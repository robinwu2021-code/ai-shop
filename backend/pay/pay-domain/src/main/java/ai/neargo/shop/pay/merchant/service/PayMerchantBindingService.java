package ai.neargo.shop.pay.merchant.service;

import java.util.Optional;

/**
 * 门店 × 渠道 → 收款商户（{@code pay_merchant_binding}）。<b>只按门店绑定</b>：一个收款商户给多家店用就给每家店各建一条。
 * 绑定时校验（表结构说明 §6.2）：收款商户 ACTIVE、渠道一致、市场与门店一致、开户门店与本门店同属一个主体。
 */
public interface PayMerchantBindingService {

    /** 绑定；同一门店 × 渠道已有 ACTIVE 绑定则先转 INACTIVE（同事务）。返回绑定 id */
    Long bind(Long storeId, String payChannel, Long payMerchantId, String operatorNo);

    /** 解绑：这家门店这个渠道从此不能收款 */
    void unbind(Long storeId, String payChannel, String operatorNo);

    /**
     * 按门店号取支付要冻结的门店身份（开放 API 建单、查可用方式用；不论营业状态）。
     * 由本服务转一手，接入层就不必直接依赖核心系统的商户端口（{@code PayReverseDependencyBudgetTest}）。
     */
    Optional<StoreRef> storeByNo(String storeNo);

    /** 同上，按门店 id（静态码只存门店 id） */
    Optional<StoreRef> storeById(Long storeId);

    /** @param market 主体市场，未填按 CN；@param fundsPath 主体的资金路径 */
    record StoreRef(Long storeId, String storeNo, String storeName, String entityName, String market, String fundsPath) {
    }
}
