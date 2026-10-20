package ro.pcbe.stockexchange.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.pcbe.stockexchange.domain.Company;
import ro.pcbe.stockexchange.market.MatchingEngine;
import ro.pcbe.stockexchange.protocol.Message;
import ro.pcbe.stockexchange.protocol.MessageType;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExchangeServerTest {

    private ExchangeServer server;
    private MatchingEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        engine = new MatchingEngine();
        engine.registerCompany(new Company("Apple", "AAPL", 100, 1000));
        server = new ExchangeServer(0, engine);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private Message next(ObjectInputStream in, MessageType type) throws Exception {
        while (true) {
            Message m = (Message) in.readObject();
            if (m.getType() == type) {
                return m;
            }
        }
    }

    @Test
    void buyAboveMarketPriceIsExecuted() throws Exception {
        try (Socket socket = new Socket("localhost", server.getPort())) {
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
            out.writeObject(new Message(MessageType.REGISTER, "ana;BUYER"));
            out.writeObject(new Message(MessageType.LIST_COMPANIES, "ana"));
            assertEquals("AAPL;Apple;100.0;1000", next(in, MessageType.COMPANY_LIST).getPayload());

            out.writeObject(new Message(MessageType.BUY_ORDER, "o1;ana;AAPL;10;150.0"));
            assertEquals("o1;AAPL;10;150.0", next(in, MessageType.ORDER_ACCEPTED).getPayload());
            assertTrue(next(in, MessageType.TRADE_EXECUTED).getPayload().startsWith("o1;AAPL;10;150.0;"));
            assertEquals(990, engine.getCompany("AAPL").getStockQuantity());
        }
    }

    @Test
    void manyClientsAtTheSameTime() throws Exception {
        int clients = 10;
        int orders = 20;
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        List<Future<Integer>> results = new ArrayList<>();
        for (int c = 0; c < clients; c++) {
            String id = "client" + c;
            results.add(pool.submit(() -> {
                try (Socket socket = new Socket("localhost", server.getPort())) {
                    ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                    ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
                    out.writeObject(new Message(MessageType.REGISTER, id + ";BUYER"));
                    for (int i = 0; i < orders; i++) {
                        out.writeObject(new Message(MessageType.BUY_ORDER, id + "-" + i + ";" + id + ";AAPL;1;150.0"));
                    }
                    out.flush();
                    int filled = 0;
                    while (filled < orders) {
                        next(in, MessageType.TRADE_EXECUTED);
                        filled++;
                    }
                    return filled;
                }
            }));
        }
        int total = 0;
        for (Future<Integer> f : results) {
            total += f.get();
        }
        pool.shutdown();
        assertEquals(clients * orders, total);
        assertEquals(1000 - clients * orders, engine.getCompany("AAPL").getStockQuantity());
    }
}
