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

    /** 按支付单号查；查无此单返回 {@code null} */
    PaymentHandle queryPayment(String payOrderNo);

    /**
     * @param bizType    {@code MALL_ORDER}（商城，内部 app MALL）/ {@code FOOD_CHECK}（餐饮，内部 app FOOD）
     * @param outOrderNo 调用方单号：与内部 app 联合唯一。商城、餐饮都用核心订单号
     * @param storeId    {@code mch_store.id}：调用方用 {@code MerchantQueryPort#payStoreOf} 解析
     * @param expireAt   支付截止（epoch 毫秒）
     */
    record InternalCreatePaymentCmd(String bizType, String bizNo, String outOrderNo, Long storeId, String storeName,
                                    String entityName, String market, long amountMinor, String currency,
                                    String subject, long expireAt) {
    }

    record PaymentHandle(Long payOrderId, String payOrderNo, String status) {
    }

    String MALL_ORDER = "MALL_ORDER";
    String FOOD_CHECK = "FOOD_CHECK";
}
