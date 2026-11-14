package ai.neargo.shop.pay.acquiring.service;

/**
 * 支付异常的处置（TDD-支付服务-W3收单补完 E04；修 R18 的后半）：关单后到账（{@code LATE_PAYMENT}）与重复支付
 * （{@code DUPLICATE_PAYMENT}）的钱是顾客多付的，处置是<b>原路退回那一笔尝试</b>。
 *
 * <p>退款不经支付单的可退余额（支付单已关或已被另一笔付满，余额里没有这笔钱），也不开退款单 —— 这笔钱进来时没记流水，
 * 退回去也不记，账两头都不碰。执行仍走退款执行器（先落库 → 抢发送权 → 不明只查不重发）。
 */
public interface PayExceptionService {

    /** 原路退回（运营手动，或自动处置作业调用）。幂等：同一条异常只发起一次退款 */
    void refund(Long exceptionId, String operatorNo);

    /** 运营判定无需处置（如线下已退），只记结论 */
    void ignore(Long exceptionId, String operatorNo, String note);

    /** 自动处置：开关打开时，把 OPEN 的关单后到账 / 重复支付逐条原路退回。返回本轮发起的条数 */
    int autoRefundOpen(int limit);

    /** 退款执行器报来结果（执行号前缀是本服务发的）：成功结案，失败退回待人工 */
    void refundSettled(String executionNo, boolean succeeded, String reason);

    /** 执行号前缀：退款行与异常靠它对上 */
    String EXECUTION_PREFIX = "PEX";

    /** 写在退款行 {@code after_sale_no} 上的来源标记 */
    String SOURCE_PREFIX = "PAYEXC:";
}
