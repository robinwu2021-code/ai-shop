package ai.neargo.shop.beauty.spi;

import ai.neargo.shop.beauty.dto.PublishServiceDefinitionVersionReq;
import ai.neargo.shop.beauty.dto.ServiceDefinitionQuery;
import ai.neargo.shop.beauty.dto.ServiceDefinitionVO;
import ai.neargo.shop.beauty.dto.ServiceDefinitionVersionVO;
import ai.neargo.shop.common.PageData;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import org.springframework.web.service.annotation.PostExchange;

/** Platform-only typed contract; public controllers derive delegated coordinates from authenticated context. */
public interface BeautyDefinitionInternalApi {
    String READ_PATH = "/internal/beauty/definitions/get";
    String PUBLISH_PATH = "/internal/beauty/definitions/publish";
    /** Reads within delegated coordinates; neither time nor query parameters expand the trusted store. */
    @PostExchange(READ_PATH)
    PageData<ServiceDefinitionVO> listDefinitions(@RequestBody Lookup request);
    /** Preserves the original actor and durable key across process boundaries for atomic publication. */
    @PostExchange(PUBLISH_PATH)
    ServiceDefinitionVersionVO publishDefinitionVersion(@RequestBody Publication request);

    record Lookup(String entityNo, String storeNo, String actorNo, @Valid ServiceDefinitionQuery query) {
        public Lookup { BeautyDelegatedScope.require(entityNo, storeNo, actorNo); }
    }
    record Publication(String entityNo, String storeNo, String actorNo, PublishServiceDefinitionVersionReq req,
            String idempotencyKey) {
        public Publication { BeautyDelegatedScope.require(entityNo, storeNo, actorNo); }
    }
}
