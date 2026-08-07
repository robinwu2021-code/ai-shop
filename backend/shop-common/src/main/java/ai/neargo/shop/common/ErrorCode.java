package ai.neargo.shop.common;

/**
 * 错误码（分段见 docs/api/响应格式规范.md §3，与本枚举的同步有守卫测试锁住）。段位不是装饰：C 端 http-client 按段决定
 * 「弹 toast / 跳登录 / 跳申诉页」，改段等于改前端行为。
 *
 * <p>{@code msgKey} 指向 {@code i18n/messages*.properties}，响应文案由 {@link Messages} 按
 * {@code Accept-Language} 渲染 —— 后端不出中文硬编码文案。
 */
public enum ErrorCode {

    // ---- 1xxxx 通用 ----
    BAD_REQUEST(10400, "err.bad_request"),
    UNAUTHORIZED(10401, "err.unauthorized"),
    FORBIDDEN(10403, "err.forbidden"),
    NOT_FOUND(10404, "err.not_found"),
    CONFLICT(10409, "err.conflict"),
    TOO_MANY_REQUESTS(10429, "err.too_many_requests"),
    INTERNAL_ERROR(10500, "err.internal"),

    // ---- 2xxxx 交易 ----
    STOCK_NOT_ENOUGH(20001, "err.trade.stock_not_enough"),
    PRICE_CHANGED(20002, "err.trade.price_changed"),
    OUT_OF_DELIVERY_RANGE(20003, "err.trade.out_of_delivery_range"),
    ORDER_STATE_ILLEGAL(20004, "err.trade.order_state_illegal"),

    // ---- 3xxxx 履约 ----
    ALREADY_VERIFIED(30001, "err.fulfillment.already_verified"),
    NOT_THIS_PICKUP_POINT(30002, "err.fulfillment.not_this_pickup"),
    ORDER_REFUNDED(30003, "err.fulfillment.order_refunded"),

    // ---- 4xxxx 营销 ----
    COUPON_SOLD_OUT(40001, "err.marketing.coupon_sold_out"),
    COUPON_NOT_APPLICABLE(40002, "err.marketing.coupon_not_applicable"),

    // ---- 5xxxx 资金 ----
    SPLIT_RECEIVER_NOT_READY(50001, "err.settle.receiver_not_ready"),
    SPLIT_EXPIRED(50002, "err.settle.split_expired"),

    // ---- 6xxxx 风控 ----
    RISK_BLOCKED(60001, "err.risk.blocked");

    private final int code;
    private final String msgKey;

    ErrorCode(int code, String msgKey) {
        this.code = code;
        this.msgKey = msgKey;
    }

    public int code() {
        return code;
    }

    public String msgKey() {
        return msgKey;
    }
}
