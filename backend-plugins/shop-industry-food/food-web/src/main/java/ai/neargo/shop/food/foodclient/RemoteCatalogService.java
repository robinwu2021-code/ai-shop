package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CatalogQuery;
import ai.neargo.shop.food.dto.CatalogVO;
import ai.neargo.shop.food.policy.CatalogPolicy;
import ai.neargo.shop.food.policy.MenuDefinitionPolicy;
import ai.neargo.shop.food.service.CatalogService;
import ai.neargo.shop.food.spi.CatalogInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** Typed remote directory never manufactures absent prices, stock or authenticated source. */
public class RemoteCatalogService implements CatalogService {
    private final CatalogInternalApi api;
    public RemoteCatalogService(CatalogInternalApi api) { this.api = api; }
    @Override public CatalogVO read(String entity, String store, CatalogQuery query) {
        MenuDefinitionPolicy.key(entity); MenuDefinitionPolicy.key(store); CatalogPolicy.query(query);
        var result = call(() -> api.read(new CatalogInternalApi.Query(entity, store, query)));
        CatalogPolicy.receipt(result, store, query); return result;
    }
    @Override public CatalogVO publicCatalog(String store, String fulfillment) {
        MenuDefinitionPolicy.key(store); CatalogPolicy.publicScope(fulfillment);
        var result = call(() -> api.publicCatalog(new CatalogInternalApi.PublicQuery(store, fulfillment)));
        CatalogPolicy.receipt(result, store, new CatalogQuery("H5", fulfillment, null)); return result;
    }
    @Override public CatalogVO guest(String store, String session, String access, String buyer) {
        MenuDefinitionPolicy.key(store); MenuDefinitionPolicy.key(session);
        if (access == null || access.isBlank() || access.length() > 128) throw BizException.of(ErrorCode.BAD_REQUEST);
        if (buyer != null) MenuDefinitionPolicy.key(buyer);
        var result = call(() -> api.guest(new CatalogInternalApi.GuestQuery(store, session, access, buyer)));
        CatalogPolicy.receipt(result, store, new CatalogQuery("QR", "DINE_IN", null)); return result;
    }
    private <T> T call(Supplier<T> work) {
        try { return ServiceCalls.call(ServiceName.FOOD, work); }
        catch (ServiceCallException failure) {
            var code = switch (failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT;
                default -> null;
            };
            if (code != null) throw BizException.of(code);
            throw failure;
        }
    }
}
