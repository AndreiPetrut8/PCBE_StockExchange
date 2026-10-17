package ro.pcbe.stockexchange.domain;

import java.time.Instant;
import java.util.UUID;

public final class Order {

    public Order(String clientId, String stockSymbol, int quantity, double price, OrderSide orderType) {
        this(UUID.randomUUID().toString(), clientId, stockSymbol, quantity, price, orderType);
    }

    public Order(String id, String clientId, String stockSymbol, int quantity, double price, OrderSide orderType) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Order ID must not be blank.");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("Client ID must not be blank.");
        }
        if (stockSymbol == null || stockSymbol.isBlank()) {
            throw new IllegalArgumentException("Stock symbol must not be blank.");
        }
        validateQuantity(quantity);
        validatePrice(price);
        if (orderType == null) {
            throw new IllegalArgumentException("Order side must not be null.");
        }

        this.id = id;
        this.clientId = clientId;
        this.stockSymbol = stockSymbol;
        this.quantity = quantity;
        this.price = price;
        this.orderType = orderType;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
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

    public OrderSide getOrderType() {
        return orderType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isOpen() {
        return quantity > 0;
    }

    public boolean isFilled() {
        return quantity == 0;
    }

    public boolean isTriggeredBy(double stockPrice) {
        return orderType == OrderSide.BUY ? stockPrice <= price : stockPrice >= price;
    }

    public void execute() {
        if (!isOpen()) {
            throw new IllegalStateException("Only open orders can be executed.");
        }
        quantity = 0;
    }

    public void modify(double newPrice, int newQuantity) {
        validatePrice(newPrice);
        validateQuantity(newQuantity);
        if (!isOpen()) {
            throw new IllegalStateException("A filled order cannot be modified.");
        }

        price = newPrice;
        quantity = newQuantity;
    }

    private static void validateQuantity(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Order quantity must be positive.");
        }
    }

    private static void validatePrice(double price) {
        if (!Double.isFinite(price) || price <= 0) {
            throw new IllegalArgumentException("Order price must be finite and positive.");
        }
    }

    private final String id;
    private final String clientId;
    private final String stockSymbol;
    private int quantity;
    private double price;
    private final OrderSide orderType;
    private final Instant createdAt;
}
