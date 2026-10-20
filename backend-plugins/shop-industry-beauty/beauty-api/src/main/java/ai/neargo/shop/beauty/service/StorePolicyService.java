package ai.neargo.shop.beauty.service;

import ai.neargo.shop.beauty.dto.PolicyVersionVO;
import ai.neargo.shop.beauty.dto.PublishStorePolicyVersionReq;

/** Publishes immutable policy versions within a caller-verified merchant/store scope. */
public interface StorePolicyService {
    /** New versions, audit evidence, idempotent result and event intent commit atomically. */
    PolicyVersionVO publishPolicyVersion(String entityNo, String currentStoreNo, String actorNo,
            PublishStorePolicyVersionReq req, String idempotencyKey);
}
