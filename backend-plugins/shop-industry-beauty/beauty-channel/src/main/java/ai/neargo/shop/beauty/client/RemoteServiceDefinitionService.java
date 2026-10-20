package ai.neargo.shop.beauty.client;

import ai.neargo.shop.beauty.dto.PublishServiceDefinitionVersionReq;
import ai.neargo.shop.beauty.dto.ServiceDefinitionQuery;
import ai.neargo.shop.beauty.dto.ServiceDefinitionVO;
import ai.neargo.shop.beauty.dto.ServiceDefinitionVersionVO;
import ai.neargo.shop.beauty.service.ServiceDefinitionService;
import ai.neargo.shop.beauty.spi.BeautyDefinitionInternalApi;
import ai.neargo.shop.common.PageData;

/** Same Service contract as embedded; only transport concerns live in channel. */
public class RemoteServiceDefinitionService implements ServiceDefinitionService {
    private final BeautyDefinitionInternalApi api;
    public RemoteServiceDefinitionService(BeautyDefinitionInternalApi api) { this.api = api; }

    @Override
    public PageData<ServiceDefinitionVO> listDefinitions(String entityNo, String currentStoreNo,
            String actorNo, ServiceDefinitionQuery query) {
        return BeautyServiceCalls.call(() -> api.listDefinitions(new BeautyDefinitionInternalApi.Lookup(
                entityNo, currentStoreNo, actorNo, query)));
    }

    @Override
    public ServiceDefinitionVersionVO publishDefinitionVersion(String entityNo, String currentStoreNo, String actorNo,
            PublishServiceDefinitionVersionReq req, String idempotencyKey) {
        return BeautyServiceCalls.call(() -> api.publishDefinitionVersion(new BeautyDefinitionInternalApi.Publication(
                entityNo, currentStoreNo, actorNo, req, idempotencyKey)));
    }
}
