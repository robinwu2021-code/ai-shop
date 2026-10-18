package ai.neargo.shop.promotion.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.ActivityView;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.SaveDraft;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.VersionView;

/** Core owns versioned activities; all mutating commands persist an idempotent response. */
public interface ActivityVersionService {
    ActivityView saveDraft(ActivityActor actor, SaveDraft command, String requestKey);
    VersionView publish(ActivityActor actor, String activityNo, long expectedRevision, String requestKey);
    ActivityView changeStatus(ActivityActor actor, String activityNo, long expectedRevision,
                              String status, String reason, String requestKey);
    ActivityView get(ActivityActor actor, String activityNo);
    PageData<ActivityView> list(ActivityActor actor, String scene, String status, long page, long size);
}
