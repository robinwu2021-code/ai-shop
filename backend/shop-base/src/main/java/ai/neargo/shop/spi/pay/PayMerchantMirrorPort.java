package ai.neargo.shop.spi.pay;

/**
 * 收款商户双写（TDD-支付服务-I4进件与收款商户 S4）：旧表 {@code mch_payment_merchant / mch_store / mch_entity}
 * 改完并提交后，把这个主体在支付域的进件单、收款商户、门店绑定跟上。开关 {@code shop.pay.merchant.dual-write}。
 * <p>
 * 调用方在旧事务<b>提交之后</b>调，并<b>吞掉它的失败</b>：新表写不进去不能让旧路径报错，差异由比对（S5）兜底。
 */
public interface PayMerchantMirrorPort {

    /** @param entityNo 旧表有改动的主体 */
    void legacyChanged(String entityNo);
}
