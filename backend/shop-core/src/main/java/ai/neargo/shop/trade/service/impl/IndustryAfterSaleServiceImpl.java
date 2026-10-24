package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.trade.IndustryAfterSalePort;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.trade.dto.AfterSaleVO;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.order.mapper.AfterSaleMapper;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.service.AfterSaleService;
import ai.neargo.shop.trade.service.IndustryAfterSaleService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Source binding and original buyer guards precede the unchanged core after-sale state machine. */
@Service
public class IndustryAfterSaleServiceImpl implements IndustryAfterSaleService {
    private final IndustryOrderSourceMapper sources;
    private final StoreOrderMapper orders;
    private final PaymentMapper payments;
    private final OrderItemMapper items;
    private final AfterSaleService afterSales;
    private final ObjectMapper json;
    private final AfterSaleMapper cases;

    public IndustryAfterSaleServiceImpl(IndustryOrderSourceMapper sources, StoreOrderMapper orders, PaymentMapper payments,
            OrderItemMapper items, AfterSaleService afterSales, ObjectMapper json, AfterSaleMapper cases) {
        this.sources = sources;
        this.orders = orders;
        this.payments = payments;
        this.items = items;
        this.afterSales = afterSales;
        this.json = json;
        this.cases = cases;
    }

    @Override
    public IndustryAfterSalePort.Result apply(IndustryAfterSalePort.Apply request) {
        if (request == null) throw missing();
        validateIdentity(request.identity(), request.binding());
        String key = key(request.identity(), request.binding(), request.requestKey());
        String hash = PricingSnapshotHash.of(request);
        var prior = known(request.identity(), request.binding(), key, hash);
        if (prior != null) return prior;
        Bound bound = bind(request.identity(), request.binding());
        var selected = selectedLines(bound.source(), request.lines());
        var command = new AfterSaleService.ApplyCommand(request.type(), request.reason(), request.images(),
                null, request.reasonCode(), selected);
        var source = new AfterSaleService.SourceRequest(request.binding().source().sourceSystem(), key, hash);
        return result(bound, afterSales.applyForBuyer(bound.order().getSubOrderNo(),
                request.identity().buyerNo(), command, source));
    }

    @Override
    public IndustryAfterSalePort.Lookup queryApply(IndustryAfterSalePort.QueryApply query) {
        if (query == null) throw missing();
        validateIdentity(query.identity(), query.binding());
        String key = key(query.identity(), query.binding(), query.requestKey());
        var result = known(query.identity(), query.binding(), key, null);
        return new IndustryAfterSalePort.Lookup(result != null, result);
    }

    private static String key(IndustryAfterSalePort.Identity identity, IndustryAfterSalePort.Binding binding,
            String requestKey) {
        if (blank(requestKey) || requestKey.length() > 128
                || requestKey.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        return PricingSnapshotHash.of(List.of(identity.tenantNo(), identity.entityNo(), identity.storeNo(),
                identity.buyerNo(), binding, requestKey));
    }

    /** Exact durable original case recovery never queries a mutable industry owner or selects a latest case. */
    private IndustryAfterSalePort.Result known(IndustryAfterSalePort.Identity identity,
            IndustryAfterSalePort.Binding binding, String key, String expectedHash) {
        OrdAfterSale prior = DataScopeContext.executeWithoutScope(() -> cases.selectOne(
                Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getSourceSystem, binding.source().sourceSystem())
                        .eq(OrdAfterSale::getSourceRequestKey, key)));
        if (prior == null) return null;
        if (!Objects.equals(prior.getSourceSystem(), binding.source().sourceSystem())
                || !Objects.equals(prior.getSourceRequestKey(), key) || blank(prior.getSourceRequestHash())
                || (expectedHash != null && !Objects.equals(prior.getSourceRequestHash(), expectedHash))
                || !Objects.equals(prior.getTenantNo(), identity.tenantNo())
                || !Objects.equals(prior.getEntityNo(), identity.entityNo())
                || !Objects.equals(prior.getUserNo(), identity.buyerNo())
                || !Objects.equals(prior.getOrderNo(), binding.orderNo())
                || !Objects.equals(prior.getSubOrderNo(), binding.subOrderNo()))
            throw BizException.of(ErrorCode.CONFLICT);
        return result(loadBound(null, identity, binding), afterSales.detailForBuyer(prior.getAfterSaleNo(), identity.buyerNo()));
    }

    @Override
    public IndustryAfterSalePort.Result get(IndustryAfterSalePort.Reference reference) {
        Bound bound = bind(reference);
        return result(bound, afterSales.detailForBuyer(reference.afterSaleNo(), reference.identity().buyerNo()));
    }

    @Override
    public IndustryAfterSalePort.Result cancel(IndustryAfterSalePort.Reference reference) {
        Bound bound = bind(reference);
        // Verify case-to-source membership before any mutation, even for another case of the same buyer.
        result(bound, afterSales.detailForBuyer(reference.afterSaleNo(), reference.identity().buyerNo()));
        return result(bound, afterSales.cancelForBuyer(reference.afterSaleNo(), reference.identity().buyerNo()));
    }

    private Bound bind(IndustryAfterSalePort.Reference reference) {
        if (reference == null || blank(reference.afterSaleNo())) throw missing();
        return bind(reference.identity(), reference.binding());
    }

    private Bound bind(IndustryAfterSalePort.Identity identity, IndustryAfterSalePort.Binding binding) {
        validateIdentity(identity, binding);
        var source = binding.source();
        OrdIndustryOrderSource row = DataScopeContext.executeWithoutScope(() -> sources.selectOne(
                Wrappers.<OrdIndustryOrderSource>lambdaQuery()
                        .eq(OrdIndustryOrderSource::getTenantNo, identity.tenantNo())
                        .eq(OrdIndustryOrderSource::getEntityNo, identity.entityNo())
                        .eq(OrdIndustryOrderSource::getStoreNo, identity.storeNo())
                        .eq(OrdIndustryOrderSource::getBuyerNo, identity.buyerNo())
                        .eq(OrdIndustryOrderSource::getActorNo, identity.actorNo())
                        .eq(OrdIndustryOrderSource::getOrderNo, binding.orderNo())
                        .eq(OrdIndustryOrderSource::getSubOrderNo, binding.subOrderNo())
                        .eq(OrdIndustryOrderSource::getSourceSystem, source.sourceSystem())
                        .eq(OrdIndustryOrderSource::getSourceType, source.sourceType())
                        .eq(OrdIndustryOrderSource::getSourceNo, source.sourceNo())
                        .eq(OrdIndustryOrderSource::getSourceVersion, source.sourceVersion())
                        .eq(OrdIndustryOrderSource::getStatus, "BOUND")));
        if (row == null || !Objects.equals(row.getTenantNo(), identity.tenantNo())
                || !Objects.equals(row.getEntityNo(), identity.entityNo())
                || !Objects.equals(row.getStoreNo(), identity.storeNo())
                || !Objects.equals(row.getBuyerNo(), identity.buyerNo())
                || !Objects.equals(row.getActorNo(), identity.actorNo())
                || !Objects.equals(row.getOrderNo(), binding.orderNo())
                || !Objects.equals(row.getSubOrderNo(), binding.subOrderNo())
                || !Objects.equals(row.getSourceSystem(), source.sourceSystem())
                || !Objects.equals(row.getSourceType(), source.sourceType())
                || !Objects.equals(row.getSourceNo(), source.sourceNo())
                || !Objects.equals(row.getSourceVersion(), source.sourceVersion())
                || !"BOUND".equals(row.getStatus())) throw missing();
        return loadBound(row, identity, binding);
    }

    private Bound loadBound(OrdIndustryOrderSource row, IndustryAfterSalePort.Identity identity,
            IndustryAfterSalePort.Binding binding) {
        OrdSubOrder order = DataScopeContext.executeWithoutScope(() -> orders.selectOne(
                Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getSubOrderNo, binding.subOrderNo())
                        .eq(OrdSubOrder::getOrderNo, binding.orderNo())
                        .eq(OrdSubOrder::getTenantNo, identity.tenantNo())
                        .eq(OrdSubOrder::getEntityNo, identity.entityNo())
                        .eq(OrdSubOrder::getStoreNo, identity.storeNo())
                        .eq(OrdSubOrder::getUserNo, identity.buyerNo())));
        if (order == null || !Objects.equals(order.getTenantNo(), identity.tenantNo())
                || !Objects.equals(order.getEntityNo(), identity.entityNo())
                || !Objects.equals(order.getStoreNo(), identity.storeNo())
                || !Objects.equals(order.getUserNo(), identity.buyerNo())
                || !Objects.equals(order.getOrderNo(), binding.orderNo())
                || !Objects.equals(order.getSubOrderNo(), binding.subOrderNo())) throw missing();
        OrdOrder payment = DataScopeContext.executeWithoutScope(() -> payments.selectOne(
                Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getOrderNo, binding.orderNo())
                        .eq(OrdOrder::getTenantNo, identity.tenantNo())
                        .eq(OrdOrder::getUserNo, identity.buyerNo())));
        if (payment == null || !Objects.equals(payment.getOrderNo(), binding.orderNo())
                || !Objects.equals(payment.getTenantNo(), identity.tenantNo())
                || !Objects.equals(payment.getUserNo(), identity.buyerNo()) || blank(payment.getCurrency())) throw missing();
        return new Bound(row, order, payment.getCurrency());
    }

    private static void validateIdentity(IndustryAfterSalePort.Identity identity, IndustryAfterSalePort.Binding binding) {
        if (identity == null || binding == null || binding.source() == null
                || !"MAIN".equals(identity.tenantNo()) || blank(identity.entityNo())
                || blank(identity.storeNo()) || blank(identity.buyerNo())
                || !Objects.equals(identity.actorNo(), identity.buyerNo())
                || blank(binding.orderNo()) || blank(binding.subOrderNo())
                || binding.source().scopeNo() != null) throw missing();
    }

    private List<AfterSaleService.ApplyCommand.ItemQty> selectedLines(OrdIndustryOrderSource source,
            List<IndustryAfterSalePort.LineQty> requested) {
        if (requested == null || requested.isEmpty() || blank(source.getAllocationsJson())) throw invalidAmount();
        var envelope = json.readTree(source.getAllocationsJson());
        if (!envelope.isObject() || !envelope.path("allocations").isArray()) throw invalidAmount();
        List<IndustryOrderPort.Allocation> allocations = json.convertValue(envelope.path("allocations"),
                json.getTypeFactory().constructCollectionType(List.class, IndustryOrderPort.Allocation.class));
        Map<LineKey, IndustryOrderPort.Allocation> original = new HashMap<>();
        for (var allocation : allocations) {
            if (allocation.qty() <= 0 || allocation.orderItemId() <= 0
                    || original.putIfAbsent(new LineKey(allocation.sourceLineNo(), allocation.componentRef()), allocation) != null)
                throw invalidAmount();
        }
        if (original.isEmpty()) throw invalidAmount();
        Map<Long, Integer> selected = new HashMap<>();
        for (var line : requested) {
            var allocation = original.get(new LineKey(line.sourceLineNo(), line.componentRef()));
            if (allocation == null || line.qty() <= 0 || line.qty() > allocation.qty()
                    || selected.putIfAbsent(allocation.orderItemId(), line.qty()) != null) throw invalidAmount();
        }
        var originals = DataScopeContext.executeWithoutScope(() -> items.selectList(
                Wrappers.<OrdItem>lambdaQuery()
                        .eq(OrdItem::getSubOrderNo, source.getSubOrderNo())));
        if (originals.size() != original.size() || originals.stream().anyMatch(item -> {
            var allocation = allocations.stream().filter(a -> a.orderItemId() == item.getId()).findFirst().orElse(null);
            int wanted = selected.getOrDefault(item.getId(), 0);
            return allocation == null || allocation.qty() != item.getQty()
                    || wanted > item.getQty() - (item.getRefundedQty() == null ? 0 : item.getRefundedQty());
        })) throw invalidAmount();
        return requested.stream().map(line -> {
            var allocation = original.get(new LineKey(line.sourceLineNo(), line.componentRef()));
            return new AfterSaleService.ApplyCommand.ItemQty(allocation.orderItemId(), line.qty());
        }).toList();
    }

    private static IndustryAfterSalePort.Result result(Bound bound, AfterSaleVO original) {
        if (original == null || !Objects.equals(original.orderNo(), bound.order().getOrderNo())
                || !Objects.equals(original.subOrderNo(), bound.order().getSubOrderNo())) throw missing();
        var p = original.refundExecution();
        var progress = p == null ? null : new IndustryAfterSalePort.Progress(p.executionNo(), p.status(), p.phase(),
                p.errorCode(), p.resourceStatus(), p.resourceErrorCode(), p.closureStatus(), p.cashStatus(),
                p.benefitsStatus(), p.succeededAt());
        return new IndustryAfterSalePort.Result(original.afterSaleNo(), original.orderNo(), original.subOrderNo(),
                original.status(), original.refundMinor(), bound.currency(), progress, original.returnInventoryStatus());
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static BizException missing() { return BizException.of(ErrorCode.NOT_FOUND); }
    private static BizException invalidAmount() { return BizException.of(ErrorCode.AFTERSALE_AMOUNT_INVALID); }
    private record Bound(OrdIndustryOrderSource source, OrdSubOrder order, String currency) { }
    private record LineKey(String sourceLineNo, String componentRef) { }
}
