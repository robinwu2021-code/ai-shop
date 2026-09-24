package ai.neargo.svc.client;

/**
 * 服务间调用失败。带上 {@link CallOutcome} 与对方的 HTTP 状态码（没有应答时为 0）。
 *
 * <p><b>消息里不带请求体、也不带响应体</b>：内部调用的参数与回执可能带业务标识，
 * 异常消息会进日志、进告警，它不该成为一个额外的数据出口。
 */
public class ServiceCallException extends RuntimeException {

    private final CallOutcome outcome;
    private final String service;
    private final int statusCode;

    public ServiceCallException(CallOutcome outcome, String service, int statusCode, String message) {
        super(message);
        this.outcome = outcome;
        this.service = service;
        this.statusCode = statusCode;
    }

    public CallOutcome outcome() {
        return outcome;
    }

    public String service() {
        return service;
    }

    /** 对方应答的 HTTP 状态码；连不上 / 超时 / 没配地址时为 0 */
    public int statusCode() {
        return statusCode;
    }
}
