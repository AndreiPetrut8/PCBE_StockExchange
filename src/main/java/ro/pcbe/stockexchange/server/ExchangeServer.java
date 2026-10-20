package ro.pcbe.stockexchange.server;

import ro.pcbe.stockexchange.domain.Company;
import ro.pcbe.stockexchange.domain.Order;
import ro.pcbe.stockexchange.domain.Trade;
import ro.pcbe.stockexchange.market.MatchingEngine;
import ro.pcbe.stockexchange.protocol.Message;
import ro.pcbe.stockexchange.protocol.MessageType;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ExchangeServer {

    private record Notice(String clientId, Message message) {
    }

    private final int port;
    private final MatchingEngine engine;
    private final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private final Map<String, Order> openOrders = new HashMap<>();
    private final ScheduledExecutorService priceTimer = Executors.newSingleThreadScheduledExecutor();
    private final Random random = new Random();
    private ServerSocket serverSocket;
    private volatile boolean running;

    public ExchangeServer(int port, MatchingEngine engine) {
        this.port = port;
        this.engine = engine;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        priceTimer.scheduleAtFixedRate(this::updatePrices, 1, 1, TimeUnit.SECONDS);
        new Thread(this::acceptClients, "acceptor").start();
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    public void stop() {
        running = false;
        priceTimer.shutdownNow();
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        clients.values().forEach(ClientHandler::close);
    }

    private void acceptClients() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                new Thread(new ClientHandler(socket, this)).start();
            } catch (IOException e) {
                if (running) {
                    System.out.println("Accept failed: " + e.getMessage());
                }
            }
        }
    }

    boolean register(String clientId, ClientHandler handler) {
        return clients.putIfAbsent(clientId, handler) == null;
    }

    void unregister(String clientId, ClientHandler handler) {
        if (clientId != null) {
            clients.remove(clientId, handler);
        }
    }

    String companyList() {
        StringJoiner rows = new StringJoiner("|");
        for (Company c : engine.getCompanies()) {
            rows.add(c.getStockSymbol() + ";" + c.getName() + ";" + c.getStockPrice() + ";" + c.getStockQuantity());
        }
        return rows.toString();
    }

    void placeOrder(Order order) {
        List<Notice> notices = new ArrayList<>();
        String accepted = String.join(";", order.getId(), order.getStockSymbol(),
                String.valueOf(order.getQuantity()), String.valueOf(order.getPrice()));
        synchronized (engine) {
            List<Trade> trades = engine.submitOrder(order);
            openOrders.put(order.getId(), order);
            notices.add(new Notice(order.getClientId(), new Message(MessageType.ORDER_ACCEPTED, accepted)));
            collectFills(trades, notices);
        }
        deliver(notices);
    }

    void modifyOrder(String clientId, String symbol, String orderId, double price, int quantity) {
        List<Notice> notices = new ArrayList<>();
        synchronized (engine) {
            List<Trade> trades = engine.modifyOrder(symbol, orderId, clientId, price, quantity);
            notices.add(new Notice(clientId, new Message(MessageType.ORDER_MODIFIED,
                    orderId + ";" + symbol + ";" + price + ";" + quantity)));
            collectFills(trades, notices);
        }
        deliver(notices);
    }

    void cancelOrder(String clientId, String symbol, String orderId) {
        synchronized (engine) {
            if (!engine.cancelOrder(symbol, orderId, clientId)) {
                throw new IllegalArgumentException("Order is not open: " + orderId);
            }
            openOrders.remove(orderId);
        }
        deliver(List.of(new Notice(clientId, new Message(MessageType.ORDER_CANCELLED, orderId))));
    }

    private void updatePrices() {
        try {
            List<Notice> notices = new ArrayList<>();
            List<Message> updates = new ArrayList<>();
            synchronized (engine) {
                for (Company c : engine.getCompanies()) {
                    double change = 1 + random.nextGaussian() * 0.01;
                    double price = Math.max(0.01, Math.round(c.getStockPrice() * change * 100) / 100.0);
                    collectFills(engine.updateStockPrice(c.getStockSymbol(), price), notices);
                    updates.add(new Message(MessageType.PRICE_UPDATE, c.getStockSymbol() + ";" + price));
                }
            }
            deliver(notices);
            for (Message update : updates) {
                clients.values().forEach(client -> client.send(update));
            }
        } catch (RuntimeException e) {
            System.out.println("Price update failed: " + e.getMessage());
        }
    }

    private void collectFills(List<Trade> trades, List<Notice> notices) {
        for (Trade trade : trades) {
            Iterator<Order> it = openOrders.values().iterator();
            while (it.hasNext()) {
                Order order = it.next();
                if (order.isFilled() && matches(order, trade)) {
                    it.remove();
                    String payload = String.join(";", order.getId(), trade.getStockSymbol(),
                            String.valueOf(trade.getQuantity()), String.valueOf(trade.getPrice()), trade.getId());
                    notices.add(new Notice(order.getClientId(), new Message(MessageType.TRADE_EXECUTED, payload)));
                    break;
                }
            }
        }
    }

    private static boolean matches(Order order, Trade trade) {
        String client = order.getClientId();
        return order.getStockSymbol().equals(trade.getStockSymbol())
                && order.getPrice() == trade.getPrice()
                && (client.equals(trade.getBuyer()) || client.equals(trade.getSeller()));
    }

    private void deliver(List<Notice> notices) {
        for (Notice notice : notices) {
            ClientHandler client = clients.get(notice.clientId());
            if (client != null) {
                client.send(notice.message());
            }
        }
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        MatchingEngine engine = new MatchingEngine();
        engine.registerCompany(new Company("Apple", "AAPL", 180, 10000));
        engine.registerCompany(new Company("Microsoft", "MSFT", 420, 8000));
        engine.registerCompany(new Company("Tesla", "TSLA", 250, 5000));
        engine.registerCompany(new Company("Nvidia", "NVDA", 120, 12000));
        new ExchangeServer(port, engine).start();
        System.out.println("Server started on port " + port);
    }
}
