package ro.pcbe.stockexchange.protocol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ro.pcbe.stockexchange.client.buyer.BuyerClient;
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

/**
 * Teste pentru BuyerClient folosind un server fals (FakeServer) care:
 *  - colectează mesajele trimise de client;
 *  - trimite către client mesajele scriptate de test.
 *
 * Starea clientului (portofoliu, ordine deschise) se verifică prin ce afișează
 * printPortfolio() / printOpenOrders(), captat din System.out.
 *
 * Cerințe: JUnit 5 + clasele Order / OrderSide din proiect (BuyerClient le folosește).
 */
class BuyerClientTest {

    private static final String BUYER = "buyer1";

    private FakeServer server;
    private BuyerClient client;

    private ByteArrayOutputStream captured;
    private PrintStream originalOut;
    private Locale originalLocale;
    private final AtomicInteger syncCounter = new AtomicInteger();

    // ------------------------------------------------------------ setup / teardown

    @BeforeEach
    void setUp() throws Exception {
        originalLocale = Locale.getDefault();
        Locale.setDefault(Locale.US); // String.format: "25.50", nu "25,50"

        originalOut = System.out;
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true));

        server = new FakeServer();
        server.acceptAsync();

        client = new BuyerClient(BUYER, "localhost", server.port());
        client.connect();

        // mesajele trimise automat la connect()
        Message register = server.next();
        assertEquals(MessageType.REGISTER, register.getType());
        assertEquals(BUYER + ";BUYER", register.getPayload());
        Message list = server.next();
        assertEquals(MessageType.LIST_COMPANIES, list.getType());
        assertEquals(BUYER, list.getPayload());
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.close();
        System.setOut(originalOut);
        Locale.setDefault(originalLocale);
    }

    // ------------------------------------------------------------ helpers

    /** Așteaptă până apare text în System.out. */
    private void awaitOutput(String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (captured.toString().contains(expected)) return;
            Thread.sleep(10);
        }
        fail("Expected output not found: \"" + expected + "\"\nActual output:\n" + captured);
    }

    /**
     * Sincronizare: reader-ul procesează mesajele în ordine, deci dacă markerul a fost
     * procesat, toate mesajele trimise înainte de el au fost procesate deja.
     */
    private void sync() throws Exception {
        String marker = "SYNC" + syncCounter.incrementAndGet();
        server.send(MessageType.PRICE_UPDATE, marker + ";1.0");
        awaitOutput("[PRICE] " + marker + " -> 1.0");
    }

    /** Rulează o acțiune și returnează doar ce a fost afișat de ea. */
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

    /** Trimite un BUY prin client și returnează id-ul ordinului generat. */
    private String placeBuy(String symbol, int qty, double price) throws Exception {
        client.buy(symbol, qty, price);
        Message m = server.next();
        assertEquals(MessageType.BUY_ORDER, m.getType());
        return m.getPayload().split(";")[0];
    }

    private void accept(String id, String symbol, int qty, double price) throws Exception {
        server.send(MessageType.ORDER_ACCEPTED, id + ";" + symbol + ";" + qty + ";" + price);
        awaitOutput("[OK] Order " + id + " accepted");
    }

    // ------------------------------------------------------------ constructor

    @Test
    void blankBuyerIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BuyerClient("  ", "localhost", 1));
        assertThrows(IllegalArgumentException.class, () -> new BuyerClient(null, "localhost", 1));
    }

    @Test
    void buyBeforeConnectPrintsNotConnected() {
        BuyerClient offline = new BuyerClient("offline", "localhost", 1);
        String out = printed(() -> offline.buy("ABC", 1, 1.0));
        assertTrue(out.contains("Not connected."));
    }

    // ------------------------------------------------------------ requests -> server

    @Test
    void buySendsWellFormedBuyOrder() throws Exception {
        client.buy("abc", 10, 25.5); // simbolul trebuie normalizat la majuscule
        Message m = server.next();

        assertEquals(MessageType.BUY_ORDER, m.getType());
        String[] f = m.getPayload().split(";");
        assertEquals(5, f.length);
        assertFalse(f[0].isBlank(), "order id");
        assertEquals(BUYER, f[1]);
        assertEquals("ABC", f[2]);
        assertEquals("10", f[3]);
        assertEquals("25.5", f[4]);
    }

    @Test
    void buyRegistersOrderAsPendingBeforeServerReplies() throws Exception {
        String id = placeBuy("ABC", 10, 25.5);
        String view = ordersView();
        assertTrue(view.contains(id));
        assertTrue(view.contains("ABC x10 limit 25.50 [PENDING]"), view);
    }

    @Test
    void invalidBuyIsNotSentAndNotStored() throws Exception {
        String out = printed(() -> client.buy("ABC", 0, 10.0)); // cantitate invalidă
        assertTrue(out.contains("Invalid order"), out);
        assertNull(server.next(300), "nothing should be sent to the server");
        assertTrue(ordersView().contains("(none)"));
    }

    @Test
    void requestCompaniesSendsListCompanies() throws Exception {
        client.requestCompanies();
        Message m = server.next();
        assertEquals(MessageType.LIST_COMPANIES, m.getType());
        assertEquals(BUYER, m.getPayload());
    }

    @Test
    void cancelSendsCancelOrderForKnownOrder() throws Exception {
        String id = placeBuy("ABC", 10, 25.5);
        client.cancel(id);
        Message m = server.next();
        assertEquals(MessageType.CANCEL_ORDER, m.getType());
        assertEquals(BUYER + ";ABC;" + id, m.getPayload());
    }

    @Test
    void cancelOfUnknownOrderPrintsMessageAndSendsNothing() throws Exception {
        String out = printed(() -> client.cancel("nope"));
        assertTrue(out.contains("No open order with id nope"), out);
        assertNull(server.next(300));
    }

    @Test
    void modifySendsModifyOrder() throws Exception {
        String id = placeBuy("ABC", 10, 25.5);
        client.modify(id, 30.0, 5);
        Message m = server.next();
        assertEquals(MessageType.MODIFY_ORDER, m.getType());
        assertEquals(BUYER + ";ABC;" + id + ";30.0;5", m.getPayload());
    }

    @Test
    void modifyWithInvalidValuesIsRejectedLocally() throws Exception {
        String id = placeBuy("ABC", 10, 25.5);

        assertTrue(printed(() -> client.modify(id, -1, 5)).contains("must be positive"));
        assertTrue(printed(() -> client.modify(id, 10, 0)).contains("must be positive"));
        assertTrue(printed(() -> client.modify(id, Double.NaN, 5)).contains("must be positive"));
        assertNull(server.next(300));
    }

    @Test
    void modifyOfUnknownOrderPrintsMessage() {
        String out = printed(() -> client.modify("nope", 10, 5));
        assertTrue(out.contains("No open order with id nope"), out);
    }

    // ------------------------------------------------------------ server -> client

    @Test
    void companyListIsPrintedAndPricesRemembered() throws Exception {
        server.send(MessageType.COMPANY_LIST, "ABC;Alpha Corp;10.5;100|XYZ;Xeta;20.0;50");
        awaitOutput("XYZ (Xeta)");
        String out = captured.toString();
        assertTrue(out.contains("ABC (Alpha Corp)  price 10.5  shares available 100"), out);
        assertTrue(out.contains("XYZ (Xeta)  price 20.0  shares available 50"), out);
    }

    @Test
    void orderAcceptedMovesOrderToOpen() throws Exception {
        String id = placeBuy("ABC", 10, 25.5);
        accept(id, "ABC", 10, 25.5);
        assertTrue(ordersView().contains("ABC x10 limit 25.50 [OPEN]"));
    }

    @Test
    void tradeExecutedUpdatesPortfolioTotalAndRemovesOrder() throws Exception {
        String id = placeBuy("ABC", 10, 25.5);
        accept(id, "ABC", 10, 25.5);

        server.send(MessageType.TRADE_EXECUTED, id + ";ABC;10;25.5;T1");
        awaitOutput("[TRADE T1] Bought 10 ABC @ 25.50");

        String portfolio = portfolioView();
        assertTrue(portfolio.contains("ABC : 10"), portfolio);
        assertTrue(portfolio.contains("Total spent: 255.00"), portfolio);
        assertTrue(ordersView().contains("(none)"));
    }

    @Test
    void duplicateTradeIsAppliedOnlyOnce() throws Exception {
        String id = placeBuy("ABC", 10, 25.5);
        server.send(MessageType.TRADE_EXECUTED, id + ";ABC;10;25.5;T1");
        server.send(MessageType.TRADE_EXECUTED, id + ";ABC;10;25.5;T1"); // același tradeId
        sync();

        String portfolio = portfolioView();
        assertTrue(portfolio.contains("ABC : 10"), portfolio);
        assertFalse(portfolio.contains("ABC : 20"), portfolio);
        assertTrue(portfolio.contains("Total spent: 255.00"), portfolio);
        assertEquals(1, count(captured.toString(), "[TRADE T1]"), "trade printed once");
    }

    @Test
    void differentTradesAccumulateInPortfolio() throws Exception {
        String id1 = placeBuy("ABC", 10, 25.0);
        String id2 = placeBuy("ABC", 5, 20.0);
        server.send(MessageType.TRADE_EXECUTED, id1 + ";ABC;10;25.0;T1");
        server.send(MessageType.TRADE_EXECUTED, id2 + ";ABC;5;20.0;T2");
        sync();

        String portfolio = portfolioView();
        assertTrue(portfolio.contains("ABC : 15"), portfolio);
        assertTrue(portfolio.contains("Total spent: 350.00"), portfolio); // 250 + 100
    }

    @Test
    void tradeBeforeAcceptDoesNotResurrectOrder() throws Exception {
        // un BUY care se execută imediat: TRADE_EXECUTED sosește înaintea lui ORDER_ACCEPTED
        String id = placeBuy("ABC", 10, 25.5);
        server.send(MessageType.TRADE_EXECUTED, id + ";ABC;10;25.5;T1");
        server.send(MessageType.ORDER_ACCEPTED, id + ";ABC;10;25.5");
        sync();

        assertTrue(ordersView().contains("(none)"));
        assertTrue(portfolioView().contains("ABC : 10"));
    }

    @Test
    void orderModifiedUpdatesPriceAndQuantity() throws Exception {
        String id = placeBuy("ABC", 10, 25.5);
        accept(id, "ABC", 10, 25.5);

        server.send(MessageType.ORDER_MODIFIED, id + ";ABC;30.0;5");
        awaitOutput("[OK] Order " + id + " modified");

        assertTrue(ordersView().contains("ABC x5 limit 30.00 [OPEN]"));
    }

    @Test
    void orderModifiedForFilledOrderIsIgnored() throws Exception {
        String id = placeBuy("ABC", 10, 25.5);
        server.send(MessageType.TRADE_EXECUTED, id + ";ABC;10;25.5;T1");
        server.send(MessageType.ORDER_MODIFIED, id + ";ABC;30.0;5");
        sync();

        assertTrue(ordersView().contains("(none)"));
    }

    @Test
    void orderCancelledRemovesOrder() throws Exception {
        String id = placeBuy("ABC", 10, 25.5);
        accept(id, "ABC", 10, 25.5);

        server.send(MessageType.ORDER_CANCELLED, id);
        awaitOutput("[OK] Order " + id + " cancelled");

        assertTrue(ordersView().contains("(none)"));
    }

    @Test
    void errorOnPendingOrderRemovesIt() throws Exception {
        String id = placeBuy("ABC", 10, 25.5);
        server.send(MessageType.ERROR, id + ";Unknown company");
        awaitOutput("[ERROR] " + id + ";Unknown company");

        assertTrue(ordersView().contains("(none)"));
    }

    @Test
    void errorOnOpenOrderKeepsIt() throws Exception {
        String id = placeBuy("ABC", 10, 25.5);
        accept(id, "ABC", 10, 25.5);

        server.send(MessageType.ERROR, id + ";Modify rejected");
        awaitOutput("[ERROR] " + id + ";Modify rejected");

        assertTrue(ordersView().contains("ABC x10 limit 25.50 [OPEN]"));
    }

    @Test
    void priceUpdateIsPrintedAndShownAsMarketPrice() throws Exception {
        String id = placeBuy("ABC", 10, 25.5);
        accept(id, "ABC", 10, 25.5);

        server.send(MessageType.PRICE_UPDATE, "ABC;24.75");
        awaitOutput("[PRICE] ABC -> 24.75");

        assertTrue(ordersView().contains("(market 24.75)"));
    }

    @Test
    void malformedMessageDoesNotKillTheReader() throws Exception {
        server.send(MessageType.TRADE_EXECUTED, "garbage");
        awaitOutput("[!] Malformed TRADE_EXECUTED message: garbage");

        // clientul continuă să proceseze mesaje după unul invalid
        server.send(MessageType.PRICE_UPDATE, "ABC;1.5");
        awaitOutput("[PRICE] ABC -> 1.5");
        assertTrue(portfolioView().contains("(empty)"));
    }

    @Test
    void nonProtocolMessageTypeFromServerIsReportedAsInfo() throws Exception {
        server.send(MessageType.BUY_ORDER, "x");
        awaitOutput("[INFO] BUY_ORDER x");
    }

    // ------------------------------------------------------------ concurrency

    @Test
    void concurrentBuysAreAllDeliveredIntact() throws Exception {
        final int n = 100;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                client.buy("ABC", 1, 1.0);
            });
        }
        start.countDown();

        Set<String> ids = new HashSet<>();
        for (int i = 0; i < n; i++) {
            Message m = server.next(5000);
            assertNotNull(m, "missing message #" + i);
            assertEquals(MessageType.BUY_ORDER, m.getType());
            ids.add(m.getPayload().split(";")[0]);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(n, ids.size(), "all order ids must be unique");
        assertEquals(n, count(ordersView(), "[PENDING]"));
    }

    @Test
    void concurrentTradesFromServerAreAllCounted() throws Exception {
        final int n = 200;
        for (int i = 0; i < n; i++) {
            server.send(MessageType.TRADE_EXECUTED, "o" + i + ";ABC;1;2.0;T" + i);
        }
        sync();

        String portfolio = portfolioView();
        assertTrue(portfolio.contains("ABC : " + n), portfolio);
        assertTrue(portfolio.contains("Total spent: 400.00"), portfolio);
    }

    // ------------------------------------------------------------ shutdown

    @Test
    void closeSendsDisconnectAndIsIdempotent() throws Exception {
        client.close();
        Message m = server.next();
        assertEquals(MessageType.DISCONNECT, m.getType());
        assertEquals(BUYER, m.getPayload());

        assertDoesNotThrow(client::close);

        String out = printed(() -> client.buy("ABC", 1, 1.0));
        assertTrue(out.contains("Not connected."));
    }

    // ------------------------------------------------------------ utils

    private static int count(String text, String part) {
        int c = 0, i = 0;
        while ((i = text.indexOf(part, i)) >= 0) { c++; i += part.length(); }
        return c;
    }

    /** Server minimal: acceptă o conexiune, memorează mesajele primite, trimite ce îi ceri. */
    private static class FakeServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final BlockingQueue<Message> received = new LinkedBlockingQueue<>();
        private final CountDownLatch ready = new CountDownLatch(1);
        private Socket socket;
        private ObjectOutputStream out;

        FakeServer() throws IOException {
            serverSocket = new ServerSocket(0); // port liber ales de OS
        }

        int port() { return serverSocket.getLocalPort(); }

        void acceptAsync() {
            Thread t = new Thread(() -> {
                try {
                    socket = serverSocket.accept();
                    out = new ObjectOutputStream(socket.getOutputStream()); // output first, then input
                    out.flush();
                    ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
                    ready.countDown();
                    while (true) {
                        received.add((Message) in.readObject());
                    }
                } catch (Exception ignored) {
                    // conexiunea s-a închis
                }
            }, "fake-server");
            t.setDaemon(true);
            t.start();
        }

        synchronized void send(MessageType type, String payload) throws Exception {
            assertTrue(ready.await(3, TimeUnit.SECONDS), "client never connected");
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
