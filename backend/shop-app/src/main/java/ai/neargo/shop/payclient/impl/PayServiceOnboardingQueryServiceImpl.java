package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.merchant.service.MerchantGovernService;
import ai.neargo.shop.payclient.PayOpsLinks;
import ai.neargo.shop.payclient.PayServiceOnboardingQueryService;
import ai.neargo.shop.payclient.PaySourceLinkApi;
import ai.neargo.svc.client.ServiceCallException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PayServiceOnboardingQueryServiceImpl implements PayServiceOnboardingQueryService {
    private final MerchantGovernService merchants;
    private final StoreSyncProperties props;
    private final ObjectProvider<PaySourceLinkApi> links;
    private final String opsBase;

    public PayServiceOnboardingQueryServiceImpl(MerchantGovernService merchants, StoreSyncProperties props,
            ObjectProvider<PaySourceLinkApi> links, @Value("${shop.pay.ops-public-base-url:}") String opsBase) {
        this.merchants = merchants;
        this.props = props;
        this.links = links;
        this.opsBase = PayOpsLinks.trustedBase(opsBase);
    }

    @Override
    public PageData<Row> list(String keyword, long page, long size) {
        var api = links.getIfAvailable();
        if (api == null) throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        // The existing owner query applies the current OPS data scope before any remote lookup.
        var visible = merchants.list(null, null, keyword, null, false, page, size);
        try {
            var rows = visible.records().stream().map(entity -> {
                var facts = api.onboardings(props.sourceScope(), entity.merchantNo());
                return new Row(entity.merchantNo(), entity.name(), facts.status(), facts.merchantRef(),
                        facts.linkStatus(), facts.profileStatus(), facts.reviewReason(), facts.onboardings(),
                        PayOpsLinks.onboarding(opsBase, facts.merchantRef()));
            }).toList();
            return PageData.of(rows, visible.total(), visible.page(), visible.size());
        } catch (ServiceCallException failure) {
            // A failed read must not turn into an apparently unsubmitted source application.
            throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        }
    }
}
