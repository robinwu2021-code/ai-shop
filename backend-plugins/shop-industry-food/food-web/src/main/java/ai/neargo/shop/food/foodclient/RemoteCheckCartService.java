package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.AuthorizeCartSessionReq;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.CartSessionGrantVO;
import ai.neargo.shop.food.dto.CartVersionReq;
import ai.neargo.shop.food.dto.CartVersionVO;
import ai.neargo.shop.food.dto.CreateCartReq;
import ai.neargo.shop.food.dto.CreateCustomerCartReq;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.ReplaceCartLineReq;
import ai.neargo.shop.food.policy.CartInputPolicy;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.spi.CartInternalApi;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/** Validates receipts without prereads or retries so a replay keeps its original CAS and idempotency identity. */
public class RemoteCheckCartService implements CheckCartService {
    private final CartInternalApi api;
    public RemoteCheckCartService(CartInternalApi api) { this.api = api; }

    @Override
    public CartResultVO createCart(String entity, String store, String actor, CreateCartReq req, String key) {
        var result = cart(invoke(() -> api.create(new CartInternalApi.CreateCommand(entity, store, actor, key, req))),
                store, null, null);
        if (req.checkNo() != null && !req.checkNo().equals(result.cart().checkNo())) throw malformed();
        if (req.checkNo() == null && req.sessionNo() != null
                && !req.sessionNo().equals(result.cart().sessionNo())) throw malformed();
        if (!req.fulfillment().equals(result.cart().fulfillment())) throw malformed();
        return result;
    }
    @Override
    public CartResultVO getCart(String entity, String store, String cart) {
        return cart(invoke(() -> api.get(new CartInternalApi.Identity(entity, store, cart))), store, cart, null);
    }
    @Override
    public CartVersionVO replaceLine(String entity, String store, String cart, String source, String actor,
            ReplaceCartLineReq req, String key) {
        line(source, req);
        return version(invoke(() -> api.replaceLine(new CartInternalApi.ReplaceLineCommand(
                entity, store, cart, source, actor, key, req))), cart, req.version());
    }
    @Override
    public CartVersionVO archiveLine(String entity, String store, String cart, String source, String actor,
            CartVersionReq req, String key) {
        return version(invoke(() -> api.archiveLine(new CartInternalApi.ArchiveLineCommand(
                entity, store, cart, source, actor, key, req))), cart, req.version());
    }
    @Override
    public OperationResultVO archiveCart(String entity, String store, String cart, String actor,
            CartVersionReq req, String key) {
        var result = invoke(() -> api.archive(new CartInternalApi.ArchiveCommand(entity, store, cart,
                actor, key, req)));
        if (result == null || result.operation() == null || !key(result.operation().operationNo())
                || !"SUCCEEDED".equals(result.operation().status()) || !cart.equals(result.operation().resultRef())
                || !next(req.version(), result.operation().version())) throw malformed();
        return result;
    }
    @Override
    public CartSessionGrantVO authorizeSession(String entity, String store, String check, String actor,
            AuthorizeCartSessionReq req, String key) {
        var result = invoke(() -> api.authorize(new CartInternalApi.AuthorizeCommand(
                entity, store, check, actor, key, req)));
        if (result == null || !check.equals(result.checkNo()) || !req.sessionNo().equals(result.sessionNo())
                || !next(req.version(), result.version())) throw malformed();
        return result;
    }
    @Override
    public CartResultVO customerCreateCart(String store, CartAccess access, CreateCustomerCartReq req, String key) {
        var result = cart(invoke(() -> api.customerCreate(new CartInternalApi.CustomerCreateCommand(
                store, access, req, key))), store, null, access);
        if (req.checkNo() != null && !req.checkNo().equals(result.cart().checkNo())) throw malformed();
        if (req.checkNo() == null && req.sessionNo() != null
                && !req.sessionNo().equals(result.cart().sessionNo())) throw malformed();
        if (!req.fulfillment().equals(result.cart().fulfillment())) throw malformed();
        return result;
    }
    @Override
    public CartResultVO customerGetCart(String cart, CartAccess access) {
        return cart(invoke(() -> api.customerGet(new CartInternalApi.CustomerIdentity(cart, access))),
                null, cart, access);
    }
    @Override
    public CartVersionVO customerReplaceLine(String cart, String source, CartAccess access,
            ReplaceCartLineReq req, String key) {
        line(source, req);
        return version(invoke(() -> api.customerReplaceLine(new CartInternalApi.CustomerReplaceLineCommand(
                cart, source, access, req, key))), cart, req.version());
    }
    @Override
    public CartVersionVO customerArchiveLine(String cart, String source, CartAccess access,
            CartVersionReq req, String key) {
        return version(invoke(() -> api.customerArchiveLine(new CartInternalApi.CustomerArchiveLineCommand(
                cart, source, access, req, key))), cart, req.version());
    }
    private static void line(String source, ReplaceCartLineReq req) {
        if (req == null || req.line() == null || !source.equals(req.line().sourceLineRef())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
    private static CartResultVO cart(CartResultVO result, String store, String number, CartAccess access) {
        if (result == null || result.cart() == null) throw malformed();
        var v = result.cart();
        if (!key(v.cartNo()) || !key(v.storeNo()) || v.sessionNo() != null && !key(v.sessionNo())
                || v.sessionNo() == null && v.checkNo() == null
                || v.checkNo() != null && !key(v.checkNo()) || v.buyerNo() != null && !key(v.buyerNo())
                || v.version() == null || v.version() < 0 || v.lines() == null || v.lines().size() > 300
                || v.status() == null || !Set.of("OPEN", "SUBMITTING", "CLOSED").contains(v.status())
                || v.orderSource() == null || !Set.of("POS", "WAITER", "QR", "H5").contains(v.orderSource())
                || v.fulfillment() == null || !Set.of("DINE_IN", "PICKUP", "DELIVERY").contains(v.fulfillment())
                || store != null && !store.equals(v.storeNo()) || number != null && !number.equals(v.cartNo())
                || access != null && v.buyerNo() != null && !v.buyerNo().equals(access.buyerNo())) throw malformed();
        var seen = new HashSet<String>();
        for (var item : v.lines()) {
            try { CartInputPolicy.line(item); }
            catch (BizException invalid) { throw malformed(); }
            if (!seen.add(item.sourceLineRef())) throw malformed();
        }
        return result;
    }
    private static CartVersionVO version(CartVersionVO result, String cart, Long expected) {
        if (result == null || !cart.equals(result.cartNo()) || !next(expected, result.version())) throw malformed();
        return result;
    }
    private static boolean next(Long expected, Long actual) {
        return expected != null && expected >= 0 && expected < Long.MAX_VALUE
                && actual != null && actual == expected + 1;
    }
    private static boolean key(String value) { return MaterialCatalogFacts.validKey(value, 32); }
    private static IllegalStateException malformed() { return new IllegalStateException("Invalid cart receipt"); }
    private <T> T invoke(Supplier<T> call) {
        try { return ServiceCalls.call(ServiceName.FOOD, call); }
        catch (ServiceCallException failure) {
            ErrorCode code = switch (failure.statusCode()) {
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
