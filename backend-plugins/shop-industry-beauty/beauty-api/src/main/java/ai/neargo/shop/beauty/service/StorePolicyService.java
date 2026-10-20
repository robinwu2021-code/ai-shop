package ai.neargo.shop.beauty.service;

import ai.neargo.shop.beauty.dto.PolicyVersionVO;
import ai.neargo.shop.beauty.dto.StorePolicyQuery;
import ai.neargo.shop.beauty.dto.StorePolicyVO;
import ai.neargo.shop.beauty.dto.PublishStorePolicyVersionReq;

/** Publishes immutable policy versions within a caller-verified merchant/store scope. */
public interface StorePolicyService {
    /** Reads effective configuration only; it does not grant transaction or fulfillment capability. */
    StorePolicyVO getPolicy(String entityNo, String currentStoreNo, String actorNo, StorePolicyQuery query);

    /** New versions, audit evidence, idempotent result and event intent commit atomically. */
    PolicyVersionVO publishPolicyVersion(String entityNo, String currentStoreNo, String actorNo,
            PublishStorePolicyVersionReq req, String idempotencyKey);
}
