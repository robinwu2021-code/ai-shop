package ai.neargo.shop.trade.service.impl;

import static ai.neargo.shop.trade.entitlement.EntitlementServiceSnapshotPolicy.hash;
import static ai.neargo.shop.trade.entitlement.EntitlementServiceSnapshotPolicy.sourceHash;
import static ai.neargo.shop.trade.entitlement.EntitlementServiceSnapshotPolicy.orderNo;
import static ai.neargo.shop.trade.entitlement.EntitlementServiceSnapshotPolicy.operationNo;
import static ai.neargo.shop.trade.entitlement.EntitlementServiceSnapshotPolicy.reference;
import static ai.neargo.shop.trade.entitlement.EntitlementServiceSnapshotPolicy.snapshot;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOrderEvents.UseDecided;
import ai.neargo.shop.spi.entitlement.EntitlementOrderEvents.UsePrepared;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort.UseFact;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.HoldView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.PreviewView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.State;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseItem;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort.UseSourceFact;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.storedvalue.StoredValueUseQuotePort.Quote;
import ai.neargo.shop.spi.storedvalue.StoredValueUseQuotePort.Selection;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.member.CustomerIdentityPort.ReferenceQuery;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.product.ProductScopeFactsPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.spi.user.UserScopeFactsPort;
import ai.neargo.shop.trade.dto.ServiceOrderCreate;
import ai.neargo.shop.trade.dto.ServiceOrderIdentifiedCreate;
import java.util.function.Supplier;
import ai.neargo.shop.trade.dto.ServiceOrderSnapshot;
import ai.neargo.shop.trade.dto.ServiceOrderSnapshot.PreviewBinding;
import ai.neargo.shop.trade.dto.ServiceUseSubmit;
import ai.neargo.shop.trade.entitlement.EntitlementServiceSnapshotPolicy.FrozenSource;
import ai.neargo.shop.trade.entitlement.entity.OrdEntAuthorization;
import ai.neargo.shop.trade.entitlement.entity.OrdEntServiceOrder;
import ai.neargo.shop.trade.mapper.EntitlementServiceOrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.EntitlementAuthorizationMapper;
import ai.neargo.shop.trade.service.EntitlementAuthorizationService;
import ai.neargo.shop.trade.service.EntitlementServiceOrderService;
import ai.neargo.shop.trade.service.EntitlementSourceDecisionService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** A performed service and its consent are core facts. Resource preparation and consumption stay in pay. */
@Service
public class EntitlementServiceOrderServiceImpl implements EntitlementServiceOrderService {
    private final EntitlementServiceOrderMapper orders;
    private final EntitlementAuthorizationMapper authorizations;
    private final CustomerIdentityPort customers;
    private final MerchantScopeFactsPort merchants;
    private final MerchantQueryPort merchantDetails;
    private final StoreDirectoryPort stores;
    private final UserScopeFactsPort users;
    private final GoodsQueryPort goods;
    private final ProductScopeFactsPort productScopes;
    private final MarketPort markets;
    private final EntitlementAuthorizationService consent;
    private final EntitlementSourceDecisionService decisions;
    private final OutboxEventBus events;
    private final ObjectMapper json;
    public EntitlementServiceOrderServiceImpl(EntitlementServiceOrderMapper orders,
            EntitlementAuthorizationMapper authorizations, CustomerIdentityPort customers,
            MerchantScopeFactsPort merchants, MerchantQueryPort merchantDetails, StoreDirectoryPort stores,
            UserScopeFactsPort users, GoodsQueryPort goods, ProductScopeFactsPort productScopes, MarketPort markets,
            EntitlementAuthorizationService consent, EntitlementSourceDecisionService decisions, OutboxEventBus events,
            ObjectMapper json) {
        this.orders = orders; this.authorizations = authorizations; this.customers = customers;
        this.merchants = merchants; this.merchantDetails = merchantDetails; this.stores = stores; this.users = users;
        this.goods = goods; this.productScopes = productScopes; this.markets = markets; this.consent = consent;
        this.decisions = decisions; this.events = events; this.json = json;
    }
    @Override public String operationNoForKey(EntitlementContext ctx, String entity, String key) {
        if (key == null || key.isBlank() || key.length() > 128) badRequest();
        return operationNo(orderNo(ctx, entity, key, json));
    }
    @Override @Transactional
    public ServiceOrderSnapshot create(EntitlementContext ctx, String entity, ServiceOrderCreate c, String key) {
        return createInternal(ctx, entity, c.storeNo(), c.items(), key, hash(json.writeValueAsString(c)), false,
                () -> customers.resolveReference(ctx,
                        new ReferenceQuery(entity, c.storeNo(), c.customerRef())).userNo());
    }
    @Override @Transactional
    public ServiceOrderSnapshot createMonetary(EntitlementContext ctx, String entity, ServiceOrderCreate c, String key) {
        return createInternal(ctx, entity, c.storeNo(), c.items(), key,
                hash(json.writeValueAsString(List.of("STORED_VALUE", c))), true,
                () -> customers.resolveReference(ctx, new ReferenceQuery(entity, c.storeNo(), c.customerRef())).userNo());
    }
    @Override @Transactional
    public ServiceOrderSnapshot createIdentified(EntitlementContext ctx, String entity,
                                                 ServiceOrderIdentifiedCreate c, String key) {
        return createInternal(ctx, entity, c.storeNo(), c.items(), key, hash(json.writeValueAsString(c)), false,
                c::userNo);
    }
    private ServiceOrderSnapshot createInternal(EntitlementContext ctx, String entity, String storeNo,
            List<ServiceOrderCreate.Line> items, String key, String payload, boolean monetary, Supplier<String> holder) {
        if (key == null || key.isBlank() || key.length() > 128) badRequest();
        String no = orderNo(ctx, entity, key, json);
        var previous = orders.find(ctx.tenantNo(), no);
        if (previous != null) return replay(previous, ctx, entity, storeNo, payload);
        if (!"MAIN".equals(ctx.tenantNo()) || !merchants.entityActive(ctx.tenantNo(), entity)
                || !merchants.storesOwned(ctx.tenantNo(), entity, Set.of(storeNo))) notFound();
        var store = stores.cards(List.of(storeNo)).get(storeNo);
        if (store == null || !store.active() || !entity.equals(store.entityNo())) notFound();
        String userNo = holder.get();
        if (users.findActive(ctx.tenantNo(), userNo).isEmpty()) notFound();
        var market = markets.find(merchantDetails.marketOf(entity)).filter(MarketPort.MarketBrief::enabled)
                .orElseThrow(() -> BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
        // The existing product price owner explicitly selects CN SKUs. Never label that price as another currency.
        if (monetary && (!"CN".equals(market.market()) || !"CNY".equals(market.currency()))) unavailable();
        var selected = goods.snapshot(items.stream().map(ServiceOrderCreate.Line::skuNo).toList(),
                Map.of(entity, storeNo));
        var lines = new ArrayList<UseItem>(); Map<String, List<String>> categories = new HashMap<>();
        int index = 0;
        for (var wanted : items) {
            var sku = selected.get(wanted.skuNo());
            // categoryType is the product owner's typed PrdGoods.type, not a display/category label.
            if (sku == null || !entity.equals(sku.merchantNo()) || !"SERVICE".equals(sku.categoryType())
                    || !sku.onSale() || !sku.fulfillments().contains(Fulfillments.STORE_VERIFY)
                    || (sku.storeNo() != null && !storeNo.equals(sku.storeNo()))) unavailable();
            String lineNo = "SL" + (++index);
            if (monetary && sku.price() <= 0) conflict();
            long price = monetary ? Math.multiplyExact(sku.price(), wanted.quantity()) : 0;
            lines.add(new UseItem(lineNo, sku.goodsNo(), sku.title(), wanted.quantity(), price));
            categories.put(lineNo, productScopes.categoryLineage(ctx.tenantNo(), sku.categoryNo()));
        }
        if (!productScopes.goodsOwned(ctx.tenantNo(), entity,
                lines.stream().map(UseItem::itemNo).collect(Collectors.toSet()))) notFound();
        Instant at = Instant.ofEpochMilli(System.currentTimeMillis());
        String staff = users.findActive(ctx.tenantNo(), ctx.actorNo()).map(UserScopeFactsPort.ActiveUser::nickname)
                .orElse(null);
        var frozen = new FrozenSource(ctx.tenantNo(), reference(no), userNo, entity, storeNo,
                market.currency(), lines, store.storeName(), staff, categories, at);
        var row = new OrdEntServiceOrder(); row.setTenantNo(ctx.tenantNo()); row.setServiceOrderNo(no);
        row.setOperationNo(operationNo(no)); row.setEntityNo(entity); row.setStoreNo(storeNo);
        row.setUserNo(userNo); row.setOperatorNo(ctx.actorNo()); row.setRequestKeyHash(hash(key));
        row.setPayloadHash(payload); row.setSourceSnapshot(json.writeValueAsString(frozen));
        row.setSourceHash(sourceHash(row.getSourceSnapshot(), json)); row.setContextSnapshot(json.writeValueAsString(ctx));
        row.setDeadline(LocalDateTime.ofInstant(at.plusSeconds(900), ZoneOffset.UTC));
        row.setCreatedAt(LocalDateTime.ofInstant(at, ZoneOffset.UTC)); row.setUpdatedAt(row.getCreatedAt());
        row.setCreatedBy(ctx.actorNo()); row.setUpdatedBy(ctx.actorNo());
        orders.insertIfAbsent(row); row = orders.lock(ctx.tenantNo(), no);
        if (row == null) conflict();
        return replay(row, ctx, entity, storeNo, payload);
    }
    @Override public ServiceOrderSnapshot requireMerchant(EntitlementContext ctx, String entity,
                                                           String store, String no) {
        var row = require(ctx.tenantNo(), no); merchant(row, ctx, entity, store); return snapshot(row, json);
    }
    @Override public ServiceOrderSnapshot requireMerchant(EntitlementContext ctx, String entity, String no) {
        var row = require(ctx.tenantNo(), no); merchant(row, ctx, entity, row.getStoreNo()); return snapshot(row, json);
    }
    @Override public List<ServiceOrderSnapshot> pending(String tenant, String user, int size) {
        if (size < 1 || size > 50) badRequest();
        var rows = orders.selectList(Wrappers.<OrdEntServiceOrder>lambdaQuery()
                .eq(OrdEntServiceOrder::getTenantNo, tenant).eq(OrdEntServiceOrder::getUserNo, user)
                .and(q -> q.in(OrdEntServiceOrder::getStatus, "PENDING", "COMMIT_DECIDED", "CANCEL_DECIDED")
                        .or(x -> x.in(OrdEntServiceOrder::getStatus, "PREVIEWED", "AUTHORIZED")
                                .gt(OrdEntServiceOrder::getPreviewExpiresAt, now())
                                .gt(OrdEntServiceOrder::getDeadline, now())))
                .orderByDesc(OrdEntServiceOrder::getCreatedAt, OrdEntServiceOrder::getServiceOrderNo)
                .last("LIMIT " + size));
        return rows.stream().map(row -> snapshot(row, json)).toList();
    }
    @Override public ServiceOrderSnapshot mine(String tenant, String user, String no) {
        var row = require(tenant, no);
        if (!row.getUserNo().equals(user)) notFound();
        return snapshot(row, json);
    }
    @Override public ServiceOrderSnapshot findOperation(String tenant, String entity, String operation) {
        var row = orders.findOperation(tenant, operation);
        return row == null || !entity.equals(row.getEntityNo()) ? null : snapshot(row, json);
    }
    @Override public UseSourceFact source(EntitlementContext ctx, SourceRef source) {
        var row = require(ctx.tenantNo(), source.sourceNo()); var view = snapshot(row, json);
        if (!source.equals(view.fact().source())) conflict();
        var store = stores.cards(List.of(row.getStoreNo())).get(row.getStoreNo());
        if (users.findActive(ctx.tenantNo(), row.getUserNo()).isEmpty()
                || !merchants.entityActive(ctx.tenantNo(), row.getEntityNo()) || store == null || !store.active()
                || !row.getEntityNo().equals(store.entityNo())) notFound();
        return view.fact();
    }
    @Override @Transactional
    public ServiceOrderSnapshot bindPreview(EntitlementContext ctx, String entity, String store, String no,
                                            AssetKind kind, PreviewView preview, LocalDate day) {
        var row = lock(ctx.tenantNo(), no); merchant(row, ctx, entity, store);
        if (!List.of(AssetKind.PAID_CARD, AssetKind.STORE_EXCHANGE).contains(kind)) unavailable();
        if (!List.of("CREATED", "PREVIEWED").contains(row.getStatus())
                || !now().isBefore(row.getDeadline())) conflict();
        if (!preview.redeemable()) conflict();
        var view = snapshot(row, json);
        if (amount(view.fact()) != 0 || preview.unitsToUse() != units(view.fact())) conflict();
        row.setPreviewSnapshot(json.writeValueAsString(new PreviewBinding(kind, preview, day)));
        row.setPreviewExpiresAt(LocalDateTime.ofInstant(preview.expiresAt(), ZoneOffset.UTC));
        row.setStatus("PREVIEWED"); update(row, ctx.actorNo());
        consent.stageTrustedIntent(ctx.tenantNo(), no, view.fact().source(), preview.previewRef(),
                preview.snapshotHash(), 0, view.fact().currency(), preview.expiresAt());
        return snapshot(row, json);
    }
    @Override @Transactional
    public ServiceOrderSnapshot bindMoneyPreview(EntitlementContext ctx, String entity, String store, String no,
                                                 Quote quote) {
        var row = lock(ctx.tenantNo(), no); merchant(row, ctx, entity, store); var view = snapshot(row, json);
        if (!List.of("CREATED", "PREVIEWED").contains(row.getStatus()) || !now().isBefore(row.getDeadline())
                || !quote.redeemable() || !view.fact().source().equals(quote.sourceRef())
                || !view.fact().currency().equals(quote.currency()) || amount(view.fact()) != quote.amountMinor()
                || !Instant.now().isBefore(quote.expiresAt())) conflict();
        var selection = new Selection(view.fact().source(), view.fact().orderSnapshotHash(), quote.accountNo(),
                quote.amountMinor(), quote.assetRevision());
        if (!hash(json.writeValueAsString(selection)).equals(quote.snapshotHash())) conflict();
        var expiry = quote.expiresAt().isBefore(view.deadline()) ? quote.expiresAt() : view.deadline();
        var bounded = new Quote(quote.quoteRef(), quote.sourceRef(), quote.accountNo(), quote.currency(),
                quote.amountMinor(), quote.applicableMinor(), quote.redeemable(), quote.reason(), quote.assetRevision(),
                quote.snapshotHash(), expiry);
        row.setPreviewSnapshot(json.writeValueAsString(new PreviewBinding(AssetKind.STORED_VALUE, null, null, bounded)));
        row.setPreviewExpiresAt(LocalDateTime.ofInstant(expiry, ZoneOffset.UTC));
        row.setStatus("PREVIEWED"); update(row, ctx.actorNo());
        consent.stageTrustedIntent(ctx.tenantNo(), no, view.fact().source(), bounded.quoteRef(), bounded.snapshotHash(),
                bounded.amountMinor(), bounded.currency(), expiry);
        return snapshot(row, json);
    }
    @Override @Transactional
    public ServiceOrderSnapshot stage(EntitlementContext ctx, String entity, String store, ServiceUseSubmit c) {
        var row = lock(ctx.tenantNo(), c.serviceOrderNo()); merchant(row, ctx, entity, store);
        var view = snapshot(row, json); var binding = view.preview();
        if (binding == null || !binding.ref().equals(c.previewRef())
                || binding.assetRevision() != c.expectedAssetRevision()) conflict();
        if (view.command() != null) {
            if (c.authorizationRef() != null && !c.authorizationRef().equals(view.command().authorizationRef())) {
                conflict();
            }
            return view;
        }
        if (!"AUTHORIZED".equals(row.getStatus())
                || !now().isBefore(row.getDeadline())) conflict();
        var auth = DataScopeContext.executeWithoutScope(() -> authorizations.selectOne(
                Wrappers.<OrdEntAuthorization>lambdaQuery()
                .eq(OrdEntAuthorization::getTenantNo, ctx.tenantNo())
                .eq(OrdEntAuthorization::getOrderNo, row.getServiceOrderNo())
                .eq(OrdEntAuthorization::getQuoteRef, c.previewRef()).last("FOR UPDATE")));
        if (auth == null || !"AUTHORIZED".equals(auth.getStatus()) || auth.getAuthorizationNo() == null
                || (c.authorizationRef() != null && !c.authorizationRef().equals(auth.getAuthorizationNo()))
                || !row.getUserNo().equals(auth.getUserNo()) || !entity.equals(auth.getEntityNo())
                || !store.equals(auth.getStoreNo())
                || !view.fact().orderSnapshotHash().equals(auth.getOrderSnapshotHash())
                || !binding.digest().equals(auth.getSelectionDigest())
                || !view.fact().source().equals(json.readValue(auth.getSourceSnapshot(), SourceRef.class))
                || !view.fact().currency().equals(auth.getCurrency()) || auth.getAmountMinor() != binding.amountMinor()
                || !now().isBefore(auth.getExpiresAt())) conflict();
        boolean monetary = binding.kind() == AssetKind.STORED_VALUE;
        if (monetary != (amount(view.fact()) > 0)) conflict();
        var use = new UseCommand(row.getOperationNo(), view.fact().source(), row.getUserNo(), entity, store,
                view.fact().currency(), binding.kind(), binding.instanceNo(), binding.accountNo(), binding.serviceDay(),
                monetary ? null : units(view.fact()), monetary ? binding.amountMinor() : null,
                view.fact().items(), auth.getAuthorizationNo(), null,
                c.expectedAssetRevision(), monetary ? null : c.previewRef(), null);
        var original = new EntitlementContext(ctx.tenantNo(), ctx.actorNo(), row.getOperationNo(),
                hash(json.writeValueAsString(use)));
        row.setContextSnapshot(json.writeValueAsString(original)); row.setCommandSnapshot(json.writeValueAsString(use));
        row.setStatus("PENDING"); update(row, ctx.actorNo());
        events.publish(new UsePrepared(ctx.tenantNo(), entity, row.getOperationNo()));
        return snapshot(row, json);
    }
    @Override @Transactional
    public ServiceOrderSnapshot accept(EntitlementContext ctx, String entity, UseFact fact) {
        var known = orders.findOperation(ctx.tenantNo(), fact.operation().operationNo());
        if (known == null || !entity.equals(known.getEntityNo())) notFound();
        var row = lock(ctx.tenantNo(), known.getServiceOrderNo()); var view = snapshot(row, json);
        if (view.command() == null || !view.command().equals(fact.command())
                || !view.fact().orderSnapshotHash().equals(fact.orderSnapshotHash())
                || !Set.of(State.HELD, State.COMMITTED).contains(fact.operation().status())) conflict();
        if (view.command().assetKind() == AssetKind.STORED_VALUE) {
            if (fact.rule() != null || !fact.couponAllocations().isEmpty()
                    || view.command().amountMinor() != amount(view.fact())) conflict();
        } else if (fact.rule() == null || !"MERCHANT".equals(fact.rule().funder())
                || !Set.of("SERVICE_UNITS", "GIFT").contains(fact.rule().benefitMode())
                || !view.command().assetKind().name().equals(fact.rule().kind())) conflict();
        if (Set.of("COMMIT_DECIDED", "COMMITTED").contains(row.getStatus())) return view;
        if (!"PENDING".equals(row.getStatus())) conflict();
        decide(row, view, true, null); return snapshot(row, json);
    }
    @Override @Transactional
    public ServiceOrderSnapshot cancel(EntitlementContext ctx, String entity, String store, String no, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 100) badRequest();
        var row = lock(ctx.tenantNo(), no); merchant(row, ctx, entity, store); var view = snapshot(row, json);
        if (Set.of("CANCEL_DECIDED", "RELEASED").contains(row.getStatus())) return view;
        if (!Set.of("CREATED", "PREVIEWED", "AUTHORIZED", "PENDING").contains(row.getStatus())) conflict();
        decide(row, view, false, reason); return snapshot(row, json);
    }
    @Override @Transactional
    public void acknowledge(EntitlementContext ctx, String entity, HoldView result) {
        var known = orders.findOperation(ctx.tenantNo(), result.operationNo());
        if (known == null || !entity.equals(known.getEntityNo())) notFound();
        var row = lock(ctx.tenantNo(), known.getServiceOrderNo());
        String expected = result.status() == State.COMMITTED ? "COMMIT_DECIDED"
                : result.status() == State.RELEASED ? "CANCEL_DECIDED" : null;
        if (expected == null || row.getStatus().equals(result.status().name())) return;
        if (!expected.equals(row.getStatus())) conflict();
        row.setStatus(result.status().name()); update(row, ctx.actorNo());
    }
    private void decide(OrdEntServiceOrder row, ServiceOrderSnapshot view, boolean commit, String reason) {
        var decision = decisions.record(row.getTenantNo(), view.fact().source(), "USE",
                commit ? DecisionRef.DecisionType.USE_COMMIT : DecisionRef.DecisionType.USE_CANCEL,
                view.command() == null ? null : view.command().authorizationRef(), null, row.getUserNo(),
                row.getEntityNo(), row.getStoreNo(), view.fact().currency(), amount(view.fact()), row.getSourceHash(),
                Instant.now());
        row.setDecisionSnapshot(json.writeValueAsString(decision));
        row.setStatus(commit ? "COMMIT_DECIDED" : "CANCEL_DECIDED"); row.setCancellationReason(reason);
        update(row, row.getOperatorNo());
        events.publish(new UseDecided(row.getTenantNo(), row.getEntityNo(), row.getOperationNo()));
    }
    private ServiceOrderSnapshot replay(OrdEntServiceOrder row, EntitlementContext ctx, String entity,
                                         String store, String payload) {
        merchant(row, ctx, entity, store);
        if (!row.getPayloadHash().equals(payload)) conflict();
        return snapshot(row, json);
    }
    private void merchant(OrdEntServiceOrder row, EntitlementContext ctx, String entity, String store) {
        if (!entity.equals(row.getEntityNo()) || !store.equals(row.getStoreNo())
                || !ctx.actorNo().equals(row.getOperatorNo())) notFound();
    }
    private OrdEntServiceOrder require(String tenant, String no) {
        var row = orders.find(tenant, no); if (row == null) notFound(); return row;
    }
    private OrdEntServiceOrder lock(String tenant, String no) {
        var row = orders.lock(tenant, no); if (row == null) notFound(); return row;
    }
    private void update(OrdEntServiceOrder row, String actor) {
        row.setUpdatedBy(actor); row.setUpdatedAt(now()); if (orders.updateById(row) != 1) conflict();
    }
    public static long amount(UseSourceFact fact) {
        long sum = 0; for (var item : fact.items()) sum = Math.addExact(sum, item.amountMinor()); return sum;
    }
    public static int units(UseSourceFact fact) {
        int sum = 0; for (var item : fact.items()) sum = Math.addExact(sum, item.quantity()); return sum;
    }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private static void badRequest() { throw BizException.of(ErrorCode.BAD_REQUEST); }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
    private static void notFound() { throw BizException.of(ErrorCode.NOT_FOUND); }
    private static void unavailable() { throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE); }
}
