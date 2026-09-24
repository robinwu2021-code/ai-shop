package ai.neargo.svc.client;

/**
 * 一次服务间调用的结局。**五种要分开**，因为它们指向五个不同的处置：
 *
 * <ul>
 *   <li>{@link #NOT_CONFIGURED} —— 改配置。<b>不会自己好</b>，等多久都没用；</li>
 *   <li>{@link #UNREACHABLE} —— 对方多半没起来，等它起来；</li>
 *   <li>{@link #TIMEOUT} —— 对方起着但慢，看对方的负载与日志；</li>
 *   <li>{@link #REMOTE_ERROR} —— 对方应答了一个错误（或一个读不懂的应答），看对方日志；</li>
 *   <li>{@link #OK}。</li>
 * </ul>
 *
 * 混成一种的话，运维会守着一个永远不来的恢复。
 */
public enum CallOutcome {
    OK,
    NOT_CONFIGURED,
    UNREACHABLE,
    TIMEOUT,
    REMOTE_ERROR,
}
