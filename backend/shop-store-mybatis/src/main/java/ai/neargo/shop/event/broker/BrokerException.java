package ai.neargo.shop.event.broker;

/** 中间件没有确认接收（不可用、超时、无人接收、被拒）。调用方据此重试或转人工。 */
public class BrokerException extends RuntimeException {

    public BrokerException(String message) { super(message); }

    public BrokerException(String message, Throwable cause) { super(message, cause); }
}
