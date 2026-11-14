package ai.neargo.shop.spi.pay;

/**
 * 收款商户读切（TDD-支付服务-I4进件与收款商户 S6）：旧端口的支付解析此刻该不该由支付新模型回答。
 * 生效 = 配置 {@code shop.pay.merchant.read-from=pay} <b>且</b> 启动时全量比对 C07 放行；任一不满足都按旧解析。
 */
public interface PayMerchantReadSwitch {

    boolean readFromPay();
}
