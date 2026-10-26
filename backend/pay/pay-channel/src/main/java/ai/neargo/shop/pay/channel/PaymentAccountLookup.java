package ai.neargo.shop.pay.channel;

import java.util.Optional;

/**
 * 按我方商户单号查这笔交易实际进的子商户号（服务商 / 收付通模式下，查单与查退款都要带它）。
 *
 * <p>定义在通道层、由支付域实现（按 {@code stl_payment.sub_merchant_id}，存量行回退到请求快照）：
 * 网关的查询接口只给单号，而服务商路径必须带 {@code sub_mchid}（F12 · Review R14）。
 * 退款单号给的是原交易的子商户号。查不到返回空 —— 调用方据此按「查询失败」处理，不猜。
 */
public interface PaymentAccountLookup {

    Optional<String> subMerchantOf(String outTradeNo);
}
