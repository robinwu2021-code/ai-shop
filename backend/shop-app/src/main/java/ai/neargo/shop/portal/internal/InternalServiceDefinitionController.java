package ai.neargo.shop.portal.internal;

import ai.neargo.shop.spi.product.ServiceDefinitionInternalApi;
import ai.neargo.shop.spi.product.ServiceDefinitionPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 服务定义归商品域；每次内部访问先核平台服务凭据，业务错误保留原语义（TDD-美业域迁移新库 §2.8 P4）。 */
@RestController
public class InternalServiceDefinitionController {
    private final ServiceDefinitionPort definitions;
    private final String token;

    public InternalServiceDefinitionController(ServiceDefinitionPort definitions,
            @Value("${shop.services.internal-token:}") String token) {
        this.definitions = definitions;
        this.token = token;
    }

    @PostMapping(ServiceDefinitionInternalApi.PATH + "/publish")
    public ResponseEntity<?> publish(@RequestBody ServiceDefinitionPort.Publish request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> definitions.publish(request));
    }

    @PostMapping(ServiceDefinitionInternalApi.PATH + "/current")
    public ResponseEntity<?> current(@RequestBody ServiceDefinitionInternalApi.SkuLookup request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> new ServiceDefinitionInternalApi.Found(
                definitions.current(request.entityNo(), request.storeNo(), request.skuNo())));
    }

    @PostMapping(ServiceDefinitionInternalApi.PATH + "/by-version")
    public ResponseEntity<?> byVersion(@RequestBody ServiceDefinitionInternalApi.VersionLookup request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> new ServiceDefinitionInternalApi.Found(
                definitions.byVersion(request.entityNo(), request.storeNo(), request.skuNo(),
                        request.definitionVersion())));
    }

    @PostMapping(ServiceDefinitionInternalApi.PATH + "/list")
    public ResponseEntity<?> list(@RequestBody ServiceDefinitionInternalApi.StoreLookup request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> new ServiceDefinitionInternalApi.Listing(
                definitions.list(request.entityNo(), request.storeNo(), request.at())));
    }
}
