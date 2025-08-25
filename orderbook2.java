import java.util.*;

class Order {
    long id;
    String trader;
    String symbol;
    int size;
    boolean isBuy;

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

    boolean isEmpty() {
        return orders.isEmpty();
    }

    int getTotalVolume() {
        return totalSize;
    }
}

class SingleSymbolOrderBook {
    private String symbol;
    private TreeMap<Integer, PriceLevel> bids = new TreeMap<>(); // descending order
    private TreeMap<Integer, PriceLevel> asks = new TreeMap<>(); // ascending order
    private Map<Long, Integer> orderMap = new HashMap<>(); // orderId -> price

    public SingleSymbolOrderBook(String symbol) {
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
        while (incoming.size > 0 && !asks.isEmpty() && asks.firstKey() <= price) {
            int bestAskPrice = asks.firstKey();
            PriceLevel bestAsk = asks.get(bestAskPrice);
            executeProRata(incoming, bestAsk);

            if (bestAsk.isEmpty()) asks.remove(bestAskPrice);
        }
        if (incoming.size > 0) {
            bids.computeIfAbsent(price, PriceLevel::new).addOrder(incoming);
            orderMap.put(incoming.id, price);
        }
    }

    private void matchSell(Order incoming, int price) {
        while (incoming.size > 0 && !bids.isEmpty() && bids.lastKey() >= price) {
            int bestBidPrice = bids.lastKey();
            PriceLevel bestBid = bids.get(bestBidPrice);
            executeProRata(incoming, bestBid);

            if (bestBid.isEmpty()) bids.remove(bestBidPrice);
        }
        if (incoming.size > 0) {
            asks.computeIfAbsent(price, PriceLevel::new).addOrder(incoming);
            orderMap.put(incoming.id, price);
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
                orderMap.remove(resting.id);
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
                orderMap.remove(largestRemainingOrder.id);
                level.orders.remove(largestRemainingOrder.id);
            }
        }
    }

    public boolean cancel(long orderId) {
        Integer price = orderMap.remove(orderId);
        if (price == null) return false;

        if (bids.containsKey(price)) {
            PriceLevel level = bids.get(price);
            level.removeOrder(orderId);
            if (level.isEmpty()) bids.remove(price);
        } else if (asks.containsKey(price)) {
            PriceLevel level = asks.get(price);
            level.removeOrder(orderId);
            if (level.isEmpty()) asks.remove(price);
        }
        return true;
    }

    public void printBook() {
        System.out.println("=== Order Book for " + symbol + " ===");
        System.out.println("Asks:");
        for (var entry : asks.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                System.out.println("Price " + entry.getKey() + " | Size " + entry.getValue().totalSize);
            }
        }
        System.out.println("Bids:");
        for (var entry : bids.descendingMap().entrySet()) {
            if (!entry.getValue().isEmpty()) {
                System.out.println("Price " + entry.getKey() + " | Size " + entry.getValue().totalSize);
            }
        }
    }

    public int getTotalVolumeAtLevel(int price) {
        if (bids.containsKey(price)) return bids.get(price).getTotalVolume();
        if (asks.containsKey(price)) return asks.get(price).getTotalVolume();
        return 0;
    }

    public String getSymbol() {
        return symbol;
    }
}
