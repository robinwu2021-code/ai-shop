package ai.neargo.shop.spi.pay;

import com.fasterxml.jackson.annotation.JsonIgnore;

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
    PaymentHandle queryPayment(String bizType, String payOrderNo, CallSource source);

    /** 同上，来源取当前上下文（{@link CallSources#current()}）—— 现有调用方不改 */
    default PaymentHandle queryPayment(String bizType, String payOrderNo) {
        return queryPayment(bizType, payOrderNo, CallSources.current());
    }

    /**
     * 关单（S7）：<b>关前必查单</b>。渠道说已付 → 不关、按结果入口入账（返回 SUCCEEDED）；
     * 未付时取得渠道关闭证明后再关闭；结果未知保留关闭意图与当前状态。
     * 调用方应在自己的事务<b>提交之后</b>调：订单关单回滚而支付单已关，
     * 下一次付款会被当成关单后到账。按调用方单号定位（交易域手里是订单号）；没有支付单返回 {@code null}。
     */
    PaymentHandle closePayment(String bizType, String outOrderNo, String reason, CallSource source);

    /** 同上，来源取当前上下文（{@link CallSources#current()}）—— 现有调用方不改 */
    default PaymentHandle closePayment(String bizType, String outOrderNo, String reason) {
        return closePayment(bizType, outOrderNo, reason, CallSources.current());
    }

    /**
     * 业务已经终止后的取消通知，必须在业务事务提交后调用。先持久化取消意图，再查/关渠道，
     * 之后到账属于迟到支付；不同于普通 closePayment 查到已付时照常入账。
     * 旧实现显式拒绝，不能静默降级到丢失取消语义的普通关单。
     */
    default PaymentHandle cancelPayment(String bizType, String outOrderNo, String reason, CallSource source) {
        throw new UnsupportedOperationException("BUSINESS_CANCELLATION_NOT_SUPPORTED");
    }

    /** 业务已终止的取消通知，来源取当前可信调用上下文。 */
    default PaymentHandle cancelPayment(String bizType, String outOrderNo, String reason) {
        return cancelPayment(bizType, outOrderNo, reason, CallSources.current());
    }

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
     * @param source     调用来源（只留痕，ADR-045 G9）；<b>不进 HTTP 请求体</b>，独立形态走 {@link InternalHeaders#CALL_SOURCE} 头
     */
    record InternalCreatePaymentCmd(String bizType, String bizNo, String outOrderNo, Long storeId, String storeName,
                                    String entityName, String market, long amountMinor, String currency,
                                    String subject, long expireAt, String fundsPath, @JsonIgnore CallSource source) {

        /** 现有调用方用的构造器：来源取当前上下文 */
        public InternalCreatePaymentCmd(String bizType, String bizNo, String outOrderNo, Long storeId, String storeName,
                                        String entityName, String market, long amountMinor, String currency,
                                        String subject, long expireAt, String fundsPath) {
            this(bizType, bizNo, outOrderNo, storeId, storeName, entityName, market, amountMinor, currency, subject,
                    expireAt, fundsPath, CallSources.current());
        }
    }

    record PaymentHandle(Long payOrderId, String payOrderNo, String status) {
    }

    /**
     * @param outRefundNo 调用方退款单号（幂等）
     * @param bizRefundNo 业务售后单号（字符串，取代合成的 {@code AS-{subOrderNo}}，R21）
     * @param operatorNo  商家操作员，必填
     * @param source      调用来源（只留痕）；不进 HTTP 请求体，同 {@link InternalCreatePaymentCmd#source()}
     */
    record InternalRefundCmd(String bizType, String payOrderNo, String outRefundNo, String bizRefundNo, long amountMinor,
                             String reason, String operatorNo, @JsonIgnore CallSource source) {

        /** 现有调用方用的构造器：来源取当前上下文 */
        public InternalRefundCmd(String bizType, String payOrderNo, String outRefundNo, String bizRefundNo,
                                 long amountMinor, String reason, String operatorNo) {
            this(bizType, payOrderNo, outRefundNo, bizRefundNo, amountMinor, reason, operatorNo, CallSources.current());
        }
    }

    /** @param status 退款单状态：PENDING_REVIEW / DISPATCHED / SUCCEEDED / FAILED / REJECTED … */
    record RefundHandle(String payRefundNo, String status, String errorCode) {
    }

    String MALL_ORDER = "MALL_ORDER";
    String FOOD_CHECK = "FOOD_CHECK";
}
