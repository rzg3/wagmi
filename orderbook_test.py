"""
Quick sanity-tests for the dense-window+heap OrderBook.

Assumptions
-----------
* OrderBook, PriceLevel, p2i/i2p helpers are in the same directory
  (or pip-installable package path).
* 'on_execute' is a no-op for now (per user note).

This is **not** a full unit-suite—just smoke checks that the
best-price cursor and NBBO-improvement tuple behave the same
as the original SortedList design.
"""

from orderbook import OrderBook, VENUES, i2p

def assert_eq(a, b, msg=""):
    if a != b:
        raise AssertionError(f"{msg}  {a!r} != {b!r}")

def test_bid_improvement():
    ob = OrderBook()

    # 1. first BID @ 2.50 → no improvement tuple
    ret = ob.on_add("oid1", "CBOE", b'BID', 2.50, 100)
    assert_eq(ret, None, "first bid should not return improvement")
    assert_eq(ob.best_bid(), 2.50, "best bid after first add")

    # 2. better BID @ 2.55 → improvement tuple
    ret = ob.on_add("oid2", "ISE", b'BID', 2.55, 50)
    new_px, new_sz, old_px, old_sz, old_exchs = ret
    assert_eq((round(new_px, 2), round(old_px, 2)), (2.55, 2.50),
              "bid NBBO improvement prices wrong")
    assert "CBOE" in old_exchs, "old venue list missing CBOE"
    assert_eq(round(ob.best_bid(), 2), 2.55, "best bid not updated")

def test_cancel_drops_best():
    ob = OrderBook()
    ob.on_add("b1", "CBOE", b'BID', 2.50, 50)
    ob.on_add("b2", "ISE",  b'BID', 2.45, 10)

    # cancel highest level
    ob.on_cancel("b1")
    assert_eq(ob.best_bid(), 2.45, "best bid did not fall back")

def test_ask_side():
    ob = OrderBook()
    ob.on_add("a1", "CBOE", b'ASK', 2.80, 40)
    ob.on_add("a2", "ARCA", b'ASK', 2.75, 20)   # better offer
    assert_eq(ob.best_ask(), 2.75)

def test_replace_atomic():
    ob = OrderBook()
    ob.on_add("x1", "CBOE", b'BID', 2.50, 100)
    # replace with BETTER price
    ret = ob.on_replace("x2", "x1", "CBOE", b'BID', 2.60, 100)
    new_px, new_sz, old_px, old_sz, old_exchs = ret
    assert_eq((new_px, old_px), (2.60, 2.50))
    # ensure best now 2.60
    assert_eq(ob.best_bid(), 2.60)

def test_heap_fallback():
    """
    Add a price 30 dollars away so it's outside the ±$5 window.
    Ensure the heap path sets best correctly, then cancel it
    and verify best snaps back to window price.
    """
    ob = OrderBook()
    ob.on_add("near", "CBOE", b'BID', 2.50, 10)        # inside window
    far_price = 32.50
    ob.on_add("far", "ISE", b'BID', far_price, 5)      # outside window

    assert_eq(ob.best_bid(), far_price, "far order should dominate best")

    ob.on_cancel("far")                                # remove far bid
    assert_eq(ob.best_bid(), 2.50, "best should fall back to near price")

def test_heap_fallback2():
    """
    Add a price 30 dollars away so it's outside the ±$5 window.
    Ensure the heap path sets best correctly, then cancel it
    and verify best snaps back to window price.
    """
    ob = OrderBook()
    ob.on_add("near", "CBOE", b'BID', 2.50, 10)        # inside window
    far_price = 32.50
    ob.on_add("far", "ISE", b'BID', far_price, 5)      # outside window
    ob.on_add("far2", "CBOE", b'BID', -32.50, 10) 

    assert_eq(ob.best_bid(), far_price, "far order should dominate best")

    ob.on_cancel("far")                                # remove far bid
    ob.on_cancel("near") 
    print(ob.best_bid())     
    assert_eq(ob.best_bid(), -32.50, "best should fall back to near price")

def run_all():
    for fn in globals().values():
        if callable(fn) and fn.__name__.startswith("test_"):
            fn()
    print("All tests passed ✔")

if __name__ == "__main__":
    run_all()
