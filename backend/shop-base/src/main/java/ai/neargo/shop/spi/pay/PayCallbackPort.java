package ai.neargo.shop.spi.pay;

import java.util.Map;

/**
 * 统一渠道回调的支付侧（渠道接入统一封装设计 §12；TDD-支付服务-I2渠道统一封装 S7）。
 *
 * <p>一条回调分两半：<b>先落、验签、核对、回查</b>在支付侧（这里），<b>入账与订单投影</b>仍在调用方 ——
 * 支付成功的权威在支付域，订单状态是它的下游投影，顺序不可交换。{@link #accept} 只在每一步都过了时给出
 * {@link Outcome#ACCEPT}；调用方入账后用 {@link #handled} 回写结果。
 */
public interface PayCallbackPort {

    /** 通知类型（路径里的 {@code notifyType}） */
    enum Kind { PAY, REFUND }

    /** 路由形态：新的子路径、查询参数、旧地址别名 */
    enum RouteStyle { PATH, QUERY, LEGACY }

    enum Outcome {
        /** 验签、核对、回查都过了：调用方去入账 */
        ACCEPT,
        /** 同一报文已经入过账且账还在：直接回成功，不再处理 */
        DUPLICATE,
        /** 被拒：回失败让渠道重推（原因已落库） */
        REJECT
    }

    /**
     * @param pathRef 路径里的收款商户号；旧地址别名为 {@code -}
     */
    record Envelope(String payChannel, Kind kind, String pathRef, RouteStyle routeStyle, String httpMethod,
                    String remoteIp, Map<String, String> headers, String rawBody) {
    }

    /**
     * @param logNo     回调记录号（{@link #handled} 要用）；未知通道不落记录，为空
     * @param outNo     我方单号（收款 = out_trade_no，退款 = out_refund_no）
     * @param confirmed 回查得到的渠道事实 —— 入账只认它，不认回调体
     * @param payload   验签后的业务字段（回写旧报文表用）
     * @param legacyMessageNo 旧报文表 {@code stl_channel_message} 的记录号；停写后为空
     */
    record Verdict(String logNo, Outcome outcome, String reason, Kind kind, String payChannel, String outNo,
                   PayQueryPort.Result confirmed, String ackOk, String ackFail, Map<String, Object> payload,
                   String legacyMessageNo) {

        public String ack() {
            return outcome == Outcome.REJECT ? ackFail : ackOk;
        }
    }

    Verdict accept(Envelope envelope);

    /**
     * 调用方入账之后回写。
     *
     * @param applied false = 入账一侧拒了（流水里没有这个单号…），{@code reason} 说明
     */
    void handled(Verdict verdict, boolean applied, String reason);
}
