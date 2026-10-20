
package ro.pcbe.stockexchange.protocol;

import java.io.Serializable;
import java.util.Objects;

public class Message implements Serializable {

    private static final long serialVersionUID = 1L;

    private final MessageType type;
    private final String payload;

    public Message(MessageType type, String payload) {
        this.type = Objects.requireNonNull(type, "Message type must not be null");
        this.payload = payload;
    }

    public MessageType getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }
}
