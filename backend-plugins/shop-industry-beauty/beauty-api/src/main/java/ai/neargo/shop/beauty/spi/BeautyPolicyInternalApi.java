package ai.neargo.shop.beauty.spi;

import ai.neargo.shop.beauty.dto.PolicyVersionVO;
import ai.neargo.shop.beauty.dto.PublishStorePolicyVersionReq;
import ai.neargo.shop.beauty.dto.StorePolicyQuery;
import ai.neargo.shop.beauty.dto.StorePolicyVO;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Platform-only typed contract; public controllers derive delegated coordinates from authenticated context. */
public interface BeautyPolicyInternalApi {
    String READ_PATH = "/internal/beauty/policies/get";
    String PUBLISH_PATH = "/internal/beauty/policies/publish";
    /** Reads within delegated coordinates; neither time nor query parameters expand the trusted store. */
    @PostExchange(READ_PATH)
    StorePolicyVO getPolicy(@RequestBody Lookup request);
    /** Preserves the original actor and durable key across process boundaries for atomic publication. */
    @PostExchange(PUBLISH_PATH)
    PolicyVersionVO publishPolicyVersion(@RequestBody Publication request);

    record Lookup(String entityNo, String storeNo, String actorNo, StorePolicyQuery query) {
        public Lookup { BeautyDelegatedScope.require(entityNo, storeNo, actorNo); }
    }
    record Publication(String entityNo, String storeNo, String actorNo, PublishStorePolicyVersionReq req,
            String idempotencyKey) {
        public Publication { BeautyDelegatedScope.require(entityNo, storeNo, actorNo); }
    }
}
