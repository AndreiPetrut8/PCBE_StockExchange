package ro.pcbe.stockexchange.domain;

import java.time.Instant;
import java.util.UUID;

public final class Trade {

    private Trade(String stockSymbol, int quantity, double price, String buyer, String seller) {
        if (stockSymbol == null || stockSymbol.isBlank()) {
            throw new IllegalArgumentException("Stock symbol must not be blank.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Trade quantity must be positive.");
        }
        if (!Double.isFinite(price) || price <= 0) {
            throw new IllegalArgumentException("Trade price must be finite and positive.");
        }
        if (buyer == null || buyer.isBlank() || seller == null || seller.isBlank()) {
            throw new IllegalArgumentException("Buyer and seller must not be blank.");
        }

        this.id = UUID.randomUUID().toString();
        this.stockSymbol = stockSymbol;
        this.quantity = quantity;
        this.price = price;
        this.buyer = buyer;
        this.seller = seller;
        this.executedAt = Instant.now();
    }

    public static Trade fromOrder(Order order, Company company) {
        if (order == null || company == null || !order.isOpen()) {
            throw new IllegalArgumentException("An open order and its company are required.");
        }
        if (!order.getStockSymbol().equals(company.getStockSymbol())) {
            throw new IllegalArgumentException("Order and company stock symbols must match.");
        }
        if (!order.isTriggeredBy(company.getStockPrice())) {
            throw new IllegalArgumentException("Company price has not reached the order's limit.");
        }

        String buyer = order.getOrderType() == OrderSide.BUY ? order.getClientId() : company.getName();
        String seller = order.getOrderType() == OrderSide.SELL ? order.getClientId() : company.getName();
        return new Trade(order.getStockSymbol(), order.getQuantity(), order.getPrice(), buyer, seller);
    }

    public String getId() {
        return id;
    }

    public String getStockSymbol() {
        return stockSymbol;
    }

    public int getQuantity() {
        return quantity;
    }

    public double getPrice() {
        return price;
    }

    public double getTotalValue() {
        return price * quantity;
    }

    public String getBuyer() {
        return buyer;
    }

    public String getSeller() {
        return seller;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    private final String id;
    private final String stockSymbol;
    private final int quantity;
    private final double price;
    private final String buyer;
    private final String seller;
    private final Instant executedAt;
}
