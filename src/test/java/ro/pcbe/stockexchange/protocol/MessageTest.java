package ro.pcbe.stockexchange.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class MessageTest {

    @Test
    void gettersReturnConstructorValues() {
        Message m = new Message(MessageType.BUY_ORDER, "a;b;c");
        assertEquals(MessageType.BUY_ORDER, m.getType());
        assertEquals("a;b;c", m.getPayload());
    }

    @Test
    void nullTypeIsRejected() {
        NullPointerException e = assertThrows(NullPointerException.class, () -> new Message(null, "x"));
        assertEquals("Message type must not be null", e.getMessage());
    }

    @Test
    void nullPayloadIsAllowed() {
        Message m = new Message(MessageType.DISCONNECT, null);
        assertNull(m.getPayload());
    }

    @Test
    void messageSurvivesSerializationRoundTrip() throws Exception {
        Message original = new Message(MessageType.TRADE_EXECUTED, "o1;ABC;10;25.5;T1");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bytes)) {
            oos.writeObject(original);
        }
        Message copy;
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            copy = (Message) ois.readObject();
        }

        assertNotSame(original, copy);
        assertEquals(original.getType(), copy.getType());
        assertEquals(original.getPayload(), copy.getPayload());
    }

    @Test
    void messageTypeContainsAllProtocolConstants() {
        String[] expected = {
                "DISCONNECT", "REGISTER", "LIST_COMPANIES", "BUY_ORDER", "CANCEL_ORDER", "MODIFY_ORDER",
                "COMPANY_LIST", "ORDER_ACCEPTED", "TRADE_EXECUTED", "ORDER_MODIFIED", "ORDER_CANCELLED",
                "PRICE_UPDATE", "ERROR"
        };
        assertEquals(expected.length, MessageType.values().length);
        for (String name : expected) {
            assertNotNull(MessageType.valueOf(name));
        }
    }
}
