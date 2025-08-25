import pyorderbook


class BatchedBookDriver:
    """
    Batch driver that trusts pyorderbook.OrderBook.on_batch() to return
    ONLY meaningful payloads (3-tuple for executions, 5-tuple for NBBO
    improvements).  We publish by inspecting the length.
    """

    def __init__(self, publisher, batch_size: int = 32):
        self.book        = pyorderbook.OrderBook()
        self.publisher   = publisher
        self.batch       = []
        self.batch_size  = batch_size

    # ---------------- flush ----------------
    def _flush(self):
        if not self.batch:
            return

        results = self.book.on_batch(self.batch)  # one C++ call
        self.batch.clear()

        for res in results:
            if len(res) == 4:                     # execution payload
                exec_px, rem, per_venue, venue_str = res
                self.publisher.publish({
                    "type":            "execute",
                    "exec_price":      exec_px,
                    "total_remaining": rem,
                    "per_venue_qty":   per_venue,
                })
            else:                                 # len == 5 → price-improvement
                new_px, new_sz, old_px, old_sz, venue_str = res
                self.publisher.publish({
                    "type":       "add",          # add/replace NBBO jump
                    "new_price":  new_px,
                    "new_size":   new_sz,
                    "old_price":  old_px,
                    "old_size":   old_sz,
                    "old_venues": venue_str,
                })

    # ---------------- public API ----------------
    def on_event(self, evt):
        """
        evt one of:
          ("add",     oid, venue, side, price,   qty)
          ("cancel",  oid)
          ("replace", new_oid, old_oid, venue, side, price, qty)
          ("execute", oid, exec_qty)
        """
        self.batch.append(evt)
        if len(self.batch) >= self.batch_size:
            self._flush()

    def close(self):
        """Flush any leftovers at shutdown."""
        self._flush()
