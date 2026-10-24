package ai.neargo.shop.promotion.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.ActivityView;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.SaveDraft;
import ai.neargo.shop.promotion.service.StoredValueActivityModels.ApprovalView;
import ai.neargo.shop.promotion.service.StoredValueActivityModels.Decision;
import ai.neargo.shop.promotion.service.StoredValueActivityModels.Scope;
import ai.neargo.shop.promotion.service.StoredValueActivityModels.Submission;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Check;

/** Durable activity saga; Pay calls remain outside Core transactions and original keys survive lost replies. */
public interface StoredValueActivityService {
    /** Save a scope-validated activity draft and invalidate older submissions. */
    ActivityView save(Scope scope,SaveDraft command,String key);

    /** Read the resource only after current identity and scope checks. */
    Detail detail(Scope scope,String activity);

    /** Read the activity and verify its definition still matches the authorized scope. */
    ActivityView get(Scope scope,String activity);

    /** List only resources visible under the current authority and original pagination contract. */
    PageData<ActivityView> list(Scope scope,String status,long page,long size);

    /** Validate current authority and constraints before accepting the proposed operation. */
    Check check(Scope scope,String activity,long revision);

    /** Persist or resume the original accepted command instead of creating duplicate work. */
    Submission submit(Scope scope,String activity,long revision,String key);

    /** Resume the original durable saga without replacing financial command identifiers. */
    Submission resume(Scope scope,Submission progress,ActivityView draft);

    /** Stop the original accepted activity release using its durable submission link. */
    Submission stop(Scope scope,String activity,long submittedRevision,String key);

    /** Create a new draft from the authorized source definition without reusing its publication. */
    ActivityView copy(Scope scope,String activity,String key);

    /** Record the authorized decision against its original revision and audit identity. */
    ApprovalView decide(Scope approver,String approvalNo,String principal,Decision command,String key);

    public record Detail(ActivityView activity,Submission submission) { }
}
