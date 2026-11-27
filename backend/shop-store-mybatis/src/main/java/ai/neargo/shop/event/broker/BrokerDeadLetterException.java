package ai.neargo.shop.event.broker;

/**
 * 订阅处理器抛它，表示这条消息<b>永久处理不了</b>：中间件不再重投，直接转该订阅的死信（ADR-067）。
 * 别的异常都按“暂时失败”退回重投，超过投递上限才进死信。
 */
public class BrokerDeadLetterException extends RuntimeException {

    public BrokerDeadLetterException(String message) { super(message); }
}
