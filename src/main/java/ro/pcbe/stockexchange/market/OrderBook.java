package ro.pcbe.stockexchange.market;

import ro.pcbe.stockexchange.domain.Company;
import ro.pcbe.stockexchange.domain.Order;
import ro.pcbe.stockexchange.domain.OrderSide;
import ro.pcbe.stockexchange.domain.Trade;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

public final class OrderBook {

    public synchronized List<Trade> addOrder(Order order, Company company) {
        if (order == null || company == null) {
            throw new IllegalArgumentException("Order and company are required.");
        }
        if (!order.getStockSymbol().equals(company.getStockSymbol())) {
            throw new IllegalArgumentException("Order and company stock symbols must match.");
        }
        if (!order.isOpen()) {
            throw new IllegalArgumentException("Only open orders can be added.");
        }
        if (getOrderById(order.getId()) != null) {
            throw new IllegalArgumentException("An order with this ID is already in the order book.");
        }

        getOrdersFor(order.getOrderType()).offer(order);
        return executeTriggeredOrders(company);
    }

    public synchronized List<Trade> executeTriggeredOrders(Company company) {
        if (company == null) {
            throw new IllegalArgumentException("Company is required.");
        }

        List<Trade> trades = new ArrayList<>();
        executeTriggeredOrders(sellOrders, company, OrderSide.SELL, trades);
        executeTriggeredOrders(buyOrders, company, OrderSide.BUY, trades);
        return List.copyOf(trades);
    }

    private void executeTriggeredOrders(
            PriorityQueue<Order> orders, Company company, OrderSide side, List<Trade> trades) {
        double stockPrice = company.getStockPrice();
        List<Order> waitingForInventory = new ArrayList<>();

        while (!orders.isEmpty()) {
            Order order = orders.peek();
            if (!order.isTriggeredBy(stockPrice)) {
                break;
            }

            int quantity = order.getQuantity();
            if (side == OrderSide.BUY && quantity > company.getStockQuantity()) {
                waitingForInventory.add(orders.poll());
                continue;
            }

            Trade trade = Trade.fromOrder(order, company);
            company.applyTrade(side, quantity);
            order.execute();
            trades.add(trade);
            orders.poll();
        }

        orders.addAll(waitingForInventory);
    }

    public synchronized boolean removeOrder(Order order) {
        return order != null && getOrdersFor(order.getOrderType()).remove(order);
    }

    public synchronized boolean removeOrder(String orderId) {
        Order order = getOrderById(orderId);
        return order != null && removeOrder(order);
    }

    public synchronized boolean cancelOrder(String orderId, String clientId) {
        Order order = getOrderById(orderId);
        if (order == null) {
            return false;
        }
        if (!order.getClientId().equals(clientId)) {
            throw new IllegalArgumentException("Only the order owner can cancel this order.");
        }
        return removeOrder(order);
    }

    public synchronized List<Trade> modifyOrder(String orderId, String clientId, double newPrice, int newQuantity, Company company) {
        Order existingOrder = getOrderById(orderId);
        if (existingOrder == null) {
            throw new IllegalArgumentException("Order not found: " + orderId);
        }
        if (!existingOrder.getClientId().equals(clientId)) {
            throw new IllegalArgumentException("Only the order owner can modify this order.");
        }
        if (company == null || !existingOrder.getStockSymbol().equals(company.getStockSymbol())) {
            throw new IllegalArgumentException("Order and company stock symbols must match.");
        }

        existingOrder.modify(newPrice, newQuantity);
        removeOrder(existingOrder);
        getOrdersFor(existingOrder.getOrderType()).offer(existingOrder);
        return executeTriggeredOrders(company);
    }

    public synchronized Order getOrderById(String orderId) {
        if (orderId == null) {
            return null;
        }
        for (Order order : buyOrders) {
            if (order.getId().equals(orderId)) {
                return order;
            }
        }
        for (Order order : sellOrders) {
            if (order.getId().equals(orderId)) {
                return order;
            }
        }
        return null;
    }

    public synchronized Order getBestBuyOrder() {
        return buyOrders.peek();
    }

    public synchronized Order getBestSellOrder() {
        return sellOrders.peek();
    }

    public synchronized List<Order> getBuyOrders() {
        return orderedSnapshot(buyOrders, BUY_PRIORITY);
    }

    public synchronized List<Order> getSellOrders() {
        return orderedSnapshot(sellOrders, SELL_PRIORITY);
    }

    public synchronized List<Order> getOpenOrders() {
        List<Order> orders = new ArrayList<>(buyOrders.size() + sellOrders.size());
        orders.addAll(getBuyOrders());
        orders.addAll(getSellOrders());
        return List.copyOf(orders);
    }

    private PriorityQueue<Order> getOrdersFor(OrderSide side) {
        return side == OrderSide.BUY ? buyOrders : sellOrders;
    }

    private static List<Order> orderedSnapshot(PriorityQueue<Order> orders, Comparator<Order> comparator) {
        List<Order> snapshot = new ArrayList<>(orders);
        snapshot.sort(comparator);
        return List.copyOf(snapshot);
    }

    private static final Comparator<Order> BUY_PRIORITY = Comparator.comparingDouble(Order::getPrice).reversed();
    private static final Comparator<Order> SELL_PRIORITY = Comparator.comparingDouble(Order::getPrice);

    private final PriorityQueue<Order> buyOrders = new PriorityQueue<>(BUY_PRIORITY);
    private final PriorityQueue<Order> sellOrders = new PriorityQueue<>(SELL_PRIORITY);
}
