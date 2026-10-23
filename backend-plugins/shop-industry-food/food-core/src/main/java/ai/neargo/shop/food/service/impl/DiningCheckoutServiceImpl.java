package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CreateDiningSettlementReq;
import ai.neargo.shop.food.dto.PayDiningSettlementReq;
import ai.neargo.shop.food.dto.DiningSettlementVersionReq;
import ai.neargo.shop.food.dto.DiningSettlementVO;
import ai.neargo.shop.food.entity.FnbChargeIntent;
import ai.neargo.shop.food.entity.FnbCoreOrderBinding;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbSettlement;
import ai.neargo.shop.food.entity.FnbSettlementItem;
import ai.neargo.shop.food.mapper.FoodMappers.ChargeIntentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SettlementMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SettlementItemMapper;
import ai.neargo.shop.food.policy.CartInputPolicy;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.DiningCheckoutService;
import ai.neargo.shop.food.service.SalesQuotaClaimService;
import ai.neargo.shop.spi.food.DiningOrderAccepted;
import ai.neargo.shop.spi.trade.IndustryCheckoutPort;
import ai.neargo.shop.spi.trade.IndustryCheckoutPort.PayCommand;
import ai.neargo.shop.spi.trade.IndustryCheckoutPort.View;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Identity;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import ai.neargo.shop.spi.trade.IndustryCheckoutPort.PaymentVO;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** One full original root, one original payment attempt, then separately proved kitchen acceptance. */
public class DiningCheckoutServiceImpl implements DiningCheckoutService {
    private final CheckCartService carts;
    private final OrderBatchMapper batches;
    private final CoreOrderBindingMapper bindings;
    private final SettlementMapper settlements;
    private final SettlementItemMapper items;
    private final ChargeIntentMapper intents;
    private final SalesQuotaClaimService quotas;
    private final Supplier<IndustryCheckoutPort> core;
    private final OutboxPublisher publisher;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate write;
    public DiningCheckoutServiceImpl(CheckCartService carts, OrderBatchMapper batches, CoreOrderBindingMapper bindings,
            SettlementMapper settlements, SettlementItemMapper items, ChargeIntentMapper intents,
            SalesQuotaClaimService quotas, Supplier<IndustryCheckoutPort> core, OutboxPublisher publisher,
            ObjectMapper json, Clock clock, PlatformTransactionManager manager) {
        this.carts = carts; this.batches = batches; this.bindings = bindings; this.settlements = settlements;
        this.items = items; this.intents = intents; this.quotas = quotas; this.core = core; this.publisher = publisher;
        this.json = json; this.clock = clock; this.write = new TransactionTemplate(manager);
        this.write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    private record Frozen(int schema, String batchNo, String buyerNo, String root, String sub,
            String quote, long sourceVersion, String sourceHash, String economicsHash, String confirmationDigest,
            String currency, long amountMinor, long deadlineAt) { }
    private record PayFrozen(int schema, PayDiningSettlementReq request, PayCommand command) { }

    @Override public DiningSettlementVO customerCreate(CartAccess access, String batchNo,
            CreateDiningSettlementReq request, String key) {
        outside(); buyer(access); CartInputPolicy.key(batchNo); CartInputPolicy.replayKey(key);
        if (request == null) throw bad(); hash(request.confirmationDigest());
        var batch = customerBatch(access, batchNo);
        var binding = sql(() -> binding(batch, false));
        String requestKey = PricingSnapshotHash.of(List.of("FOOD_SETTLEMENT_KEY_V1", access.buyerNo(), key));
        String requestHash = PricingSnapshotHash.of(List.of("FOOD_SETTLEMENT_REQUEST_V1", batch.getBatchNo(), access.buyerNo(), request));
        var replay = sql(() -> byKey(batch, requestKey));
        if (replay != null) { replay(replay, requestHash, access.buyerNo()); return view(replay, batch, null, false); }
        var actual = core().get(identity(batch), binding.getPaymentRootNo());
        validate(batch, binding, actual);
        if (!request.confirmationDigest().equals(actual.confirmationDigest())
                || !request.confirmationDigest().equals(batch.getQuoteHash()) || actual.payDeadlineAt() == null) throw conflict();
        if (!Set.of("WAIT_PAY", "PAID").contains(actual.orderStatus())) throw conflict();
        var frozen = new Frozen(1, batch.getBatchNo(), access.buyerNo(), actual.orderNo(), actual.subOrderNo(),
                actual.quoteNo(), actual.sourceVersion(), actual.sourceHash(), actual.economicsHash(), actual.confirmationDigest(),
                actual.currency(), actual.amountMinor(), actual.payDeadlineAt());
        var saved = transaction(() -> {
            var lockedBinding = binding(batch, true);
            if (!binding.getPaymentRootNo().equals(lockedBinding.getPaymentRootNo())) throw conflict();
            var existing = byKey(batch, requestKey);
            if (existing != null) { replay(existing, requestHash, access.buyerNo()); return existing; }
            String scope = actual.orderNo() + ":FULL";
            if (items.activeFullScopeCount(batch.getEntityNo(), batch.getStoreNo(), actual.orderNo(), scope) != 0) throw conflict();
            var settlement = new FnbSettlement(); stamp(settlement, batch.getEntityNo(), access.buyerNo());
            settlement.setSettlementNo(BizKey.next("FSET")); settlement.setEntityNo(batch.getEntityNo());
            settlement.setStoreNo(batch.getStoreNo()); settlement.setCurrency(frozen.currency()); settlement.setDueMinor(frozen.amountMinor());
            settlement.setStatus("FROZEN"); settlement.setScopeHash(PricingSnapshotHash.of(frozen)); settlement.setQuoteRef(frozen.quote());
            settlement.setCoreCashierRef(frozen.root()); settlement.setExpiresAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(frozen.deadlineAt()), ZoneOffset.UTC));
            settlement.setRequestKey(requestKey); settlement.setRequestHash(requestHash); settlement.setBuyerNo(access.buyerNo());
            settlement.setRequestSnapshot(json.writeValueAsString(frozen)); settlements.insert(settlement);
            var item = new FnbSettlementItem(); stamp(item, batch.getEntityNo(), access.buyerNo());
            item.setEntityNo(batch.getEntityNo()); item.setScopeNo(BizKey.next("FSCP")); item.setSettlementNo(settlement.getSettlementNo());
            item.setCheckNo(batch.getCheckNo()); item.setPaymentRootNo(actual.orderNo()); item.setStoreOrderNo(actual.subOrderNo());
            item.setAllocationRef(actual.economicsHash()); item.setDueMinor(actual.amountMinor()); item.setCoreVersion(actual.economicsHash());
            item.setScopeKey(scope); items.insert(item); return settlement;
        });
        return view(saved, batch, actual, false);
    }
    @Override public DiningSettlementVO customerGet(CartAccess access, String number) {
        outside(); var source = customerSettlement(access, number); var batch = customerBatch(access, frozen(source).batchNo());
        var actual = core().get(identity(batch), frozen(source).root()); exact(source, batch, actual);
        return view(source, batch, actual, false);
    }
    @Override public DiningSettlementVO customerPay(CartAccess access, String number, PayDiningSettlementReq request, String key) {
        outside(); buyer(access); CartInputPolicy.replayKey(key);
        if (request == null) throw bad(); CartInputPolicy.version(request.version()); hash(request.confirmationDigest()); CartInputPolicy.key(request.payChannel());
        var source = customerSettlement(access, number); var batch = customerBatch(access, frozen(source).batchNo());
        var frozen = frozen(source);
        if (!frozen.confirmationDigest().equals(request.confirmationDigest())) throw conflict();
        String requestKey = PricingSnapshotHash.of(List.of("FOOD_CHARGE_KEY_V1", access.buyerNo(), key));
        String digest = PricingSnapshotHash.of(List.of("FOOD_CHARGE_REQUEST_V1", number, access.buyerNo(), request));
        var command = new PayCommand(identity(batch), frozen.root(), frozen.quote(),
                new PricingSourceRef("FOOD", "CART", batch.getCartNo(), batch.getCartVersion(), null, null),
                frozen.confirmationDigest(), request.payChannel());
        var existingCharge = sql(() -> byChargeKey(source, requestKey));
        if (existingCharge == null) {
            var available = core().get(identity(batch), frozen.root()); exact(source, batch, available);
            if (frozen.amountMinor() != 0 && available.channels().stream().noneMatch(c -> c.available()
                    && request.payChannel().equals(c.payChannel()))) throw unsupported();
        }
        var intent = transaction(() -> {
            var locked = settlement(source.getEntityNo(), source.getStoreNo(), number, access.buyerNo(), true);
            var original = byChargeKey(locked, requestKey);
            if (original != null) { if (!digest.equals(original.getRequestHash())) throw conflict(); return original; }
            if (!"FROZEN".equals(locked.getStatus()) || !Objects.equals(locked.getVersion(), request.version())
                    || chargeCount(locked) != 0) throw conflict();
            var row = new FnbChargeIntent(); stamp(row, locked.getEntityNo(), access.buyerNo());
            row.setEntityNo(locked.getEntityNo()); row.setIntentNo(BizKey.next("FCHG")); row.setSettlementNo(number);
            row.setKind("PAY"); row.setRequestKey(requestKey); row.setRequestHash(digest); row.setStatus("PREPARED");
            row.setRequestSnapshot(json.writeValueAsString(new PayFrozen(1, request, command))); intents.insert(row);
            locked.setStatus("COLLECTING"); update(locked); return row;
        });
        boolean dispatch = transaction(() -> {
            var locked = charge(source, intent.getIntentNo(), true);
            if (!"PREPARED".equals(locked.getStatus())) return false;
            locked.setStatus("SENT"); update(locked); return true;
        });
        final View actual;
        try {
            actual = dispatch ? core().pay(json.readValue(intent.getRequestSnapshot(), PayFrozen.class).command())
                    : core().queryPayment(identity(batch), frozen.root());
        } catch (RuntimeException unknown) {
            transaction(() -> { var locked = charge(source, intent.getIntentNo(), true);
                if (!"CONFIRMED".equals(locked.getStatus())) { locked.setStatus("UNKNOWN"); update(locked); } return null; });
            return pending(reload(source), batch, intent);
        }
        financial(source, batch, actual); return view(reload(source), batch, actual, false);
    }
    @Override public DiningSettlementVO customerQuery(CartAccess access, String number) {
        outside(); var source = customerSettlement(access, number); var batch = customerBatch(access, frozen(source).batchNo());
        var actual = core().queryPayment(identity(batch), frozen(source).root());
        financial(source, batch, actual); return view(reload(source), batch, actual, false);
    }
    @Override public DiningSettlementVO customerFinalize(CartAccess access, String number) {
        outside(); var source = customerSettlement(access, number); var batch = customerBatch(access, frozen(source).batchNo());
        var actual = core().finalizeCheckout(identity(batch), frozen(source).root()); exact(source, batch, actual);
        financial(source, batch, actual);
        if (!actual.readyToAccept() || !cashSuccess(actual)) return view(reload(source), batch, actual, false);
        var accepted = transaction(() -> {
            binding(batch, true);
            var locked = batch(batch.getEntityNo(), batch.getStoreNo(), batch.getBatchNo(), true);
            var settled = settlement(source.getEntityNo(), source.getStoreNo(), number, access.buyerNo(), true);
            if (!"SETTLED".equals(settled.getStatus())) throw conflict();
            if ("ACCEPTED".equals(locked.getStatus())) return locked;
            if (!"BOUND".equals(locked.getStatus()) || settlements.localReturnCount(locked.getEntityNo(), locked.getBatchNo()) != 0) throw conflict();
            quotas.consume(locked.getEntityNo(), locked.getStoreNo(), locked.getBatchNo());
            locked.setStatus("ACCEPTED"); locked.setAcceptedAt(now());
            if (batches.updateById(locked) != 1) throw conflict();
            publisher.publish(new DiningOrderAccepted(locked.getBatchNo(), locked.getEntityNo(), locked.getStoreNo(),
                    locked.getCheckNo(), actual.orderNo(), actual.subOrderNo(), locked.getBusinessDate(), locked.getFulfillment(),
                    locked.getAcceptedAt().toInstant(ZoneOffset.UTC)), new PublishOptions("FOOD:BATCH:" + locked.getBatchNo(), 1));
            return locked;
        });
        return view(reload(source), accepted, actual, true);
    }
    @Override public DiningSettlementVO customerCancel(CartAccess access, String number, DiningSettlementVersionReq request) {
        outside(); if (request == null) throw bad(); CartInputPolicy.version(request.version());
        var source = customerSettlement(access, number); var batch = customerBatch(access, frozen(source).batchNo());
        if ("CANCELLED".equals(source.getStatus())) return view(source, batch, null, false);
        var actual = core().get(identity(batch), frozen(source).root()); exact(source, batch, actual);
        if (actual.payment() == null || !"NOT_STARTED".equals(actual.payment().status()) || actual.payment().outTradeNo() != null
                || !"WAIT_PAY".equals(actual.orderStatus())) throw conflict();
        var cancelled = transaction(() -> {
            var locked = settlement(source.getEntityNo(), source.getStoreNo(), number, access.buyerNo(), true);
            if ("CANCELLED".equals(locked.getStatus())) return locked;
            if (!"FROZEN".equals(locked.getStatus()) || !Objects.equals(locked.getVersion(), request.version()) || chargeCount(locked) != 0) throw conflict();
            locked.setStatus("CANCELLED"); update(locked); return locked;
        });
        return view(cancelled, batch, actual, false);
    }
    @Override public DiningSettlementVO staffGet(String entity, String store, String actor, String number) {
        outside(); CartInputPolicy.key(entity); CartInputPolicy.key(store); CartInputPolicy.key(actor); CartInputPolicy.key(number);
        var source = sql(() -> settlement(entity, store, number, null, false));
        var batch = sql(() -> batch(entity, store, frozen(source).batchNo(), false));
        return view(source, batch, null, false); // Staff read never receives a consumer's gateway credentials.
    }
    @Override public DiningSettlementVO recoverOriginal(String entity, String store, String number) {
        outside(); CartInputPolicy.key(entity); CartInputPolicy.key(store); CartInputPolicy.key(number);
        FnbSettlement original = sql(() -> settlement(entity, store, number, null, false));
        CartInputPolicy.key(original.getBuyerNo());
        frozen(original); // Original consent/full scope must still verify before any owner query or projection.
        // These are the persisted consenting buyer's credentials, not a synthetic login or a new pay request.
        return customerFinalize(new CartAccess(null, null, original.getBuyerNo()), number);
    }

    private void financial(FnbSettlement source, FnbOrderBatch batch, View actual) {
        exact(source, batch, actual);
        if ("CANCELLED".equals(source.getStatus())) return;
        transaction(() -> {
            var locked = settlement(source.getEntityNo(), source.getStoreNo(), source.getSettlementNo(), source.getBuyerNo(), true);
            if ("CANCELLED".equals(locked.getStatus())) return null;
            var charges = intents.selectList(Wrappers.<FnbChargeIntent>lambdaQuery().eq(FnbChargeIntent::getTenantNo, "MAIN")
                    .eq(FnbChargeIntent::getEntityNo, source.getEntityNo()).eq(FnbChargeIntent::getSettlementNo, source.getSettlementNo()).last("FOR UPDATE"));
            if (charges.size() > 1) throw conflict();
            if (cashSuccess(actual)) {
                if (!"SETTLED".equals(locked.getStatus())) { locked.setStatus("SETTLED"); update(locked); }
                for (var charge : charges) {
                    if (charge.getCoreReference() != null && !charge.getCoreReference().equals(actual.payment().outTradeNo())) throw conflict();
                    charge.setCoreReference(actual.payment().outTradeNo()); charge.setStatus("CONFIRMED"); update(charge);
                }
            } else {
                for (var charge : charges) if (!"CONFIRMED".equals(charge.getStatus())) {
                    if (actual.payment() != null && actual.payment().outTradeNo() != null) {
                        if (charge.getCoreReference() != null && !charge.getCoreReference().equals(actual.payment().outTradeNo())) throw conflict();
                        charge.setCoreReference(actual.payment().outTradeNo());
                    }
                    charge.setStatus("UNKNOWN"); update(charge);
                }
            }
            return null;
        });
    }
    private FnbOrderBatch customerBatch(CartAccess access, String number) {
        buyer(access); CartInputPolicy.key(number);
        var batch = sql(() -> batches.selectOne(Wrappers.<FnbOrderBatch>lambdaQuery().eq(FnbOrderBatch::getTenantNo, "MAIN")
                .eq(FnbOrderBatch::getBatchNo, number).eq(FnbOrderBatch::getBuyerNo, access.buyerNo())));
        if (batch == null) throw missing();
        var cart = carts.customerGetCart(batch.getCartNo(), access).cart();
        if (cart == null || !batch.getStoreNo().equals(cart.storeNo())
                || !"H5".equals(batch.getSource()) || !"PICKUP".equals(batch.getFulfillment())
                || !"PREPAY".equals(batch.getPayMode()) || !Set.of("BOUND", "ACCEPTED").contains(batch.getStatus())) throw unsupported();
        return batch;
    }
    private FnbSettlement customerSettlement(CartAccess access, String number) {
        buyer(access); CartInputPolicy.key(number);
        var source = sql(() -> settlements.selectOne(Wrappers.<FnbSettlement>lambdaQuery().eq(FnbSettlement::getTenantNo, "MAIN")
                .eq(FnbSettlement::getSettlementNo, number).eq(FnbSettlement::getBuyerNo, access.buyerNo())));
        if (source == null || source.getRequestSnapshot() == null) throw missing(); return source;
    }
    private FnbSettlement settlement(String entity, String store, String number, String buyer, boolean lock) {
        var query = Wrappers.<FnbSettlement>lambdaQuery().eq(FnbSettlement::getTenantNo, "MAIN")
                .eq(FnbSettlement::getEntityNo, entity).eq(FnbSettlement::getStoreNo, store).eq(FnbSettlement::getSettlementNo, number);
        if (buyer != null) query.eq(FnbSettlement::getBuyerNo, buyer);
        if (lock) query.last("FOR UPDATE"); var source = settlements.selectOne(query);
        if (source == null || source.getRequestSnapshot() == null) throw missing(); return source;
    }
    private FnbOrderBatch batch(String entity, String store, String number, boolean lock) {
        var query = Wrappers.<FnbOrderBatch>lambdaQuery().eq(FnbOrderBatch::getTenantNo, "MAIN")
                .eq(FnbOrderBatch::getEntityNo, entity).eq(FnbOrderBatch::getStoreNo, store).eq(FnbOrderBatch::getBatchNo, number);
        if (lock) query.last("FOR UPDATE"); var row = batches.selectOne(query); if (row == null) throw missing(); return row;
    }
    private FnbCoreOrderBinding binding(FnbOrderBatch batch, boolean lock) {
        var query = Wrappers.<FnbCoreOrderBinding>lambdaQuery().eq(FnbCoreOrderBinding::getTenantNo, "MAIN")
                .eq(FnbCoreOrderBinding::getEntityNo, batch.getEntityNo()).eq(FnbCoreOrderBinding::getBatchNo, batch.getBatchNo())
                .eq(FnbCoreOrderBinding::getStatus, "BOUND");
        if (lock) query.last("FOR UPDATE"); var row = bindings.selectOne(query); if (row == null) throw conflict(); return row;
    }
    private FnbSettlement byKey(FnbOrderBatch batch, String key) {
        return settlements.selectOne(Wrappers.<FnbSettlement>lambdaQuery().eq(FnbSettlement::getTenantNo, "MAIN")
                .eq(FnbSettlement::getEntityNo, batch.getEntityNo()).eq(FnbSettlement::getStoreNo, batch.getStoreNo()).eq(FnbSettlement::getRequestKey, key));
    }
    private FnbChargeIntent byChargeKey(FnbSettlement source, String key) {
        return intents.selectOne(Wrappers.<FnbChargeIntent>lambdaQuery().eq(FnbChargeIntent::getTenantNo, "MAIN")
                .eq(FnbChargeIntent::getEntityNo, source.getEntityNo()).eq(FnbChargeIntent::getSettlementNo, source.getSettlementNo()).eq(FnbChargeIntent::getRequestKey, key));
    }
    private FnbChargeIntent charge(FnbSettlement source, String number, boolean lock) {
        var query = Wrappers.<FnbChargeIntent>lambdaQuery().eq(FnbChargeIntent::getTenantNo, "MAIN")
                .eq(FnbChargeIntent::getEntityNo, source.getEntityNo()).eq(FnbChargeIntent::getSettlementNo, source.getSettlementNo()).eq(FnbChargeIntent::getIntentNo, number);
        if (lock) query.last("FOR UPDATE"); var row = intents.selectOne(query); if (row == null) throw conflict(); return row;
    }
    private long chargeCount(FnbSettlement source) {
        return intents.selectCount(Wrappers.<FnbChargeIntent>lambdaQuery().eq(FnbChargeIntent::getTenantNo, "MAIN")
                .eq(FnbChargeIntent::getEntityNo, source.getEntityNo()).eq(FnbChargeIntent::getSettlementNo, source.getSettlementNo()));
    }
    private Frozen frozen(FnbSettlement source) {
        if (source.getRequestSnapshot() == null) throw unsupported();
        var frozen = json.readValue(source.getRequestSnapshot(), Frozen.class);
        if (frozen.schema() != 1 || !Objects.equals(source.getBuyerNo(), frozen.buyerNo())
                || !Objects.equals(source.getScopeHash(), PricingSnapshotHash.of(frozen))) throw conflict(); return frozen;
    }
    private void validate(FnbOrderBatch batch, FnbCoreOrderBinding binding, View actual) {
        if (actual == null || !binding.getPaymentRootNo().equals(actual.orderNo())
                || !binding.getStoreOrderNo().equals(actual.subOrderNo()) || !batch.getQuoteRef().equals(actual.quoteNo())
                || batch.getCartVersion() != actual.sourceVersion() || actual.amountMinor() < 0
                || actual.currency() == null || !actual.currency().matches("[A-Z]{3}")
                || actual.economicsHash() == null || !actual.economicsHash().matches("[0-9a-f]{64}")) throw conflict();
    }
    private void exact(FnbSettlement source, FnbOrderBatch batch, View actual) {
        var frozen = frozen(source);
        if (actual == null || !frozen.batchNo().equals(batch.getBatchNo()) || !frozen.root().equals(actual.orderNo())
                || !frozen.sub().equals(actual.subOrderNo()) || !frozen.quote().equals(actual.quoteNo())
                || frozen.sourceVersion() != actual.sourceVersion() || !frozen.sourceHash().equals(actual.sourceHash())
                || !frozen.economicsHash().equals(actual.economicsHash()) || !frozen.confirmationDigest().equals(actual.confirmationDigest())
                || !frozen.currency().equals(actual.currency()) || frozen.amountMinor() != actual.amountMinor()) throw conflict();
    }
    private DiningSettlementVO view(FnbSettlement source, FnbOrderBatch batch, View actual, boolean accepted) {
        var frozen = frozen(source);
        return new DiningSettlementVO(source.getSettlementNo(), source.getStoreNo(), batch.getBatchNo(), batch.getCheckNo(),
                frozen.root(), frozen.sub(), source.getStatus(), source.getCurrency(), source.getDueMinor(), source.getScopeHash(),
                frozen.confirmationDigest(), source.getVersion(), batch.getStatus(), actual == null ? null : actual.orderStatus(),
                actual == null ? null : actual.payment(), accepted && "ACCEPTED".equals(batch.getStatus()));
    }
    private DiningSettlementVO pending(FnbSettlement source, FnbOrderBatch batch, FnbChargeIntent original) {
        var intent = sql(() -> charge(source, original.getIntentNo(), false));
        var command = json.readValue(intent.getRequestSnapshot(), PayFrozen.class).command();
        var base = view(source, batch, null, false);
        return new DiningSettlementVO(base.settlementNo(), base.storeNo(), base.batchNo(), base.checkNo(), base.orderNo(),
                base.subOrderNo(), base.status(), base.currency(), base.dueMinor(), base.scopeHash(), base.confirmationDigest(),
                base.version(), base.batchStatus(), base.coreOrderStatus(),
                new PaymentVO(intent.getCoreReference(), command.payChannel(), "UNKNOWN", Map.of(), null), false);
    }
    private static boolean cashSuccess(View actual) {
        return actual != null && "PAID".equals(actual.orderStatus()) && actual.payment() != null
                && "SUCCESS".equals(actual.payment().status()) && actual.payment().outTradeNo() != null
                && !actual.payment().outTradeNo().isBlank();
    }
    private static Identity identity(FnbOrderBatch batch) { return new Identity("MAIN", batch.getEntityNo(), batch.getStoreNo(), batch.getBuyerNo(), batch.getBuyerNo()); }
    private static void replay(FnbSettlement source, String hash, String buyer) {
        if (!hash.equals(source.getRequestHash()) || !buyer.equals(source.getBuyerNo())) throw conflict();
    }
    private FnbSettlement reload(FnbSettlement source) { return sql(() -> settlement(source.getEntityNo(), source.getStoreNo(), source.getSettlementNo(), source.getBuyerNo(), false)); }
    private <T> T transaction(Supplier<T> action) { return sql(() -> write.execute(status -> action.get())); }
    private static <T> T sql(Supplier<T> action) { return DataScopeContext.executeWithoutScope(action); }
    private void update(FnbSettlement source) { source.setUpdatedAt(now()); if (settlements.updateById(source) != 1) throw conflict(); }
    private void update(FnbChargeIntent source) { source.setUpdatedAt(now()); if (intents.updateById(source) != 1) throw conflict(); }
    private void stamp(BaseEntity row, String entity, String actor) {
        row.setTenantNo("MAIN"); row.setVersion(0L); row.setDeleted(0); row.setCreatedBy(actor); row.setUpdatedBy(actor);
        row.setCreatedAt(now()); row.setUpdatedAt(now());
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private IndustryCheckoutPort core() { var value = core.get(); if (value == null) throw unsupported(); return value; }
    private static void buyer(CartAccess access) {
        if (access == null || access.buyerNo() == null || access.sessionNo() != null || access.accessToken() != null) throw unsupported();
        CartInputPolicy.key(access.buyerNo());
    }
    private static void hash(String value) { if (value == null || !value.matches("[0-9a-f]{64}")) throw bad(); }
    private static void outside() { if (TransactionSynchronizationManager.isActualTransactionActive()) throw unsupported(); }
    private static BizException bad() { return BizException.of(ErrorCode.BAD_REQUEST); }
    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }
    private static BizException unsupported() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
    private static BizException missing() { return BizException.of(ErrorCode.NOT_FOUND); }
}
