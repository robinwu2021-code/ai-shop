package ai.neargo.shop.spi.product;

import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * 服务定义端口的跨部署形态（TDD-美业域迁移新库 §2.8 P4）：美业独立部署时经这里调宿主的 {@link ServiceDefinitionPort}。
 * 凭据由共用的 InternalHttp 带上。「没有定义」用 {@link Found#definition()} 为空表达，不用空响应（空响应是 503）。
 */
public interface ServiceDefinitionInternalApi {
    String PATH = "/internal/core/products/service-definitions";

    @PostExchange(PATH + "/publish")
    ServiceDefinitionPort.Definition publish(@RequestBody ServiceDefinitionPort.Publish request);

    @PostExchange(PATH + "/current")
    Found current(@RequestBody SkuLookup request);

    @PostExchange(PATH + "/by-version")
    Found byVersion(@RequestBody VersionLookup request);

    @PostExchange(PATH + "/list")
    Listing list(@RequestBody StoreLookup request);

    record SkuLookup(String entityNo, String storeNo, String skuNo) { }
    record VersionLookup(String entityNo, String storeNo, String skuNo, int definitionVersion) { }
    record StoreLookup(String entityNo, String storeNo, Instant at) { }
    /** 查询结果；{@code definition} 为空表示没有定义。 */
    record Found(ServiceDefinitionPort.Definition definition) { }
    record Listing(List<ServiceDefinitionPort.Definition> definitions) { }
}
