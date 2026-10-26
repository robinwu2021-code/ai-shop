package ai.neargo.shop.spi.pay;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 收款路由唯一解析口（代码清单 §2.4；TDD-支付服务-I4进件与收款商户 S2）。取代 {@code MerchantQueryPort} 上的支付解析方法。
 *
 * <p><b>只按门店绑定解析，没有兜底</b>：门店 × 渠道没有 ACTIVE 绑定 = 这家店这个渠道不能收款。
 * 旧解析一路兜到「主体级默认号」，门店没配也能收、收进主体某个号（R01 / R02）。
 */
public interface PayMerchantQueryPort {

    /** 新收款用：这家门店这个渠道收到哪个收款商户。商户不是 ACTIVE 也算没有 */
    Optional<PayRoute> routeOf(Long storeId, String payChannel);

    /** 这家门店在这个市场所有可收款的路由，按优先级（小者先） */
    List<PayRoute> collectableRoutes(Long storeId, String market);

    /** 退款、查单用：按交易冻结的收款商户 id 解析，不看绑定、不看是否 ACTIVE（关闭的除外） */
    Optional<PayRoute> routeByPayMerchantId(Long payMerchantId, Integer keyVersion);

    /** 收款商户的能力与结算属性 */
    Optional<MerchantCapability> capabilityOf(Long payMerchantId);

    /**
     * 一条收款路由。
     *
     * @param keyVersion 密钥版本（密钥模块就绪前恒为 0）
     */
    record PayRoute(Long storeId, Long payMerchantId, String payMerchantNo, String payChannel, String market,
                    String currency, String accessMode, String fundsPath, String channelMerchantId, String subMerchantId,
                    Long parentPayMerchantId, Integer keyVersion, int priority) {
    }

    /**
     * @param payChannelProductIds 开通（OPEN）的支付产品
     */
    record MerchantCapability(Long payMerchantId, String status, Set<Long> payChannelProductIds, boolean invoiceCapable,
                              String feeBearer, String settleCycle, Boolean splitReversible) {
    }
}
