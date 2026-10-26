package ai.neargo.shop.spi.pay;

/**
 * 内部系统（商城、餐饮）调支付的唯一入口（开发计划 §3.1；TDD-支付服务-I3支付单退款流水）。
 *
 * <p>一笔要收的钱先建一张<b>支付单</b>：调用方单号 + 内部 app 幂等，同号不同内容拒绝。之后的通道尝试、回调、补查、
 * 退款、流水都挂在支付单上 —— 不再以「订单号 + 一串通道尝试」充当一笔钱的身份。
 *
 * <p>内嵌形态由 pay-domain 的 {@code PaymentPortImpl} 直调；独立形态由 shop-app 的远程实现经 pay-svc 内部端点调（S8）。
 * 开关 {@code shop.pay.order.enabled=false} 时两个方法都返回 {@code null}，调用方照旧走原路。
 */
public interface PaymentPort {

    /** 建单（幂等）。返回 {@code null} = 支付单未启用 */
    PaymentHandle createPayment(InternalCreatePaymentCmd cmd);

    /** 按支付单号查；查无此单返回 {@code null}。{@code bizType} 决定以哪个内部调用方的身份查（独立形态按它授权） */
    PaymentHandle queryPayment(String bizType, String payOrderNo);

    /**
     * 关单（S7）：<b>关前必查单</b>。渠道说已付 → 不关、按结果入口入账（返回 SUCCEEDED）；没付 / 没有这笔 / 查不通 →
     * 关支付单与未终态的尝试（之后到账走 LATE_PAYMENT）。调用方应在自己的事务<b>提交之后</b>调：订单关单回滚而支付单已关，
     * 下一次付款会被当成关单后到账。按调用方单号定位（交易域手里是订单号）；没有支付单返回 {@code null}。
     */
    PaymentHandle closePayment(String bizType, String outOrderNo, String reason);

    /**
     * 退款（S6）。<b>必须带商家操作员</b>：内部系统不能以系统身份退款 —— 商城售后、餐饮 POS 的退款按钮背后是商户操作员。
     * 幂等键是调用方退款单号；复核中的退款再调一次即「复核通过后执行」。
     */
    RefundHandle requestRefund(InternalRefundCmd cmd);

    /**
     * @param bizType    {@code MALL_ORDER}（商城，内部 app MALL）/ {@code FOOD_CHECK}（餐饮，内部 app FOOD）
     * @param outOrderNo 调用方单号：与内部 app 联合唯一。商城、餐饮都用核心订单号
     * @param storeId    {@code mch_store.id}：调用方用 {@code MerchantQueryPort#payStoreOf} 解析
     * @param expireAt   支付截止（epoch 毫秒）
     * @param fundsPath  主体的资金路径（DIRECT / AGGREGATED），冻结进支付单，记流水按它分录
     */
    record InternalCreatePaymentCmd(String bizType, String bizNo, String outOrderNo, Long storeId, String storeName,
                                    String entityName, String market, long amountMinor, String currency,
                                    String subject, long expireAt, String fundsPath) {
    }

    record PaymentHandle(Long payOrderId, String payOrderNo, String status) {
    }

    /**
     * @param outRefundNo 调用方退款单号（幂等）
     * @param bizRefundNo 业务售后单号（字符串，取代合成的 {@code AS-{subOrderNo}}，R21）
     * @param operatorNo  商家操作员，必填
     */
    record InternalRefundCmd(String bizType, String payOrderNo, String outRefundNo, String bizRefundNo, long amountMinor,
                             String reason, String operatorNo) {
    }

    /** @param status 退款单状态：PENDING_REVIEW / DISPATCHED / SUCCEEDED / FAILED / REJECTED … */
    record RefundHandle(String payRefundNo, String status, String errorCode) {
    }

    String MALL_ORDER = "MALL_ORDER";
    String FOOD_CHECK = "FOOD_CHECK";
}
