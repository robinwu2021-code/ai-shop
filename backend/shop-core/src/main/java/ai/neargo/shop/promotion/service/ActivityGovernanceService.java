package ai.neargo.shop.promotion.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.ActivityView;

/** Operations governance retains the authenticated operator data scope. */
public interface ActivityGovernanceService {
    PageData<GovernanceView> list(String tenant, String entity, String status, long page, long size);
    GovernanceView get(String tenant, String activity);
    GovernanceView stop(String tenant, String actorNo, String activity, long revision, String reason, String key);
    record GovernanceView(String entityNo, ActivityView activity) { }
}
