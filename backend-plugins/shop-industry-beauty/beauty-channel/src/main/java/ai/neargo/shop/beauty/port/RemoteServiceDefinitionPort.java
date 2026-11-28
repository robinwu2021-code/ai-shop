package ai.neargo.shop.beauty.port;

import ai.neargo.shop.spi.product.ServiceDefinitionInternalApi;
import ai.neargo.shop.spi.product.ServiceDefinitionPort;
import java.time.Instant;
import java.util.List;

/** 美业独立部署时的服务定义端口：经宿主内部接口调商品域，不引商品实体或 Mapper（TDD-美业域迁移新库 §2.8 P4）。 */
public class RemoteServiceDefinitionPort implements ServiceDefinitionPort {
    private final ServiceDefinitionInternalApi api;

    public RemoteServiceDefinitionPort(ServiceDefinitionInternalApi api) { this.api = api; }

    @Override
    public Definition publish(Publish command) {
        return PlatformCalls.call(() -> api.publish(command));
    }

    @Override
    public Definition current(String entityNo, String storeNo, String skuNo) {
        return PlatformCalls.call(() -> api.current(new ServiceDefinitionInternalApi.SkuLookup(entityNo, storeNo,
                skuNo))).definition();
    }

    @Override
    public Definition byVersion(String entityNo, String storeNo, String skuNo, int definitionVersion) {
        return PlatformCalls.call(() -> api.byVersion(new ServiceDefinitionInternalApi.VersionLookup(entityNo,
                storeNo, skuNo, definitionVersion))).definition();
    }

    @Override
    public List<Definition> list(String entityNo, String storeNo, Instant at) {
        return PlatformCalls.call(() -> api.list(new ServiceDefinitionInternalApi.StoreLookup(entityNo, storeNo,
                at))).definitions();
    }
}
