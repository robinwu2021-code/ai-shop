package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.SalesRestrictionReq;
import ai.neargo.shop.food.dto.SalesRestrictionQuery;
import ai.neargo.shop.food.dto.SalesRestrictionVO;
import ai.neargo.shop.food.dto.SalesRestrictionEvaluationVO;
import ai.neargo.shop.food.policy.MenuDefinitionPolicy;
import ai.neargo.shop.food.policy.SalesRestrictionPolicy;
import ai.neargo.shop.food.service.SalesRestrictionService;
import ai.neargo.shop.food.spi.SalesRestrictionInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Objects;
import java.util.List;
import java.util.function.Supplier;

/** Validates remote scope and quota facts before exposing a successful restriction receipt. */
public class RemoteSalesRestrictionService implements SalesRestrictionService {
    private final SalesRestrictionInternalApi api;
    public RemoteSalesRestrictionService(SalesRestrictionInternalApi api) { this.api = api; }
    @Override public SalesRestrictionVO manage(String entity, String store, String actor,
            SalesRestrictionReq req, String key) {
        context(entity, store); MenuDefinitionPolicy.key(actor); SalesRestrictionPolicy.command(req);
        if (key == null || key.isBlank() || key.length() > 128) throw BizException.of(ErrorCode.BAD_REQUEST);
        var v = call(() -> api.manage(new SalesRestrictionInternalApi.Command(entity, store, actor, key, req)));
        if (!SalesRestrictionPolicy.valid(v, store) || !req.skuNo().equals(v.skuNo())
                || !req.reason().equals(v.reason())) invalid();
        if ((req.orderSource() != null || !"CLEAR".equals(req.mode()))
                && !Objects.equals(req.orderSource(), v.orderSource())
                || (req.fulfillment() != null || !"CLEAR".equals(req.mode()))
                        && !Objects.equals(req.fulfillment(), v.fulfillment())) invalid();
        if ("CLEAR".equals(req.mode())) {
            if (!req.restrictionNo().equals(v.restrictionNo()) || !"CLEARED".equals(v.status())
                    || v.version() != req.version() + 1) invalid();
        } else if (!req.mode().equals(v.mode()) || v.version() != 0
                || !Objects.equals(req.businessDate(), v.businessDate()) || !same(req.quota(), v.quota())
                || req.startsAt() != null && !req.startsAt().equals(v.startsAt())
                || (req.businessDate() == null || req.expiresAt() != null)
                        && !Objects.equals(req.expiresAt(), v.expiresAt())
                || req.businessDate() != null && v.expiresAt() == null
                || !List.of("ACTIVE", "EXPIRED").contains(v.status())
                || v.reservedQty().signum() != 0 || v.consumedQty().signum() != 0) invalid();
        return v;
    }
    @Override public PageData<SalesRestrictionVO> list(String entity, String store, SalesRestrictionQuery q) {
        context(entity, store); SalesRestrictionPolicy.query(q);
        int page = q == null || q.page() == null ? 1 : q.page();
        int size = q == null || q.size() == null ? 20 : q.size();
        var v = call(() -> api.list(new SalesRestrictionInternalApi.Lookup(entity, store, q)));
        var seen = new HashSet<String>();
        if (v == null || v.records() == null || v.page() == null || v.size() == null || v.total() == null
                || v.page() != page || v.size() != size || v.total() < 0 || v.records().size() > size
                || v.records().stream().anyMatch(r -> !SalesRestrictionPolicy.valid(r, store)
                        || !seen.add(r.restrictionNo()) || q != null && (q.skuNo() != null
                                && !q.skuNo().equals(r.skuNo())
                                || q.status() != null && !q.status().equals(r.status())))) invalid();
        if (v.records().size() != Math.min(size, Math.max(0L, v.total() - ((long) page - 1) * size))) invalid();
        return PageData.of(v.records(), v.total(), page, size);
    }
    @Override public SalesRestrictionEvaluationVO evaluate(String entity, String store, String sku,
            String source, String fulfillment) {
        context(entity, store); MenuDefinitionPolicy.key(sku); MenuDefinitionPolicy.key(source);
        MenuDefinitionPolicy.key(fulfillment);
        SalesRestrictionPolicy.scope(source, fulfillment);
        var v = call(() -> api.evaluate(
                new SalesRestrictionInternalApi.Evaluation(entity, store, sku, source, fulfillment)));
        if (v == null || v.blocked() == null || v.originalStockKnown() == null || v.originalStockKnown()
                || v.asOf() == null || v.restrictions() == null) invalid();
        boolean blocked = false; BigDecimal remaining = null; var seen = new HashSet<String>();
        for (var r : v.restrictions()) {
            if (!SalesRestrictionPolicy.valid(r, store) || !sku.equals(r.skuNo()) || !"ACTIVE".equals(r.status())
                    || !seen.add(r.restrictionNo()) || r.startsAt().isAfter(v.asOf())
                    || r.expiresAt() != null && !r.expiresAt().isAfter(v.asOf())
                    || r.orderSource() != null && !source.equals(r.orderSource())
                    || r.fulfillment() != null && !fulfillment.equals(r.fulfillment())) invalid();
            if ("SOLD_OUT".equals(r.mode())) blocked = true;
            else {
                var qty = r.quota().subtract(r.reservedQty()).subtract(r.consumedQty());
                remaining = remaining == null ? qty : remaining.min(qty);
                if (qty.signum() == 0) blocked = true;
            }
        }
        if (blocked != v.blocked() || !same(remaining, v.remainingQuota())) invalid();
        return v;
    }
    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }
    private static void context(String entity, String store) {
        MenuDefinitionPolicy.key(entity); MenuDefinitionPolicy.key(store);
    }
    private static void invalid() { throw new IllegalStateException("Invalid restriction receipt"); }
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
