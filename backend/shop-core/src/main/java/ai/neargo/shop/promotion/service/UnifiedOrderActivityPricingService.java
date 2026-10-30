package ai.neargo.shop.promotion.service;

import ai.neargo.shop.promotion.entity.PmtApply;
import ai.neargo.shop.spi.marketing.CampaignPort;
import java.util.List;

/** Published order activity pricing and transactional accounting owner. */
public interface UnifiedOrderActivityPricingService {
    boolean supportsOrdinarySelection(String activityNo);

    List<CampaignPort.AppliedActivity> candidates(String userNo, List<CampaignPort.MerchantAmount> groups);
    boolean commit(String userNo, String orderNo, CampaignPort.AppliedActivity selected);
    boolean release(PmtApply row);
}
