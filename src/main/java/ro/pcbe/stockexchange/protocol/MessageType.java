package ro.pcbe.stockexchange.protocol;

public enum MessageType {
    DISCONNECT,
    REGISTER,
    LIST_COMPANIES,
    BUY_ORDER,
    CANCEL_ORDER,
    MODIFY_ORDER,
    COMPANY_LIST,
    ORDER_ACCEPTED,
    TRADE_EXECUTED,
    ORDER_MODIFIED,
    ORDER_CANCELLED,
    PRICE_UPDATE,
    ERROR
}
