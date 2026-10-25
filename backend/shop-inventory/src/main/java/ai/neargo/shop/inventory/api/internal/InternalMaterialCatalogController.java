package ai.neargo.shop.inventory.api.internal;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.spi.inventory.MaterialCatalogInternalApi;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import jakarta.validation.Validator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Authenticates before parsing, without importing Servlet runtime into the independently hosted
 * domain.
 */
@RestController
@ConditionalOnInventory
public class InternalMaterialCatalogController {
    private final MaterialCatalogQueryPort catalog;
    private final String token;
    private final Validator validator;
    private final JsonMapper mapper;

    public InternalMaterialCatalogController(
            MaterialCatalogQueryPort catalog,
            @Value("${shop.services.internal-token:}") String token,
            Validator validator,
            JsonMapper mapper) {
        this.catalog = catalog;
        this.token = token;
        this.validator = validator;
        this.mapper = mapper;
    }

    @PostMapping("/internal/inventory/catalog/sku-materials/batch")
    public ResponseEntity<?> lookupSkus(
            @RequestBody(required = false) byte[] body,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> {
                    final MaterialCatalogInternalApi.SkuLookup c;
                    try {
                        c = mapper.readValue(body, MaterialCatalogInternalApi.SkuLookup.class);
                    } catch (RuntimeException invalid) {
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    }
                    return catalog.getSkuMaterials(c.entityNo(), c.storeNo(), c.skuNos());
                });
    }

    @PostMapping(MaterialCatalogInternalApi.BATCH_PATH)
    public ResponseEntity<?> lookupBatch(
            @RequestBody(required = false) byte[] body,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> {
                    if (body == null || body.length == 0)
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    MaterialCatalogInternalApi.BatchLookup request;
                    try {
                        request =
                                mapper.readValue(
                                        body, MaterialCatalogInternalApi.BatchLookup.class);
                    } catch (JacksonException malformed) {
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    }
                    if (request == null || !validator.validate(request).isEmpty())
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    MaterialCatalogQueryPort.validateLookup(request.entityNo(), request.itemIds());
                    try {
                        return catalog.getMaterials(request.entityNo(), request.itemIds());
                    } catch (BizException failure) {
                        throw failure;
                    } catch (RuntimeException unavailable) {
                        throw BizException.of(ErrorCode.INTERNAL_ERROR);
                    }
                });
    }
}
