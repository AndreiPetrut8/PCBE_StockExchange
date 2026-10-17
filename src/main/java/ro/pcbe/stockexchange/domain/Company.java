package ro.pcbe.stockexchange.domain;

public final class Company {

    public Company(String name, String stockSymbol, double stockPrice, int stockQuantity) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Company name must not be blank.");
        }
        if (stockSymbol == null || stockSymbol.isBlank()) {
            throw new IllegalArgumentException("Stock symbol must not be blank.");
        }
        if (!Double.isFinite(stockPrice) || stockPrice <= 0) {
            throw new IllegalArgumentException("Stock price must be finite and positive.");
        }
        if (stockQuantity < 0) {
            throw new IllegalArgumentException("Stock quantity must not be negative.");
        }

        this.name = name;
        this.stockSymbol = stockSymbol;
        this.stockPrice = stockPrice;
        this.stockQuantity = stockQuantity;
    }

    public String getName() {
        return name;
    }

    public String getStockSymbol() {
        return stockSymbol;
    }

    public synchronized double getStockPrice() {
        return stockPrice;
    }

    public synchronized int getStockQuantity() {
        return stockQuantity;
    }

    public synchronized double getStockValue() {
        return stockPrice * stockQuantity;
    }

    public synchronized void updateStockPrice(double newPrice) {
        if (!Double.isFinite(newPrice) || newPrice <= 0) {
            throw new IllegalArgumentException("New stock price must be finite and positive.");
        }
        stockPrice = newPrice;
    }

    public synchronized void applyTrade(OrderSide side, int quantity) {
        if (side == null) {
            throw new IllegalArgumentException("Order side must not be null.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Trade quantity must be positive.");
        }

        if (side == OrderSide.BUY) {
            if (quantity > stockQuantity) {
                throw new IllegalArgumentException("Company does not have enough shares to fill this order.");
            }
            stockQuantity -= quantity;
        } else {
            stockQuantity += quantity;
        }
    }

    private final String name;
    private final String stockSymbol;
    private double stockPrice;
    private int stockQuantity;
}
