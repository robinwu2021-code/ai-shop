package ai.neargo.shop.beauty.client;

import ai.neargo.shop.beauty.dto.PolicyVersionVO;
import ai.neargo.shop.beauty.dto.PublishStorePolicyVersionReq;
import ai.neargo.shop.beauty.dto.StorePolicyQuery;
import ai.neargo.shop.beauty.dto.StorePolicyVO;
import ai.neargo.shop.beauty.service.StorePolicyService;
import ai.neargo.shop.beauty.spi.BeautyPolicyInternalApi;

/** Same Service contract as embedded; only transport concerns live in channel. */
public class RemoteStorePolicyService implements StorePolicyService {
    private final BeautyPolicyInternalApi api;
    public RemoteStorePolicyService(BeautyPolicyInternalApi api) { this.api = api; }

    @Override
    public StorePolicyVO getPolicy(String entityNo, String currentStoreNo, String actorNo, StorePolicyQuery query) {
        return BeautyServiceCalls.call(() -> api.getPolicy(new BeautyPolicyInternalApi.Lookup(
                entityNo, currentStoreNo, actorNo, query)));
    }

    @Override
    public PolicyVersionVO publishPolicyVersion(String entityNo, String currentStoreNo, String actorNo,
            PublishStorePolicyVersionReq req, String idempotencyKey) {
        return BeautyServiceCalls.call(() -> api.publishPolicyVersion(new BeautyPolicyInternalApi.Publication(
                entityNo, currentStoreNo, actorNo, req, idempotencyKey)));
    }
}
