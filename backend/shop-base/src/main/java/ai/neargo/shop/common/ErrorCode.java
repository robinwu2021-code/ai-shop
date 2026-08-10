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
    RISK_BLOCKED(60001, "err.risk.blocked"),

    // ---- 7xxxx 商家与通道准入 ----
    /**
     * 该行业不能用这个主体类型进件（微信小微白名单按行业给，线上业态不支持）。
     *
     * <p>单独一个码而不是复用 BAD_REQUEST：端上要据此把「换个主体」这条出路
     * 直接说出来，而通用的「请求参数有误」什么也没告诉商家。
     */
    INDUSTRY_SUBJECT_NOT_ALLOWED(70001, "err.merchant.industry_subject_not_allowed"),

    /**
     * 商家没有经营该类目所需的授权（{@code prd_category.required_code} 不在
     * {@code mch_entity.category_codes} 里）。
     *
     * <p>单独一个码：端上要把**缺哪张资质**说出来并给出申请入口，
     * 通用的「请求参数有误」会让商家反复改商品信息，而问题根本不在商品上。
     */
    CATEGORY_NOT_AUTHORIZED(70002, "err.merchant.category_not_authorized"),

    // ---- 8xxxx 类目维护 ----
    /** 类目最多三级 —— 再深一层 C 端的类目导航就没法展示，也没有第四层的产品定义。 */
    CATEGORY_TOO_DEEP(80001, "err.category.too_deep"),
    /** 下面还挂着商品或未归档的子类目 —— 直接归档会让那些商品挂在一个不存在的类目上。 */
    CATEGORY_IN_USE(80002, "err.category.in_use"),
    /** 父类目已归档，恢复它会造出一个挂在已删父节点下的孤儿。 */
    CATEGORY_PARENT_ARCHIVED(80003, "err.category.parent_archived");

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
