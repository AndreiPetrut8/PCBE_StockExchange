package ro.pcbe.stockexchange.client;

import java.io.IOException;

public interface ExchangeClient extends AutoCloseable {
    void connect() throws IOException;
    void requestCompanies();
    void printPortfolio();
    void printOpenOrders();
    void cancel(String orderId);
    void modify(String orderId, double newPrice, int newQuantity);
    @Override
    void close();
}