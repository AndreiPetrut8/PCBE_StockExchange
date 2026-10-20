package ro.pcbe.stockexchange.server;

import ro.pcbe.stockexchange.domain.Order;
import ro.pcbe.stockexchange.domain.OrderSide;
import ro.pcbe.stockexchange.protocol.Message;
import ro.pcbe.stockexchange.protocol.MessageType;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

public class ClientHandler implements Runnable {

    private final Socket socket;
    private final ExchangeServer server;
    private final ObjectOutputStream out;
    private String clientId;

    public ClientHandler(Socket socket, ExchangeServer server) throws IOException {
        this.socket = socket;
        this.server = server;
        this.out = new ObjectOutputStream(socket.getOutputStream());
        this.out.flush();
    }

    @Override
    public void run() {
        try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
            while (true) {
                Object obj = in.readObject();
                if (!(obj instanceof Message message)) {
                    continue;
                }
                if (message.getType() == MessageType.DISCONNECT) {
                    break;
                }
                handle(message);
            }
        } catch (IOException | ClassNotFoundException ignored) {
        } finally {
            server.unregister(clientId, this);
            close();
            if (clientId != null) {
                System.out.println(clientId + " disconnected");
            }
        }
    }

    private void handle(Message message) {
        String[] p = (message.getPayload() == null ? "" : message.getPayload()).split(";");
        if (message.getType() == MessageType.REGISTER) {
            register(p[0]);
            return;
        }
        if (clientId == null) {
            send(new Message(MessageType.ERROR, ";Register first"));
            return;
        }
        try {
            switch (message.getType()) {
                case LIST_COMPANIES -> send(new Message(MessageType.COMPANY_LIST, server.companyList()));
                case BUY_ORDER -> server.placeOrder(order(p, OrderSide.BUY));
                case SELL_ORDER -> server.placeOrder(order(p, OrderSide.SELL));
                case CANCEL_ORDER -> server.cancelOrder(clientId, p[1].toUpperCase(), p[2]);
                case MODIFY_ORDER -> server.modifyOrder(clientId, p[1].toUpperCase(), p[2],
                        Double.parseDouble(p[3]), Integer.parseInt(p[4]));
                default -> send(new Message(MessageType.ERROR, ";Unknown message " + message.getType()));
            }
        } catch (RuntimeException e) {
            send(new Message(MessageType.ERROR, orderId(message, p) + ";" + e.getMessage()));
        }
    }

    private void register(String id) {
        if (clientId != null || id.isBlank() || !server.register(id, this)) {
            send(new Message(MessageType.ERROR, ";Cannot register " + id));
            return;
        }
        clientId = id;
        System.out.println(id + " connected");
    }

    private Order order(String[] p, OrderSide side) {
        return new Order(p[0], clientId, p[2].toUpperCase(), Integer.parseInt(p[3]), Double.parseDouble(p[4]), side);
    }

    private static String orderId(Message message, String[] p) {
        return switch (message.getType()) {
            case BUY_ORDER, SELL_ORDER -> p[0];
            case CANCEL_ORDER, MODIFY_ORDER -> p.length > 2 ? p[2] : "";
            default -> "";
        };
    }

    public synchronized void send(Message message) {
        try {
            out.writeObject(message);
            out.flush();
            out.reset();
        } catch (IOException e) {
            close();
        }
    }

    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
