package ro.pcbe.stockexchange.protocol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ro.pcbe.stockexchange.client.seller.SellerClient;
import ro.pcbe.stockexchange.protocol.Message;
import ro.pcbe.stockexchange.protocol.MessageType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SellerClientTest {

    private static final String SELLER = "seller1";

    private FakeServer server;
    private SellerClient client;

    private ByteArrayOutputStream captured;
    private PrintStream originalOut;
    private Locale originalLocale;
    private final AtomicInteger syncCounter = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        originalLocale = Locale.getDefault();
        Locale.setDefault(Locale.US);

        originalOut = System.out;
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true));

        server = new FakeServer();
        server.acceptAsync();

        client = new SellerClient(SELLER, "localhost", server.port());
        client.connect();

        Message register = server.next();
        assertEquals(MessageType.REGISTER, register.getType());
        assertEquals(SELLER + ";SELLER", register.getPayload());
        Message list = server.next();
        assertEquals(MessageType.LIST_COMPANIES, list.getType());
        assertEquals(SELLER, list.getPayload());
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.close();
        System.setOut(originalOut);
        Locale.setDefault(originalLocale);
    }

    private void awaitOutput(String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (captured.toString().contains(expected)) return;
            Thread.sleep(10);
        }
        fail("Expected output not found: \"" + expected + "\"\nActual output:\n" + captured);
    }

    private void sync() throws Exception {
        String marker = "SYNC" + syncCounter.incrementAndGet();
        server.send(MessageType.PRICE_UPDATE, marker + ";1.0");
        awaitOutput("[PRICE] " + marker + " -> 1.0");
    }

    private String printed(Runnable action) {
        int before = captured.size();
        action.run();
        return captured.toString().substring(before);
    }

    private String ordersView() {
        return printed(client::printOpenOrders);
    }

    private String portfolioView() {
        return printed(client::printPortfolio);
    }

    private String placeSell(String symbol, int qty, double price) throws Exception {
        client.sell(symbol, qty, price);
        Message m = server.next();
        assertEquals(MessageType.SELL_ORDER, m.getType());
        return m.getPayload().split(";")[0];
    }

    @Test
    void sellSendsWellFormedSellOrder() throws Exception {
        client.sell("xyz", 20, 50.0);
        Message m = server.next();

        assertEquals(MessageType.SELL_ORDER, m.getType());
        String[] f = m.getPayload().split(";");
        assertEquals(5, f.length);
        assertFalse(f[0].isBlank());
        assertEquals(SELLER, f[1]);
        assertEquals("XYZ", f[2]);
        assertEquals("20", f[3]);
        assertEquals("50.0", f[4]);
    }

    @Test
    void sellRegistersOrderAsPendingBeforeServerReplies() throws Exception {
        String id = placeSell("ABC", 10, 30.0);
        String view = ordersView();
        assertTrue(view.contains(id));
        assertTrue(view.contains("ABC x10 min 30.00 [PENDING]"), view);
    }

    @Test
    void tradeExecutedUpdatesPortfolioTotalEarnedAndRemovesOrder() throws Exception {
        client.setInitialShares("ABC", 50);
        String id = placeSell("ABC", 10, 30.0);

        server.send(MessageType.ORDER_ACCEPTED, id + ";ABC;10;30.0");
        awaitOutput("[OK] Sell Order " + id + " accepted");

        server.send(MessageType.TRADE_EXECUTED, id + ";ABC;10;30.0;T100");
        awaitOutput("[TRADE T100] Sold 10 ABC @ 30.00");

        String portfolio = portfolioView();
        assertTrue(portfolio.contains("ABC : 40"), portfolio);
        assertTrue(portfolio.contains("Total earned: 300.00"), portfolio);
        assertTrue(ordersView().contains("(none)"));
    }

    @Test
    void duplicateTradeIsAppliedOnlyOnce() throws Exception {
        client.setInitialShares("ABC", 50);
        String id = placeSell("ABC", 10, 30.0);
        server.send(MessageType.TRADE_EXECUTED, id + ";ABC;10;30.0;T1");
        server.send(MessageType.TRADE_EXECUTED, id + ";ABC;10;30.0;T1");
        sync();

        String portfolio = portfolioView();
        assertTrue(portfolio.contains("ABC : 40"), portfolio);
        assertTrue(portfolio.contains("Total earned: 300.00"), portfolio);
    }

    @Test
    void concurrentSellsAreAllDeliveredIntact() throws Exception {
        final int n = 50;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                client.sell("ABC", 1, 10.0);
            });
        }
        start.countDown();

        Set<String> ids = new HashSet<>();
        for (int i = 0; i < n; i++) {
            Message m = server.next(5000);
            assertNotNull(m, "missing message #" + i);
            assertEquals(MessageType.SELL_ORDER, m.getType());
            ids.add(m.getPayload().split(";")[0]);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(n, ids.size());
    }

    private static class FakeServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final BlockingQueue<Message> received = new LinkedBlockingQueue<>();
        private final CountDownLatch ready = new CountDownLatch(1);
        private Socket socket;
        private ObjectOutputStream out;

        FakeServer() throws IOException {
            serverSocket = new ServerSocket(0);
        }

        int port() { return serverSocket.getLocalPort(); }

        void acceptAsync() {
            Thread t = new Thread(() -> {
                try {
                    socket = serverSocket.accept();
                    out = new ObjectOutputStream(socket.getOutputStream());
                    out.flush();
                    ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
                    ready.countDown();
                    while (true) {
                        received.add((Message) in.readObject());
                    }
                } catch (Exception ignored) { }
            }, "fake-server-seller");
            t.setDaemon(true);
            t.start();
        }

        synchronized void send(MessageType type, String payload) throws Exception {
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            out.writeObject(new Message(type, payload));
            out.flush();
        }

        Message next() throws InterruptedException { return next(3000); }

        Message next(long timeoutMs) throws InterruptedException {
            return received.poll(timeoutMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            try { if (socket != null) socket.close(); } catch (IOException ignored) { }
            try { serverSocket.close(); } catch (IOException ignored) { }
        }
    }
}