import java.util.*;

class Order {
    long id;
    String trader;
    String symbol;
    int size;
    boolean isBuy;
    boolean cancelled = false; // tombstone flag

    Order(long id, String trader, String symbol, int size, boolean isBuy) {
        this.id = id;
        this.trader = trader;
        this.symbol = symbol;
        this.size = size;
        this.isBuy = isBuy;
    }
}

class PriceLevel {
    int price;
    Map<Long, Order> orders = new HashMap<>();
    int totalSize = 0;

    PriceLevel(int price) {
        this.price = price;
    }

    void addOrder(Order o) {
        orders.put(o.id, o);
        totalSize += o.size;
    }

    void removeOrder(long orderId) {
        Order removed = orders.remove(orderId);
        if (removed != null) {
            totalSize -= removed.size;
        }
    }

    void removeCancelled() {
        Iterator<Map.Entry<Long, Order>> it = orders.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Order> e = it.next();
            if (e.getValue().cancelled || e.getValue().size <= 0) {
                totalSize -= e.getValue().size;
                it.remove();
            }
        }
    }

    boolean isEmpty() {
        removeCancelled();
        return orders.isEmpty();
    }
}

class SingleSymbolOrderBookHeap {
    private String symbol;

    // Max-heap for bids (highest price first)
    private PriorityQueue<PriceLevel> bids = new PriorityQueue<>(
            (a, b) -> Integer.compare(b.price, a.price));
    // Min-heap for asks (lowest price first)
    private PriorityQueue<PriceLevel> asks = new PriorityQueue<>(
            Comparator.comparingInt(a -> a.price));

    private Map<Long, Integer> orderPriceMap = new HashMap<>(); // orderId -> price
    private Map<Integer, PriceLevel> bidLevels = new HashMap<>();
    private Map<Integer, PriceLevel> askLevels = new HashMap<>();

    public SingleSymbolOrderBookHeap(String symbol) {
        this.symbol = symbol;
    }

    public void addOrder(Order order, int price) {
        if (order.isBuy) {
            matchBuy(order, price);
        } else {
            matchSell(order, price);
        }
    }

    private void matchBuy(Order incoming, int price) {
        while (incoming.size > 0 && !asks.isEmpty() && asks.peek().price <= price) {
            PriceLevel bestAsk = asks.peek();
            bestAsk.removeCancelled();
            if (bestAsk.isEmpty()) {
                asks.poll();
                askLevels.remove(bestAsk.price);
                continue;
            }
            executeProRata(incoming, bestAsk);
            if (bestAsk.isEmpty()) {
                asks.poll();
                askLevels.remove(bestAsk.price);
            }
        }
        if (incoming.size > 0) {
            PriceLevel level = bidLevels.computeIfAbsent(price, PriceLevel::new);
            level.addOrder(incoming);
            bids.add(level);
            orderPriceMap.put(incoming.id, price);
        }
    }

    private void matchSell(Order incoming, int price) {
        while (incoming.size > 0 && !bids.isEmpty() && bids.peek().price >= price) {
            PriceLevel bestBid = bids.peek();
            bestBid.removeCancelled();
            if (bestBid.isEmpty()) {
                bids.poll();
                bidLevels.remove(bestBid.price);
                continue;
            }
            executeProRata(incoming, bestBid);
            if (bestBid.isEmpty()) {
                bids.poll();
                bidLevels.remove(bestBid.price);
            }
        }
        if (incoming.size > 0) {
            PriceLevel level = askLevels.computeIfAbsent(price, PriceLevel::new);
            level.addOrder(incoming);
            asks.add(level);
            orderPriceMap.put(incoming.id, price);
        }
    }

    private void executeProRata(Order incoming, PriceLevel level) {
        int totalAvailable = level.totalSize;
        if (totalAvailable == 0) return;

        int remaining = incoming.size;
        double ratio = incoming.size / (double) totalAvailable;

        Order largestRemainingOrder = null;
        int largestRemainingCapacity = 0;

        Iterator<Order> iterator = level.orders.values().iterator();
        while (iterator.hasNext() && remaining > 0) {
            Order resting = iterator.next();
            if (resting.cancelled) continue;

            int fill = (int) Math.floor(resting.size * ratio);
            fill = Math.min(fill, Math.min(resting.size, remaining));

            if (fill > 0) {
                resting.size -= fill;
                level.totalSize -= fill;
                incoming.size -= fill;
                remaining -= fill;

                System.out.printf("TRADE: %s %s %d @ %d against %s%n",
                        incoming.symbol,
                        incoming.isBuy ? "BUY" : "SELL",
                        fill, level.price, resting.trader);
            }

            int remainingCapacity = resting.size;
            if (remainingCapacity > largestRemainingCapacity) {
                largestRemainingCapacity = remainingCapacity;
                largestRemainingOrder = resting;
            }

            if (resting.size <= 0) {
                orderPriceMap.remove(resting.id);
                iterator.remove();
            }
        }

        if (remaining > 0 && largestRemainingOrder != null && largestRemainingOrder.size > 0) {
            int finalFill = Math.min(remaining, largestRemainingOrder.size);
            largestRemainingOrder.size -= finalFill;
            level.totalSize -= finalFill;
            incoming.size -= finalFill;

            System.out.printf("TRADE: %s %s %d @ %d against %s (tie-breaker)%n",
                    incoming.symbol,
                    incoming.isBuy ? "BUY" : "SELL",
                    finalFill, level.price, largestRemainingOrder.trader);

            if (largestRemainingOrder.size <= 0) {
                orderPriceMap.remove(largestRemainingOrder.id);
                level.orders.remove(largestRemainingOrder.id);
            }
        }
    }

    public boolean cancel(long orderId) {
        Integer price = orderPriceMap.remove(orderId);
        if (price == null) return false;

        Order cancelled = null;
        PriceLevel level = bidLevels.get(price);
        if (level != null) cancelled = level.orders.get(orderId);
        if (cancelled == null) {
            level = askLevels.get(price);
            if (level != null) cancelled = level.orders.get(orderId);
        }

        if (cancelled != null) {
            cancelled.cancelled = true; // tombstone
            return true;
        }
        return false;
    }

    public void printBook() {
        System.out.println("=== Order Book for " + symbol + " ===");
        System.out.println("Asks:");
        List<PriceLevel> askSnapshot = new ArrayList<>(asks);
        askSnapshot.sort(Comparator.comparingInt(a -> a.price));
        for (PriceLevel pl : askSnapshot) {
            pl.removeCancelled();
            if (!pl.isEmpty()) {
                System.out.println("Price " + pl.price + " | Size " + pl.totalSize);
            }
        }
        System.out.println("Bids:");
        List<PriceLevel> bidSnapshot = new ArrayList<>(bids);
        bidSnapshot.sort((a, b) -> Integer.compare(b.price, a.price));
        for (PriceLevel pl : bidSnapshot) {
            pl.removeCancelled();
            if (!pl.isEmpty()) {
                System.out.println("Price " + pl.price + " | Size " + pl.totalSize);
            }
        }
    }
}
