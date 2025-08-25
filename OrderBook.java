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
    private PriceLevel[] levels;
    private Map<Long, Integer> orderMap = new HashMap<>();

    private int askMin;
    private int bidMax;

    public SingleSymbolOrderBook(String symbol, int maxPrice) {
        this.symbol = symbol;
        levels = new PriceLevel[maxPrice + 1];
        for (int i = 0; i <= maxPrice; i++) {
            levels[i] = new PriceLevel(i);
        }
        askMin = maxPrice + 1;
        bidMax = -1;
    }

    public void addOrder(Order order, int price) {
        if (order.isBuy) {
            matchBuy(order, price);
        } else {
            matchSell(order, price);
        }
    }

    private void matchBuy(Order incoming, int price) {
        while (incoming.size > 0 && askMin <= price) {
            PriceLevel bestAsk = levels[askMin];
            if (bestAsk.isEmpty()) {
                askMin++;
                continue;
            }
            executeProRata(incoming, bestAsk);
            if (bestAsk.isEmpty()) askMin++;
        }
        if (incoming.size > 0) {
            levels[price].addOrder(incoming);
            orderMap.put(incoming.id, price);
            if (price > bidMax) bidMax = price;
        }
    }

    private void matchSell(Order incoming, int price) {
        while (incoming.size > 0 && bidMax >= price) {
            PriceLevel bestBid = levels[bidMax];
            if (bestBid.isEmpty()) {
                bidMax--;
                continue;
            }
            executeProRata(incoming, bestBid);
            if (bestBid.isEmpty()) bidMax--;
        }
        if (incoming.size > 0) {
            levels[price].addOrder(incoming);
            orderMap.put(incoming.id, price);
            if (price < askMin) askMin = price;
        }
    }

    private void executeProRata(Order incoming, PriceLevel level) {
        int totalAvailable = level.totalSize;
        if (totalAvailable == 0) return;

        int remaining = incoming.size;
        double ratio = incoming.size / (double) totalAvailable;

        // One-pass: calculate, apply, and track largest remaining order
        Order largestRemainingOrder = null;
        int largestRemainingCapacity = 0;
        
        // Apply proportional fills immediately
        Iterator<Order> iterator = level.orders.values().iterator();
        while (iterator.hasNext() && remaining > 0) {
            Order resting = iterator.next();
            
            // Calculate proportional fill
            int fill = (int) Math.floor(resting.size * ratio);
            fill = Math.min(fill, Math.min(resting.size, remaining));
            
            if (fill > 0) {
                // Apply fill immediately
                resting.size -= fill;
                level.totalSize -= fill;
                incoming.size -= fill;
                remaining -= fill;

                System.out.printf("TRADE: %s %s %d @ %d against %s%n",
                        incoming.symbol,
                        incoming.isBuy ? "BUY" : "SELL",
                        fill, level.price, resting.trader);
            }
            
            // Track largest remaining order for tie-breaking
            int remainingCapacity = resting.size;
            if (remainingCapacity > largestRemainingCapacity) {
                largestRemainingCapacity = remainingCapacity;
                largestRemainingOrder = resting;
            }
            
            // Remove completely filled orders
            if (resting.size <= 0) {
                orderMap.remove(resting.id);
                iterator.remove();
            }
        }

        // Handle any leftover with the largest remaining order
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

        PriceLevel level = levels[price];
        level.removeOrder(orderId);

        if (level.isEmpty()) {
            if (price == askMin) {
                while (askMin < levels.length && levels[askMin].isEmpty()) askMin++;
            } else if (price == bidMax) {
                while (bidMax >= 0 && levels[bidMax].isEmpty()) bidMax--;
            }
        }
        return true;
    }

    public void printBook() {
        System.out.println("=== Order Book for " + symbol + " ===");
        System.out.println("Asks:");
        for (int i = askMin; i < levels.length; i++) {
            if (!levels[i].isEmpty()) {
                System.out.println("Price " + i + " | Size " + levels[i].totalSize);
            }
        }
        System.out.println("Bids:");
        for (int i = bidMax; i >= 0; i--) {
            if (!levels[i].isEmpty()) {
                System.out.println("Price " + i + " | Size " + levels[i].totalSize);
            }
        }
    }

    public int getTotalVolumeAtLevel(int price) {
        if (price < 0 || price >= levels.length) return 0;
        return levels[price].getTotalVolume();
    }

    public String getSymbol() {
        return symbol;
    }
}
