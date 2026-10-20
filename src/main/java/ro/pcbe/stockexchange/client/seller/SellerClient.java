package ro.pcbe.stockexchange.client.seller;

import ro.pcbe.stockexchange.domain.Order;
import ro.pcbe.stockexchange.domain.OrderSide;
import ro.pcbe.stockexchange.protocol.Message;
import ro.pcbe.stockexchange.protocol.MessageType;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe seller client.
 *
 * Concurrency design
 * ------------------
 *  - 3 kinds of threads touch this object:
 *      * caller threads (console or strategy threads) call sell/cancel/modify
 *      * ONE reader thread receives server messages and updates the state
 *      * ONE writer thread writes to the socket via an outbox queue
 *  - All mutable seller state is guarded by ONE lock for atomic updates.
 */
public class SellerClient implements AutoCloseable {

    private enum Status { PENDING, OPEN }

    private record OpenOrder(String id, String symbol, int quantity, double price, Status status) { }

    private final String sellerId;
    private final String host;
    private final int port;

    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;

    private final BlockingQueue<Message> outbox = new LinkedBlockingQueue<>();
    private Thread readerThread;
    private Thread writerThread;
    private volatile boolean running;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // ---- state guarded by 'lock' ----
    private final Object lock = new Object();
    private final Map<String, OpenOrder> openOrders = new HashMap<>(); // orderId -> order
    private final Map<String, Integer> portfolio = new HashMap<>();    // symbol -> shares held to sell
    private final Map<String, Double> lastPrices = new HashMap<>();    // symbol -> last known market price
    private final Set<String> appliedTrades = new HashSet<>();         // trade ids already counted
    private double totalEarned = 0;
    // ---------------------------------

    public SellerClient(String sellerId, String host, int port) {
        if (sellerId == null || sellerId.isBlank()) {
            throw new IllegalArgumentException("Seller ID must not be blank.");
        }
        this.sellerId = sellerId;
        this.host = host;
        this.port = port;
    }

    public void setInitialShares(String symbol, int quantity) {
        if (symbol == null || symbol.isBlank() || quantity < 0) {
            throw new IllegalArgumentException("Invalid initial inventory.");
        }
        synchronized (lock) {
            portfolio.put(symbol.toUpperCase(), quantity);
        }
    }

    public void connect() throws IOException {
        socket = new Socket(host, port);
        out = new ObjectOutputStream(socket.getOutputStream());
        out.flush();
        in = new ObjectInputStream(socket.getInputStream());
        running = true;

        writerThread = new Thread(this::writeLoop, "seller-writer-" + sellerId);
        readerThread = new Thread(this::readLoop, "seller-reader-" + sellerId);
        writerThread.setDaemon(true);
        readerThread.setDaemon(true);
        writerThread.start();
        readerThread.start();

        enqueue(MessageType.REGISTER, sellerId + ";SELLER");
        enqueue(MessageType.LIST_COMPANIES, sellerId);
    }

    private boolean enqueue(MessageType type, String payload) {
        if (!running) return false;
        return outbox.offer(new Message(type, payload));
    }

    private void writeLoop() {
        try {
            while (true) {
                Message m = outbox.poll(100, TimeUnit.MILLISECONDS);
                if (m == null) {
                    if (!running) break;
                    continue;
                }
                out.writeObject(m);
                out.flush();
                out.reset();
            }
        } catch (IOException e) {
            if (running) println("[!] Send failed: " + e.getMessage());
            running = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void sell(String symbol, int quantity, double minPrice) {
        if (!running) {
            println("Not connected.");
            return;
        }
        Order order;
        try {
            order = new Order(sellerId, symbol.toUpperCase(), quantity, minPrice, OrderSide.SELL);
        } catch (IllegalArgumentException e) {
            println("Invalid order: " + e.getMessage());
            return;
        }

        synchronized (lock) {
            openOrders.put(order.getId(), new OpenOrder(order.getId(), order.getStockSymbol(),
                    order.getQuantity(), order.getPrice(), Status.PENDING));
        }

        boolean queued = enqueue(MessageType.SELL_ORDER, String.join(";", order.getId(), sellerId,
                order.getStockSymbol(), String.valueOf(order.getQuantity()), String.valueOf(order.getPrice())));
        if (!queued) {
            synchronized (lock) { openOrders.remove(order.getId()); }
            println("Could not send order (connection closed).");
        }
    }

    public void cancel(String orderId) {
        OpenOrder o;
        synchronized (lock) { o = openOrders.get(orderId); }
        if (o == null) {
            println("No open order with id " + orderId);
            return;
        }
        enqueue(MessageType.CANCEL_ORDER, String.join(";", sellerId, o.symbol(), orderId));
    }

    public void modify(String orderId, double newPrice, int newQuantity) {
        if (!Double.isFinite(newPrice) || newPrice <= 0 || newQuantity <= 0) {
            println("Price and quantity must be positive.");
            return;
        }
        OpenOrder o;
        synchronized (lock) { o = openOrders.get(orderId); }
        if (o == null) {
            println("No open order with id " + orderId);
            return;
        }
        enqueue(MessageType.MODIFY_ORDER, String.join(";", sellerId, o.symbol(), orderId,
                String.valueOf(newPrice), String.valueOf(newQuantity)));
    }

    public void requestCompanies() {
        enqueue(MessageType.LIST_COMPANIES, sellerId);
    }

    public void printPortfolio() {
        Map<String, Integer> holdings;
        Map<String, Double> prices;
        double earned;
        synchronized (lock) {
            holdings = new TreeMap<>(portfolio);
            prices = new HashMap<>(lastPrices);
            earned = totalEarned;
        }
        StringBuilder sb = new StringBuilder("--- Portfolio of " + sellerId + " ---\n");
        if (holdings.isEmpty()) sb.append("(empty)\n");
        holdings.forEach((s, q) -> {
            Double p = prices.get(s);
            sb.append(s).append(" : ").append(q);
            if (p != null) sb.append(String.format("  (market %.2f)", p));
            sb.append('\n');
        });
        sb.append(String.format("Total earned: %.2f", earned));
        println(sb.toString());
    }

    public void printOpenOrders() {
        List<OpenOrder> snapshot;
        Map<String, Double> prices;
        synchronized (lock) {
            snapshot = new ArrayList<>(openOrders.values());
            prices = new HashMap<>(lastPrices);
        }
        StringBuilder sb = new StringBuilder("--- Open SELL orders ---\n");
        if (snapshot.isEmpty()) sb.append("(none)");
        for (OpenOrder o : snapshot) {
            Double p = prices.get(o.symbol());
            sb.append(String.format("%s  %s x%d min %.2f [%s]%s%n", o.id(), o.symbol(), o.quantity(),
                    o.price(), o.status(), p == null ? "" : String.format("  (market %.2f)", p)));
        }
        println(sb.toString().stripTrailing());
    }

    private void readLoop() {
        try {
            while (running) {
                Object obj = in.readObject();
                if (obj instanceof Message m) handle(m);
            }
        } catch (IOException e) {
            if (running) println("[!] Connection lost: " + e.getMessage());
        } catch (ClassNotFoundException e) {
            println("[!] Unknown message: " + e.getMessage());
        } finally {
            running = false;
        }
    }

    private void handle(Message m) {
        String payload = m.getPayload() == null ? "" : m.getPayload();
        String[] p = payload.split(";");
        String event;

        try {
            switch (m.getType()) {
                case COMPANY_LIST -> {
                    StringBuilder sb = new StringBuilder("--- Companies ---");
                    synchronized (lock) {
                        for (String row : payload.split("\\|")) {
                            if (row.isBlank()) continue;
                            String[] c = row.split(";");
                            lastPrices.put(c[0], Double.parseDouble(c[2]));
                            sb.append(String.format("%n%s (%s)  price %s  shares available %s", c[0], c[1], c[2], c[3]));
                        }
                    }
                    event = sb.toString();
                }
                case ORDER_ACCEPTED -> {
                    synchronized (lock) {
                        openOrders.computeIfPresent(p[0], (id, o) ->
                                new OpenOrder(o.id(), o.symbol(), o.quantity(), o.price(), Status.OPEN));
                    }
                    event = "[OK] Sell Order " + p[0] + " accepted (" + p[1] + " x" + p[2] + " @ " + p[3] + ")";
                }
                case TRADE_EXECUTED -> {
                    String symbol = p[1];
                    int qty = Integer.parseInt(p[2]);
                    double price = Double.parseDouble(p[3]);
                    boolean applied;
                    synchronized (lock) {
                        applied = appliedTrades.add(p[4]);
                        if (applied) {
                            openOrders.remove(p[0]);
                            portfolio.computeIfPresent(symbol, (k, v) -> Math.max(0, v - qty));
                            totalEarned += qty * price;
                        }
                    }
                    event = applied
                            ? String.format("[TRADE %s] Sold %d %s @ %.2f (order %s)", p[4], qty, symbol, price, p[0])
                            : null;
                }
                case ORDER_MODIFIED -> {
                    synchronized (lock) {
                        openOrders.computeIfPresent(p[0], (id, o) -> new OpenOrder(id, o.symbol(),
                                Integer.parseInt(p[3]), Double.parseDouble(p[2]), Status.OPEN));
                    }
                    event = "[OK] Order " + p[0] + " modified";
                }
                case ORDER_CANCELLED -> {
                    synchronized (lock) { openOrders.remove(p[0]); }
                    event = "[OK] Order " + p[0] + " cancelled";
                }
                case PRICE_UPDATE -> {
                    synchronized (lock) { lastPrices.put(p[0], Double.parseDouble(p[1])); }
                    event = "[PRICE] " + p[0] + " -> " + p[1];
                }
                case ERROR -> {
                    synchronized (lock) {
                        OpenOrder o = openOrders.get(p[0]);
                        if (o != null && o.status() == Status.PENDING) {
                            openOrders.remove(p[0]);
                        }
                    }
                    event = "[ERROR] " + payload;
                }
                default -> event = "[INFO] " + m.getType() + " " + payload;
            }
        } catch (RuntimeException e) {
            event = "[!] Malformed " + m.getType() + " message: " + payload;
        }
        if (event != null) println(event);
    }

    private static void println(String s) {
        System.out.println(s);
    }

    public void runConsole() {
        println("Commands:\n"
                + "  companies\n"
                + "  sell <symbol> <qty> <minPrice>\n"
                + "  modify <orderId> <newPrice> <newQty>\n"
                + "  cancel <orderId>\n"
                + "  orders | portfolio | quit");
        try (Scanner sc = new Scanner(System.in)) {
            while (running && sc.hasNextLine()) {
                String[] t = sc.nextLine().trim().split("\\s+");
                if (t[0].isEmpty()) continue;
                try {
                    switch (t[0].toLowerCase()) {
                        case "companies" -> requestCompanies();
                        case "sell" -> sell(t[1], Integer.parseInt(t[2]), Double.parseDouble(t[3]));
                        case "modify" -> modify(t[1], Double.parseDouble(t[2]), Integer.parseInt(t[3]));
                        case "cancel" -> cancel(t[1]);
                        case "orders" -> printOpenOrders();
                        case "portfolio" -> printPortfolio();
                        case "quit", "exit" -> { return; }
                        default -> println("Unknown command.");
                    }
                } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
                    println("Bad arguments. Example: sell ABC 10 25.5");
                }
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (running) {
            enqueue(MessageType.DISCONNECT, sellerId);
            running = false;
        }
        try {
            if (writerThread != null) writerThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) { }
    }

    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "seller-" + (System.currentTimeMillis() % 1000);
        String host = args.length > 1 ? args[1] : "localhost";
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 5000;

        try (SellerClient client = new SellerClient(id, host, port)) {
            client.connect();
            println("Connected as " + id);
            client.runConsole();
        } catch (IOException e) {
            println("Cannot connect to " + host + ":" + port + " - " + e.getMessage());
        }
    }
}