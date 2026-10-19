package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOrderEvents.UseDecided;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort.UseFact;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.HoldView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.State;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import ai.neargo.shop.trade.dto.OrderEntitlementUseView;
import ai.neargo.shop.trade.entitlement.EntitlementOrderSnapshotPolicy;
import ai.neargo.shop.trade.entitlement.entity.OrdEntAuthorization;
import ai.neargo.shop.trade.entitlement.entity.OrdEntOrderUse;
import ai.neargo.shop.trade.entitlement.entity.OrdEntPaymentSeal;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.EntitlementOrderUseMapper;
import ai.neargo.shop.trade.mapper.EntitlementPaymentSealMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.EntitlementAuthorizationMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.service.EntitlementOrderUseService;
import ai.neargo.shop.trade.service.EntitlementSourceDecisionService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Serialize core writes on the order, then its coordination row. Never hold a core lock across a pay call. */
@Service
public class EntitlementOrderUseServiceImpl implements EntitlementOrderUseService {
    private final EntitlementOrderUseMapper uses;
    private final EntitlementPaymentSealMapper seals;
    private final EntitlementAuthorizationMapper authorizations;
    private final OrderMapper orders;
    private final SubOrderMapper subs;
    private final OrderItemMapper items;
    private final EntitlementSourceDecisionService decisions;
    private final OutboxEventBus events;
    private final ObjectMapper json;
    public EntitlementOrderUseServiceImpl(EntitlementOrderUseMapper uses, EntitlementPaymentSealMapper seals,
            EntitlementAuthorizationMapper authorizations, OrderMapper orders, SubOrderMapper subs,
            OrderItemMapper items,
            EntitlementSourceDecisionService decisions, OutboxEventBus events, ObjectMapper json) {
        this.uses = uses; this.seals = seals; this.authorizations = authorizations; this.orders = orders;
        this.subs = subs; this.items = items; this.decisions = decisions; this.events = events; this.json = json;
    }
    @Override public String orderNoForAuthorization(String tenant, String ref) {
        var row = unscoped(() -> authorizations.selectOne(Wrappers.<OrdEntAuthorization>lambdaQuery()
                .eq(OrdEntAuthorization::getTenantNo, tenant).eq(OrdEntAuthorization::getAuthorizationNo, ref)));
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row.getOrderNo();
    }
    @Override public OrderEntitlementUseView find(String tenant, String entity, String operation) {
        var row = unscoped(() -> uses.find(tenant, operation));
        return row == null || !entity.equals(row.getEntityNo()) ? null : view(row);
    }
    @Override @Transactional
    public OrderEntitlementUseView stage(EntitlementContext ctx, UseCommand c) {
        if (c.assetKind() != AssetKind.ORDER_COUPON || !"CORE".equals(c.sourceRef().sourceSystem())
                || !"ORDER_USE".equals(c.sourceRef().sourceType())) unavailable();
        var sub = unscoped(() -> subs.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getTenantNo, ctx.tenantNo())
                .eq(OrdSubOrder::getSubOrderNo, c.sourceRef().sourceNo())));
        if (sub == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var order = lockOrder(ctx.tenantNo(), sub.getOrderNo());
        var previous = byOrder(ctx.tenantNo(), order.getOrderNo(), true);
        if (previous != null) {
            if (!c.operationNo().equals(previous.getOperationNo())
                    || !digest(json.writeValueAsString(c)).equals(previous.getPayloadHash())) conflict();
            return view(previous);
        }
        if (sealed(ctx.tenantNo(), order.getOrderNo()) != null || !OrdOrder.WAIT_PAY.equals(order.getStatus())
                || !OrdSubOrder.WAIT_PAY.equals(sub.getStatus()) || !c.userNo().equals(order.getUserNo())
                || !c.userNo().equals(sub.getUserNo()) || !c.entityNo().equals(sub.getEntityNo())
                || !c.storeNo().equals(sub.getStoreNo()) || !c.currency().equals(order.getCurrency())
                || order.getVersion() != c.sourceRef().sourceRevision()) conflict();
        var auth = unscoped(() -> authorizations.selectOne(Wrappers.<OrdEntAuthorization>lambdaQuery()
                .eq(OrdEntAuthorization::getTenantNo, ctx.tenantNo())
                .eq(OrdEntAuthorization::getAuthorizationNo, c.authorizationRef())));
        String hash = snapshot(order);
        if (auth == null || !"AUTHORIZED".equals(auth.getStatus()) || !order.getOrderNo().equals(auth.getOrderNo())
                || !c.userNo().equals(auth.getUserNo()) || !c.entityNo().equals(auth.getEntityNo())
                || !c.storeNo().equals(auth.getStoreNo()) || !c.quoteRef().equals(auth.getQuoteRef())
                || !c.currency().equals(auth.getCurrency()) || !Objects.equals(c.amountMinor(), auth.getAmountMinor())
                || !json.readValue(auth.getSourceSnapshot(), SourceRef.class).equals(c.sourceRef())
                || !hash.equals(auth.getOrderSnapshotHash()) || !now().isBefore(auth.getExpiresAt())) conflict();
        var row = new OrdEntOrderUse(); audit(row, ctx);
        row.setOperationNo(c.operationNo()); row.setOrderNo(order.getOrderNo()); row.setEntityNo(c.entityNo());
        row.setUserNo(c.userNo()); row.setSourceKey(c.sourceRef().useIntentKey());
        row.setPayloadHash(digest(json.writeValueAsString(c))); row.setCommandSnapshot(json.writeValueAsString(c));
        row.setContextSnapshot(json.writeValueAsString(ctx)); row.setOrderSnapshotHash(hash); row.setStatus("PENDING");
        changed(unscoped(() -> uses.insert(row))); return view(row);
    }
    @Override @Transactional
    public void apply(EntitlementContext ctx, UseFact fact) {
        var c = fact.command();
        if (c == null || !(fact.operation().status() == State.HELD || fact.operation().status() == State.COMMITTED)) {
            conflict();
        }
        var known = require(ctx.tenantNo(), c.entityNo(), c.operationNo());
        var order = lockOrder(ctx.tenantNo(), known.getOrderNo());
        var row = byOrder(ctx.tenantNo(), order.getOrderNo(), true);
        if (!json.readValue(row.getCommandSnapshot(), UseCommand.class).equals(c)
                || !row.getOrderSnapshotHash().equals(fact.orderSnapshotHash())
                || !c.operationNo().equals(fact.operation().operationNo())
                || !"MERCHANT".equals(fact.rule().funder())) {
            conflict();
        }
        if ("APPLIED".equals(row.getStatus())) return;
        if (!"PENDING".equals(row.getStatus()) || !OrdOrder.WAIT_PAY.equals(order.getStatus())
                || sealed(ctx.tenantNo(), order.getOrderNo()) != null
                || !row.getOrderSnapshotHash().equals(snapshot(order))) conflict();
        var target = subOrders(ctx.tenantNo(), order.getOrderNo()).stream()
                .filter(s -> s.getSubOrderNo().equals(c.sourceRef().sourceNo())).findFirst().orElseThrow();
        var lines = orderItems(ctx.tenantNo(), order.getOrderNo()).stream()
                .filter(i -> i.getSubOrderNo().equals(target.getSubOrderNo())).toList();
        long freight = 0; long goods = 0;
        Map<String, Long> discountsByLine = new HashMap<>();
        for (var allocation : fact.couponAllocations()) {
            if (!c.instanceNo().equals(allocation.instanceNo())) conflict();
            if ("__FREIGHT__".equals(allocation.orderLineNo())) {
                freight = Math.addExact(freight, allocation.discountMinor());
            } else {
                var line = lines.stream().filter(i -> ("OL" + i.getId()).equals(allocation.orderLineNo()))
                        .findFirst().orElseThrow(() -> BizException.of(ErrorCode.CONFLICT));
                if (allocation.discountMinor() > line.getAmount()) conflict();
                goods = Math.addExact(goods, allocation.discountMinor());
                discountsByLine.merge(allocation.orderLineNo(), allocation.discountMinor(), Math::addExact);
            }
        }
        long discount = Math.addExact(goods, freight);
        if (discount != c.amountMinor() || freight > target.getFreightAmount()
                || goods > target.getGoodsAmount() - target.getDiscountAmount()
                || discount > target.getPayAmount() || discount > order.getPayAmount()) conflict();
        target.setDiscountAmount(Math.addExact(target.getDiscountAmount(), goods));
        target.setFreightAmount(target.getFreightAmount() - freight);
        target.setPayAmount(target.getPayAmount() - discount);
        order.setDiscountAmount(Math.addExact(order.getDiscountAmount(), goods));
        order.setFreightAmount(order.getFreightAmount() - freight); order.setPayAmount(order.getPayAmount() - discount);
        long lineTotal = 0;
        if (c.itemsSnapshot().size() != lines.size()
                || c.itemsSnapshot().stream().map(i -> i.orderLineNo()).distinct().count() != lines.size()) conflict();
        for (var line : lines) {
            String lineNo = "OL" + line.getId();
            var frozen = c.itemsSnapshot().stream().filter(i -> i.orderLineNo().equals(lineNo))
                    .findFirst().orElseThrow(() -> BizException.of(ErrorCode.CONFLICT));
            if (!line.getGoodsNo().equals(frozen.itemNo()) || line.getQty() != frozen.quantity()) conflict();
            long share = frozen.amountMinor() - discountsByLine.getOrDefault(lineNo, 0L);
            if (share < 0) conflict();
            lineTotal = Math.addExact(lineTotal, share);
            line.setPaidShareMinor(share); changed(unscoped(() -> items.updateById(line)));
        }
        if (lineTotal != target.getGoodsAmount() - target.getDiscountAmount()) conflict();
        changed(unscoped(() -> subs.updateById(target))); changed(unscoped(() -> orders.updateById(order)));
        row.setStatus("APPLIED"); update(row, ctx.actorNo());
    }
    @Override @Transactional
    public OrdOrder sealForPayment(String tenant, String no) {
        var order = lockOrder(tenant, no);
        if (!OrdOrder.WAIT_PAY.equals(order.getStatus())) conflict();
        var use = byOrder(tenant, no, true);
        if (use != null && !"APPLIED".equals(use.getStatus())) conflict();
        var prior = sealed(tenant, no);
        if (prior != null) {
            if (!Objects.equals(prior.getPayAmount(), order.getPayAmount())
                    || !Objects.equals(prior.getCurrency(), order.getCurrency())) conflict();
            return order;
        }
        var seal = new OrdEntPaymentSeal();
        audit(seal, new EntitlementContext(tenant, order.getUserNo(), no, "0".repeat(64)));
        seal.setOrderNo(no); seal.setPayAmount(order.getPayAmount()); seal.setCurrency(order.getCurrency());
        changed(unscoped(() -> seals.insert(seal))); return order;
    }
    @Override @Transactional
    public void decideForTerminalOrder(String tenant, String no) {
        var order = lockOrder(tenant, no);
        var row = byOrder(tenant, no, true);
        if (row == null || !List.of("PENDING", "APPLIED").contains(row.getStatus())) return;
        boolean paid = OrdOrder.PAID.equals(order.getStatus());
        if (!paid && !List.of(OrdOrder.CANCELLED, OrdOrder.CLOSED).contains(order.getStatus())) return;
        if (paid && !"APPLIED".equals(row.getStatus())) conflict();
        var c = json.readValue(row.getCommandSnapshot(), UseCommand.class);
        var type = paid ? DecisionRef.DecisionType.USE_COMMIT : DecisionRef.DecisionType.USE_CANCEL;
        var at = paid && order.getPaidAt() != null ? Instant.ofEpochMilli(order.getPaidAt()) : Instant.now();
        var decision = decisions.record(tenant, c.sourceRef(), "USE", type, c.authorizationRef(), null,
                c.userNo(), c.entityNo(), c.storeNo(), c.currency(), c.amountMinor(), row.getOrderSnapshotHash(), at);
        row.setDecisionSnapshot(json.writeValueAsString(decision));
        row.setStatus(paid ? "COMMIT_DECIDED" : "CANCEL_DECIDED"); update(row, "ORDER");
        events.publish(new UseDecided(tenant, c.entityNo(), c.operationNo()));
    }
    @Override @Transactional
    public void acknowledge(EntitlementContext ctx, String entity, HoldView result) {
        var known = require(ctx.tenantNo(), entity, result.operationNo());
        lockOrder(ctx.tenantNo(), known.getOrderNo());
        var row = byOrder(ctx.tenantNo(), known.getOrderNo(), true);
        String expected = result.status() == State.COMMITTED ? "COMMIT_DECIDED"
                : result.status() == State.RELEASED ? "CANCEL_DECIDED" : null;
        if (expected == null) return;
        if (row.getStatus().equals(result.status().name())) return;
        if (!expected.equals(row.getStatus())) conflict();
        row.setStatus(result.status().name()); update(row, ctx.actorNo());
    }
    private OrdEntOrderUse require(String tenant, String entity, String operation) {
        var row = unscoped(() -> uses.find(tenant, operation));
        if (row == null || !entity.equals(row.getEntityNo())) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }
    private OrdOrder lockOrder(String tenant, String no) {
        var row = unscoped(() -> orders.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                .eq(OrdOrder::getTenantNo, tenant).eq(OrdOrder::getOrderNo, no).last("FOR UPDATE")));
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }
    private OrdEntOrderUse byOrder(String tenant, String no, boolean lock) {
        return unscoped(() -> uses.selectOne(Wrappers.<OrdEntOrderUse>lambdaQuery()
                .eq(OrdEntOrderUse::getTenantNo, tenant).eq(OrdEntOrderUse::getOrderNo, no)
                .last(lock ? "FOR UPDATE" : "")));
    }
    private OrdEntPaymentSeal sealed(String tenant, String no) {
        return unscoped(() -> seals.selectOne(Wrappers.<OrdEntPaymentSeal>lambdaQuery()
                .eq(OrdEntPaymentSeal::getTenantNo, tenant).eq(OrdEntPaymentSeal::getOrderNo, no)));
    }
    private String snapshot(OrdOrder order) {
        return EntitlementOrderSnapshotPolicy.hash(json, order, subOrders(order.getTenantNo(), order.getOrderNo()),
                orderItems(order.getTenantNo(), order.getOrderNo()));
    }
    private List<OrdSubOrder> subOrders(String tenant, String no) {
        return unscoped(() -> subs.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getTenantNo, tenant).eq(OrdSubOrder::getOrderNo, no).orderByAsc(OrdSubOrder::getId)));
    }
    private List<OrdItem> orderItems(String tenant, String no) {
        return unscoped(() -> items.selectList(Wrappers.<OrdItem>lambdaQuery()
                .eq(OrdItem::getTenantNo, tenant).eq(OrdItem::getOrderNo, no).orderByAsc(OrdItem::getId)));
    }
    private OrderEntitlementUseView view(OrdEntOrderUse row) {
        return new OrderEntitlementUseView(row.getOrderNo(), row.getStatus(),
                json.readValue(row.getContextSnapshot(), EntitlementContext.class),
                json.readValue(row.getCommandSnapshot(), UseCommand.class), row.getDecisionSnapshot() == null ? null
                : json.readValue(row.getDecisionSnapshot(), DecisionRef.class));
    }
    private void update(OrdEntOrderUse row, String actor) {
        row.setUpdatedAt(now()); row.setUpdatedBy(actor); changed(unscoped(() -> uses.updateById(row)));
    }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private static void audit(BaseEntity row, EntitlementContext ctx) {
        row.setTenantNo(ctx.tenantNo()); row.setVersion(0L); row.setDeleted(0); row.setCreatedAt(now());
        row.setUpdatedAt(row.getCreatedAt()); row.setCreatedBy(ctx.actorNo()); row.setUpdatedBy(ctx.actorNo());
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 required", e); }
    }
    private static <T> T unscoped(Supplier<T> task) { return DataScopeContext.executeWithoutScope(task); }
    private static void changed(int count) { if (count != 1) conflict(); }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
    private static void unavailable() { throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE); }
}
