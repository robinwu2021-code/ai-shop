package ai.neargo.shop.payclient;

import ai.neargo.shop.merchant.dto.PaymentApplymentVO;
import ai.neargo.shop.pay.client.PayBizInternalApi;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * App 主体页的渠道列表（TDD-门店代码与商户渠道展示 C1–C4）：主体在支付侧有已核验的支付商户时，以支付侧渠道账户为准
 * （渠道商户号全号、沙箱、渠道状态、待开通原因、已启用门店）；没有时回退到本地进件表。
 */
@Component
public class MerchantChannelListing {
    static final String PENDING_ACTIVATION = "PENDING_ACTIVATION";

    private final BizPaymentAppService pay;

    public MerchantChannelListing(BizPaymentAppService pay) {
        this.pay = pay;
    }

    public List<PaymentApplymentVO> list(String entityNo, Supplier<List<PaymentApplymentVO>> local) {
        var remote = pay.entityChannelAccounts(entityNo);
        if (remote == null || remote.merchantNo() == null) return local.get();
        return remote.accounts().stream().map(MerchantChannelListing::row).toList();
    }

    static PaymentApplymentVO row(PayBizInternalApi.EntityChannelAccount a) {
        boolean live = "ENABLED".equals(a.channelStatus()) && "ACTIVE".equals(a.accountStatus());
        String status = live ? "ACTIVE" : "SUSPENDED".equals(a.accountStatus()) ? "FROZEN" : PENDING_ACTIVATION;
        var stores = a.boundStoreNos() == null ? List.<PaymentApplymentVO.EnabledStore>of()
                : a.boundStoreNos().stream().map(PaymentApplymentVO.EnabledStore::new).toList();
        return new PaymentApplymentVO(a.channelCode(), a.channelName(), status, live, a.payMerchantNo(), null, null, null, null,
                List.of(), true, null, null, null, a.channelMerchantId(), a.sandbox(), a.channelStatus(), a.pendingReason(), stores);
    }
}
