package ro.pcbe.stockexchange.market;

import ro.pcbe.stockexchange.domain.Company;
import ro.pcbe.stockexchange.domain.Order;
import ro.pcbe.stockexchange.domain.Trade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MatchingEngine {

    public synchronized void registerCompany(Company company) {
        if (company == null) {
            throw new IllegalArgumentException("Company must not be null.");
        }
        String symbol = company.getStockSymbol();
        if (companies.containsKey(symbol)) {
            throw new IllegalArgumentException("A company with this stock symbol is already registered: " + symbol);
        }

        companies.put(symbol, company);
        orderBooks.put(symbol, new OrderBook());
        tradeHistory.put(symbol, new ArrayList<>());
    }

    public synchronized Company getCompany(String stockSymbol) {
        return companies.get(stockSymbol);
    }

    public synchronized List<Company> getCompanies() {
        return List.copyOf(companies.values());
    }

    public synchronized List<Trade> submitOrder(Order order) {
        if (order == null) {
            throw new IllegalArgumentException("Order must not be null.");
        }
        String symbol = order.getStockSymbol();
        OrderBook orderBook = orderBooks.get(symbol);
        if (orderBook == null) {
            throw new IllegalArgumentException("No company is registered for stock symbol: " + symbol);
        }

        List<Trade> trades = orderBook.addOrder(order, companies.get(symbol));
        tradeHistory.get(symbol).addAll(trades);
        return trades;
    }

    public synchronized List<Trade> updateStockPrice(String stockSymbol, double newPrice) {
        Company company = requireCompany(stockSymbol);
        company.updateStockPrice(newPrice);
        List<Trade> trades = orderBooks.get(stockSymbol).executeTriggeredOrders(company);
        tradeHistory.get(stockSymbol).addAll(trades);
        return trades;
    }

    public synchronized OrderBook getOrderBook(String stockSymbol) {
        return requireOrderBook(stockSymbol);
    }

    public synchronized boolean cancelOrder(String stockSymbol, String orderId, String clientId) {
        return requireOrderBook(stockSymbol).cancelOrder(orderId, clientId);
    }

    public synchronized List<Trade> modifyOrder(
            String stockSymbol, String orderId, String clientId, double newPrice, int newQuantity) {
        OrderBook orderBook = requireOrderBook(stockSymbol);
        Company company = requireCompany(stockSymbol);
        List<Trade> trades = orderBook.modifyOrder(orderId, clientId, newPrice, newQuantity, company);
        tradeHistory.get(stockSymbol).addAll(trades);
        return trades;
    }

    public synchronized List<Trade> getTradeHistory(String stockSymbol) {
        List<Trade> history = tradeHistory.get(stockSymbol);
        if (history == null) {
            throw new IllegalArgumentException("No company is registered for stock symbol: " + stockSymbol);
        }
        return List.copyOf(history);
    }

    private OrderBook requireOrderBook(String stockSymbol) {
        OrderBook orderBook = orderBooks.get(stockSymbol);
        if (orderBook == null) {
            throw new IllegalArgumentException("No company is registered for stock symbol: " + stockSymbol);
        }
        return orderBook;
    }

    private Company requireCompany(String stockSymbol) {
        Company company = companies.get(stockSymbol);
        if (company == null) {
            throw new IllegalArgumentException("No company is registered for stock symbol: " + stockSymbol);
        }
        return company;
    }

    private final Map<String, Company> companies = new HashMap<>();
    private final Map<String, OrderBook> orderBooks = new HashMap<>();
    private final Map<String, List<Trade>> tradeHistory = new HashMap<>();
}
