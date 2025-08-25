#######################################################################
#  PriceLevel  –  “bucket” for one price on one side
#######################################################################
from array import array
from enum  import Enum, auto
import heapq
from sortedcontainers import SortedList
TICK_SIZE = 0.01
INV_TICK  = 1.0 / TICK_SIZE
VENUES    = ["CBOE","ISE","BOX","MIAX","ARCA","PHLX","GEM","EDGX",
             "BAT","MRX","BZX","NDQ","C2","AMEX"]
VENUE_MAP = {v:i for i,v in enumerate(VENUES)}
NUM_VENUES = len(VENUES)
WINDOW   = 1001           # odd → symmetric → covers ±$5 in penny ticks
HALF_W   = WINDOW // 2
def p2i(price: float) -> int: return int(round(price * INV_TICK, 2))
def i2p(idx: int)   -> float: return idx * TICK_SIZE
class PriceLevel:
    """
    One object per (price, side).  Knows nothing about NBBO;
    it just tracks per-venue and aggregate size, then returns Δagg.
    """
    __slots__ = ("venue_qty", "agg_qty")

    def __init__(self):
        self.venue_qty = array('I', [0]) * NUM_VENUES
        self.agg_qty   = 0

    def adjust(self, venue_id: int, delta: int) -> int:
        """
        ±delta (add, execute or cancel) for that venue.
        Returns Δaggregate so that the OrderBook can update its arrays.
        """
        self.venue_qty[venue_id] += delta
        new_agg = self.agg_qty + delta
        diff    = new_agg - self.agg_qty
        self.agg_qty = new_agg
        return diff                            # could be + or –
    def snapshot_by_venue(self):
        """Return (sorted_venues_with_size, size_dict) after update."""
        active = []

        for vid, q in enumerate(self.venue_qty):
            if q:                             # skip zeroes
                name = VENUES[vid]
                active.append(name)
        active.sort()                         # alphabetical
        return active, self.venue_qty 

    
class DenseWindowSide:
    __slots__=("is_bid","win0","flags","best","initial","heap","tomb")
    def __init__(self, first_idx:int, is_bid:bool):
        self.is_bid=is_bid
        self.win0  =first_idx-HALF_W
        self.flags =array('b',[0])*WINDOW
        self.best  = -1 if is_bid else float('inf')
        self.initial = -1 if is_bid else float('inf')
        self.heap  =[]
        self.tomb  =set()
        self._set(first_idx)
    # helpers
    def _in_win(self,i): return self.win0<=i<self.win0+WINDOW
    def _rel(self,i):    return i-self.win0
    def _set(self,i):
        if self._in_win(i): self.flags[self._rel(i)]=1
        else: heapq.heappush(self.heap,-i if self.is_bid else i)
    def _clr(self,i):
        if self._in_win(i): self.flags[self._rel(i)]=0
        else: self.tomb.add(i)
    def _top_heap(self):
        h,t=self.heap,self.tomb
        while h and ((-h[0] if self.is_bid else h[0]) in t):
            t.remove(-heapq.heappop(h) if self.is_bid else heapq.heappop(h))
        return (-h[0] if self.is_bid else h[0]) if h else (-1 if self.is_bid else float('inf'))
    def _best_in_window(self):
        if self.is_bid:                    # scan downwards
            for rel in range(WINDOW-1, -1, -1):
                if self.flags[rel]:
                    return self.win0 + rel
        else:                              # scan upwards
            for rel in range(WINDOW):
                if self.flags[rel]:
                    return self.win0 + rel
        return -1 if self.is_bid else float('inf')
    # public ----------------------------------------------------------
    def inc_level(self, idx:int):
        """returns prev_best_idx if best improved else None"""
        prev=self.best
        self._set(idx)
        if (self.is_bid and idx>self.best) or (not self.is_bid and idx<self.best):
            self.best=idx
        return prev if prev!=self.initial else None
    def dec_level(self, idx:int):
        self._clr(idx)
        if idx != self.best:
            return

        # ---------- recompute best ----------
        if self._in_win(idx):
            step = -1 if self.is_bid else 1
            r    = self._rel(idx) + step
            while 0 <= r < WINDOW and self.flags[r] == 0:
                r += step
            if 0 <= r < WINDOW:
                self.best = self.win0 + r
                return

        # Either we were outside window, or window scan found nothing
        self.best = self._top_heap()
        if (self.best == -1 and self.is_bid) or \
           (self.best == float('inf') and not self.is_bid):
            # heap empty → fall back to any tick still set in the window
            self.best = self._best_in_window()
    def best_price(self):
        if self.is_bid and self.best==-1: return None
        if (not self.is_bid) and self.best==float('inf'): return None
        print(self.best, "h")
        return i2p(self.best)

#######################################################################
#  OrderBook  –  owns:
#    • tick-indexed dict of PriceLevel objects
#    • dense NBBO arrays (bid_qty[], ask_qty[])
#    • best_bid_idx / best_ask_idx cursors
#######################################################################


class OrderBook:
    def __init__(self):
        # dense-window index per side, created lazily on first add
        self.side_idx = {b'BID': None, b'ASK': None}

        # {side -> {tick_idx -> PriceLevel}}
        self.levels   = {b'BID': {}, b'ASK': {}}
        
        self.order_map = {}

    def _idx_obj(self, side: bytes, idx: int) -> DenseWindowSide:
        s = self.side_idx[side]
        if s is None:
            self.side_idx[side] = s = DenseWindowSide(idx, side == b'BID')
        return s

    # ------------------------------------------------------------
    def on_add(self, oid, venue, side, price, qty):
        idx,vid=p2i(price),VENUE_MAP[venue]
        lvl=self.levels[side].get(idx)
        first=False
        if lvl is None:
            lvl=self.levels[side][idx]=PriceLevel()
            first=True
        lvl.adjust(vid,+qty)
        self.order_map[oid]=(side,idx,vid,qty)
        if first:
            prev_best_idx=self._idx_obj(side,idx).inc_level(idx)
            if prev_best_idx is not None:
                prev_lvl=self.levels[side][prev_best_idx]
                old_price=i2p(prev_best_idx)
                old_size =prev_lvl.agg_qty
                old_venues,_=prev_lvl.snapshot_by_venue()
                new_best=i2p(idx)
                new_size=lvl.agg_qty
                return new_best,new_size,old_price,old_size,old_venues
    
    def on_cancel(self, oid):
        side,idx,vid,qty=self.order_map.pop(oid)
        lvl=self.levels[side][idx]; lvl.adjust(vid,-qty)
        if lvl.agg_qty==0:
            self._idx_obj(side,idx).dec_level(idx)
            del self.levels[side][idx]
            
    def on_replace(self,new_oid,orig_oid,venue,side,price,qty):
        # add first --> get NBBO improvement snapshot if any
        info=self.on_add(new_oid,venue,side,price,qty)
        self.on_cancel(orig_oid)
        return info

    def on_execute(self, oid, exec_qty):
        pass
        side, idx, venue_id, qty_left = self.order_map[oid]
        take = min(exec_qty, qty_left)
        self.order_map[oid] = (side, idx, venue_id, qty_left - take)

        lvl  = self.levels[side][idx]
        dagg = lvl.adjust(venue_id, -take)

        if lvl.agg_qty == 0:
            self._idx_obj(side,idx).dec_level(idx)
            del self.levels[side][idx]
        if (qty_left - take) == 0:
            del self.order_map[oid]
    def best_bid(self): 
        s=self.side_idx[b'BID']
        return None if s is None else s.best_price()
    def best_ask(self):
        s=self.side_idx[b'ASK']
        return None if s is None else s.best_price()
    


