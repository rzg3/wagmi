#include <pybind11/pybind11.h>
#include <pybind11/stl.h>
#include <array>
#include <vector>
#include <set>
#include <unordered_map>
#include <string_view>
#include <limits>

namespace py = pybind11;

/* ---------- constants / helpers ---------- */
constexpr double TICK = 0.01;
constexpr int    INV_TICK = 100;
constexpr size_t NUM_VENUES = 14;

static const std::array<std::string_view,NUM_VENUES> VENUES = {
    "CBOE","ISE","BOX","MIAX","ARCA","PHLX","GEM","EDGX",
    "BAT","MRX","BZX","NDQ","C2","AMEX"
};
static const std::unordered_map<std::string_view,size_t> VENUE_MAP = {
    {"CBOE",0},{"ISE",1},{"BOX",2},{"MIAX",3},{"ARCA",4},
    {"PHLX",5},{"GEM",6},{"EDGX",7},{"BAT",8},{"MRX",9},
    {"BZX",10},{"NDQ",11},{"C2",12},{"AMEX",13}
};

inline int    p2i(double p){ return int(p*INV_TICK + 0.5); }
inline double i2p(int idx){  return idx*TICK; }

/* ---------- PriceLevel ---------- */
struct PriceLevel {
    std::array<uint32_t,NUM_VENUES> vqty{};
    uint32_t agg{0};
    void adjust(size_t vid,int d){ vqty[vid]+=d; agg+=d; }
};

/* ---------- SideBook  (only sparse set) ---------- */
class SideBook {
    bool is_bid_;
    std::set<int> ticks_;                       /* sorted price indices   */
    std::unordered_map<int,PriceLevel> levels_; /* idx → bucket           */

public:
    explicit SideBook(bool is_bid): is_bid_(is_bid){}
    ~SideBook() = default;

    /* add qty, return prev_best if best moved else INT_MIN */
    int add(int idx,size_t vid,uint32_t qty){
        auto &pl = levels_[idx];
        int prev_best = best_idx();
        bool first    = pl.agg==0;
        pl.adjust(vid, int(qty));
        if(first) ticks_.insert(idx);
        int new_best  = best_idx();
        if (prev == (is_bid_ ? -1 : std::numeric_limits<int>::max())){
            return std::numeric_limits<int>::min();  // ignore sentinel-to-real 
        }
        return (new_best != prev_best) ? prev_best
                                       : std::numeric_limits<int>::min();
    }

    /* remove qty, erase level if empty, update ticks_ */
    void remove(int idx,size_t vid,uint32_t qty){
        auto &pl = levels_.at(idx);
        pl.adjust(vid,-int(qty));
        if(pl.agg==0){
            ticks_.erase(idx);
            levels_.erase(idx);
        }
    }

    int  best_idx() const {
        if(ticks_.empty())
            return is_bid_ ? -1 : std::numeric_limits<int>::max();
        return is_bid_ ? *ticks_.rbegin() : *ticks_.begin();
    }
    double best_price() const {
        int b = best_idx();
        if(is_bid_ && b==-1) return NAN;
        if(!is_bid_ && b==std::numeric_limits<int>::max()) return NAN;
        return i2p(b);
    }

    /* expose levels for snapshot */
    const std::unordered_map<int,PriceLevel>& levels() const { return levels_; }

    py::dict snapshot(int idx) const {
        py::dict d;
        auto it = levels_.find(idx);
        if(it==levels_.end()) return d;
        const auto& pl = it->second;
        for(size_t i=0;i<NUM_VENUES;++i)
            if(pl.vqty[i]) d[py::str(VENUES[i])] = pl.vqty[i];
        return d;
    }
};

/* ---------- OrderBook ---------- */
class OrderBook {
    SideBook bid_{true};
    SideBook ask_{false};

    struct Meta{ SideBook* sb; int idx; size_t vid; uint32_t qty; };
    std::unordered_map<std::string,Meta> omap_;

    /* helper to build NBBO-improvement tuple */
    py::object nbbo_tuple(SideBook& sb, int new_idx, int old_idx) {
        const auto& new_pl = sb.levels().at(new_idx);
        const auto& old_pl = sb.levels().at(old_idx);
        return py::make_tuple(
            i2p(new_idx), new_pl.agg,
            i2p(old_idx), old_pl.agg,
            venue_string(old_pl)             // <── concatenated string
        );
    }
    /* alphabetically-sorted concatenation of venues with qty > 0  */
    static std::string venue_string(const PriceLevel& pl) {
        std::string out;
        for (size_t i = 0; i < NUM_VENUES; ++i)
            if (pl.vqty[i]) out.push_back(VENUE_CODE[i]);
        std::sort(out.begin(), out.end());        // alphabetical
        return out;                               // e.g. "CNX"
    }
    public:
    OrderBook() = default;

    /* ---------- single-message API ---------- */
    py::object on_add(const std::string& oid,const char venue_code,,
                      const py::bytes& side_b,double price,uint32_t qty){
        bool bid = side_b==py::bytes("BID");
        SideBook& sb = bid? bid_ : ask_;
        int idx = p2i(price);
        size_t vid = VENUE_MAP.at(venue_code);

        int prev_best = sb.add(idx,vid,qty);
        omap_[oid] = {&sb,idx,vid,qty};

        if(prev_best!=std::numeric_limits<int>::min())
            return nbbo_tuple(sb,idx,prev_best);
        return py::none();
    }

    void on_cancel(const std::string& oid){
        auto it=omap_.find(oid); if(it==omap_.end()) return;
        auto m=it->second; omap_.erase(it);
        m.sb->remove(m.idx,m.vid,m.qty);
    }

    py::object on_replace(const std::string& new_oid,const std::string& old_oid,
                          char venue_code,const py::bytes& side_b,
                          double price,uint32_t qty){
        auto res=on_add(new_oid,venue_code,side_b,price,qty);
        on_cancel(old_oid);
        return res;
    }

    py::object on_execute(const std::string& oid, uint32_t exec_qty) {
        auto it = omap_.find(oid);
        if (it == omap_.end()) return py::none();

        Meta& m   = it->second;
        uint32_t take = std::min(exec_qty, m.qty);
        m.qty    -= take;
        m.sb->remove(m.idx, m.vid, take);

        const PriceLevel& pl = m.sb->levels().at(m.idx);
        py::list per_venue;
        for (auto q : pl.vqty) per_venue.append(q);

        std::string venues = venue_string(pl);

        if (m.qty == 0) omap_.erase(it);

        /* exec_price, total_remaining, qty_list, venue_str  (len == 4) */
        return py::make_tuple(i2p(m.idx), pl.agg, per_venue, venues);
    }
    /* ---------- batch API ---------- */
/* ---------- batch API ---------- */
    py::list on_batch(py::iterable batch) {
        py::list out;
        out.reserve(py::len(batch));              // pre-allocate Python list

        for (auto item : batch) {
            auto t   = item.cast<py::tuple>();
            std::string cmd = t[0].cast<std::string>();

            if (cmd == "add") {
                py::object res = on_add(
                    t[1].cast<std::string>(),     // oid
                    t[2].cast<char>(),     // venue
                    t[3].cast<py::bytes>(),       // side
                    t[4].cast<double>(),          // price
                    t[5].cast<uint32_t>()         // qty
                );
                if (!res.is_none())
                    out.append(res);

            } else if (cmd == "execute") {
                py::object res = on_execute(
                    t[1].cast<std::string>(),     // oid
                    t[2].cast<uint32_t>()         // exec_qty
                );
                if (!res.is_none())
                    out.append(res);

            } else if (cmd == "cancel") {
                on_cancel(t[1].cast<std::string>());   // nothing to append

            } else if (cmd == "replace") {
                py::object res = on_replace(
                    t[1].cast<std::string>(),     // new_oid
                    t[2].cast<std::string>(),     // old_oid
                    t[3].cast<char>(),     // venue
                    t[4].cast<py::bytes>(),       // side
                    t[5].cast<double>(),          // price
                    t[6].cast<uint32_t>()         // qty
                );
                if (!res.is_none())
                    out.append(res);

            } else {
                throw std::runtime_error("Bad cmd");
            }
        }
        return out;                               // list may be shorter than batch
    }


    /* ---------- utilities ---------- */
    py::object best_bid() const {
        double p=bid_.best_price(); return std::isnan(p)?py::none():py::float_(p);
    }
    py::object best_ask() const {
        double p=ask_.best_price(); return std::isnan(p)?py::none():py::float_(p);
    }
    py::dict snapshot(const py::bytes& side_b,double price) const {
        const SideBook& sb=(side_b==py::bytes("BID"))?bid_:ask_;
        return sb.snapshot(p2i(price));
    }
};

/* ---------- bindings ---------- */
PYBIND11_MODULE(pyorderbook, m){
    using namespace py::literals;
    py::class_<OrderBook>(m,"OrderBook")
        .def(py::init<>())
        .def("on_add",     &OrderBook::on_add,
             "oid"_a,"venue"_a,"side"_a,"price"_a,"qty"_a)
        .def("on_cancel",  &OrderBook::on_cancel,"oid"_a)
        .def("on_replace", &OrderBook::on_replace,
             "new_oid"_a,"old_oid"_a,"venue"_a,"side"_a,"price"_a,"qty"_a)
        .def("on_execute", &OrderBook::on_execute,"oid"_a,"exec_qty"_a)
        .def("on_batch",   &OrderBook::on_batch,"batch"_a)
        .def("best_bid",   &OrderBook::best_bid)
        .def("best_ask",   &OrderBook::best_ask)
        .def("snapshot",   &OrderBook::snapshot,"side"_a,"price"_a);
}
