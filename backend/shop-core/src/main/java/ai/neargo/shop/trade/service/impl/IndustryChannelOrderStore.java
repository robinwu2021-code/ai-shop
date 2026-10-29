package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.numbering.DocumentSource;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.trade.IndustryChannelOrderFacts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Cancellation;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Collection;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.CollectionFacts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Data;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Facts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Withdrawal;
import ai.neargo.shop.trade.entity.OrdChannelAdmissionGuard;
import ai.neargo.shop.trade.entity.OrdChannelFundingHistory;
import ai.neargo.shop.trade.entity.OrdChannelImport;
import ai.neargo.shop.trade.entity.OrdChannelPolicy;
import ai.neargo.shop.trade.entity.OrdChannelPreparation;
import ai.neargo.shop.trade.entity.OrdChannelQuote;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.mapper.TradeMappers.ChannelAdmissionGuardMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.ChannelFundingHistoryMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.ChannelImportMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.ChannelPolicyMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.ChannelPreparationMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.ChannelQuoteMapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Original primary bookkeeping only. No source, payment or inventory network call runs under these
 * transactions.
 */
@Component
public final class IndustryChannelOrderStore {
    final ChannelImportMapper imports;
    final ChannelAdmissionGuardMapper guards;
    final ChannelQuoteMapper quotes;
    final ChannelPolicyMapper policies;
    final ChannelPreparationMapper preparations;
    final ObjectMapper json;
    private final DocumentNumberCoordinator numbers;
    private final TransactionTemplate read;
    private final TransactionTemplate write;
    final Clock clock;
    private ChannelFundingHistoryMapper fundingHistory;

    /**
     * Existing manual fixtures keep their constructors; production always installs the actual
     * immutable history mapper.
     */
    @Autowired
    public void fundingHistory(ChannelFundingHistoryMapper history) {
        this.fundingHistory = history;
    }

    @Autowired
    public IndustryChannelOrderStore(
            ChannelImportMapper imports,
            ChannelAdmissionGuardMapper guards,
            ChannelQuoteMapper quotes,
            ChannelPolicyMapper policies,
            ChannelPreparationMapper preparations,
            ObjectMapper json,
            @Qualifier("coreDocumentNumberCoordinator") DocumentNumberCoordinator numbers,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this(
                imports,
                guards,
                quotes,
                policies,
                preparations,
                json,
                numbers,
                manager,
                Clock.systemUTC());
    }

    IndustryChannelOrderStore(
            ChannelImportMapper imports,
            ChannelAdmissionGuardMapper guards,
            ChannelQuoteMapper quotes,
            ChannelPolicyMapper policies,
            ChannelPreparationMapper preparations,
            ObjectMapper json,
            DocumentNumberCoordinator numbers,
            PlatformTransactionManager manager,
            Clock clock) {
        this.imports = imports;
        this.guards = guards;
        this.quotes = quotes;
        this.policies = policies;
        this.preparations = preparations;
        this.json = json;
        this.numbers = numbers;
        this.clock = clock;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    <T> T query(Supplier<T> action) {
        return DataScopeContext.executeWithoutScope(() -> read.execute(tx -> action.get()));
    }

    <T> T atomic(Supplier<T> action) {
        return DataScopeContext.executeWithoutScope(() -> write.execute(tx -> action.get()));
    }

    LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    void audit(BaseEntity row, Scope scope) {
        row.setTenantNo(scope.tenantNo());
        row.setVersion(0L);
        row.setDeleted(0);
        row.setCreatedAt(now());
        row.setUpdatedAt(now());
        row.setCreatedBy("SYSTEM");
        row.setUpdatedBy("SYSTEM");
    }

    OrdChannelAdmissionGuard guard(Reference ref, boolean lock) {
        var query =
                Wrappers.<OrdChannelAdmissionGuard>query()
                        .eq("tenant_no", ref.scope().tenantNo())
                        .eq("entity_no", ref.scope().entityNo())
                        .eq("store_no", ref.scope().storeNo())
                        .eq("source_system", ref.sourceSystem())
                        .eq("import_ref", ref.importRef());
        if (lock) query.last("FOR UPDATE");
        var guard = guards.selectOne(query);
        if (guard != null)
            require(
                    ref.sourceNo().equals(guard.getSourceNo())
                            && ref.sourceVersion() == guard.getSourceVersion()
                            && ref.sourceHash().equals(guard.getSourceHash()));
        return guard;
    }

    void ensureGuard(Reference ref) {
        try {
            atomic(
                    () -> {
                        if (guard(ref, true) != null) return null;
                        var row = new OrdChannelAdmissionGuard();
                        audit(row, ref.scope());
                        row.setEntityNo(ref.scope().entityNo());
                        row.setStoreNo(ref.scope().storeNo());
                        row.setSourceSystem(ref.sourceSystem());
                        row.setSourceNo(ref.sourceNo());
                        row.setSourceVersion(ref.sourceVersion());
                        row.setSourceHash(ref.sourceHash());
                        row.setImportRef(ref.importRef());
                        row.setStatus("ACTIVE");
                        guards.insert(row);
                        return null;
                    });
        } catch (DuplicateKeyException concurrent) {
            require(query(() -> guard(ref, false)) != null);
        }
    }

    void activeGuard(Reference ref) {
        var guard = guard(ref, true);
        require(guard != null && "ACTIVE".equals(guard.getStatus()));
    }

    /**
     * A genuine source withdrawal closes an absent admission, or fences an existing one for actual
     * stock withdrawal.
     */
    Cancellation beginCancellation(Reference ref, Withdrawal proof) {
        ensureGuard(ref);
        return atomic(
                () -> {
                    var guard = guard(ref, true);
                    require(guard != null);
                    if ("CANCELLED".equals(guard.getStatus()))
                        return new Cancellation(ref, guard.getCancellationReceiptRef());
                    var order = find(ref, true);
                    require(order == null || !"ACCEPTED".equals(order.getStatus()));
                    String receipt = order == null ? BizKey.next("OCX") : null;
                    require(
                            guards.update(
                                            Wrappers.<OrdChannelAdmissionGuard>update()
                                                    .eq("tenant_no", ref.scope().tenantNo())
                                                    .eq("id", guard.getId())
                                                    .eq("version", guard.getVersion())
                                                    .set(
                                                            "status",
                                                            order == null
                                                                    ? "CANCELLED"
                                                                    : "CANCELLING")
                                                    .set(
                                                            "withdrawal_snapshot",
                                                            json.writeValueAsString(proof))
                                                    .set("cancellation_receipt_ref", receipt)
                                                    .setSql("version=version+1"))
                                    == 1);
                    return receipt == null ? null : new Cancellation(ref, receipt);
                });
    }

    void finishCancellation(Reference ref, String receipt) {
        var guard = guard(ref, true);
        require(guard != null && "CANCELLING".equals(guard.getStatus()));
        require(
                guards.update(
                                Wrappers.<OrdChannelAdmissionGuard>update()
                                        .eq("tenant_no", ref.scope().tenantNo())
                                        .eq("id", guard.getId())
                                        .eq("version", guard.getVersion())
                                        .set("status", "CANCELLED")
                                        .set("cancellation_receipt_ref", receipt)
                                        .setSql("version=version+1"))
                        == 1);
    }

    OrdChannelImport find(Reference ref, boolean lock) {
        var q =
                Wrappers.<OrdChannelImport>query()
                        .eq("tenant_no", ref.scope().tenantNo())
                        .eq("entity_no", ref.scope().entityNo())
                        .eq("store_no", ref.scope().storeNo())
                        .eq("source_system", ref.sourceSystem())
                        .eq("import_ref", ref.importRef());
        if (lock) q.last("FOR UPDATE");
        var row = imports.selectOne(q);
        if (row != null) exact(row, ref);
        return row;
    }

    OrdChannelImport required(Reference ref, boolean lock) {
        var row = find(ref, lock);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    void exact(OrdChannelImport row, Reference ref) {
        require(
                row.getTenantNo().equals(ref.scope().tenantNo())
                        && row.getEntityNo().equals(ref.scope().entityNo())
                        && row.getStoreNo().equals(ref.scope().storeNo())
                        && row.getSourceSystem().equals(ref.sourceSystem())
                        && row.getSourceNo().equals(ref.sourceNo())
                        && row.getSourceVersion() == ref.sourceVersion()
                        && row.getSourceHash().equals(ref.sourceHash())
                        && row.getImportRef().equals(ref.importRef()));
    }

    OrdChannelQuote quote(OrdChannelImport row) {
        var quote =
                quotes.selectOne(
                        Wrappers.<OrdChannelQuote>query()
                                .eq("tenant_no", row.getTenantNo())
                                .eq("entity_no", row.getEntityNo())
                                .eq("store_no", row.getStoreNo())
                                .eq("quote_no", row.getQuoteNo())
                                .eq("admission_no", row.getAdmissionNo()));
        require(quote != null && quote.getSourceHash().equals(row.getSourceHash()));
        return quote;
    }

    Data data(OrdChannelImport row) {
        var original = json.readValue(quote(row).getSourceSnapshot(), Data.class);
        require(PricingSnapshotHash.of(original).equals(row.getSourceHash()));
        return original;
    }

    OrdChannelPreparation preparation(OrdChannelImport row, boolean lock) {
        if (row.getPreparationNo() == null) return null;
        var q =
                Wrappers.<OrdChannelPreparation>query()
                        .eq("tenant_no", row.getTenantNo())
                        .eq("entity_no", row.getEntityNo())
                        .eq("store_no", row.getStoreNo())
                        .eq("admission_no", row.getAdmissionNo())
                        .eq("preparation_no", row.getPreparationNo());
        if (lock) q.last("FOR UPDATE");
        var preparation = preparations.selectOne(q);
        require(preparation != null);
        return preparation;
    }

    /** Genuine original numbers and source quote commit before any independent inventory call. */
    OrdChannelImport create(Facts facts) {
        var ref = facts.reference();
        ensureGuard(ref);
        try {
            return DataScopeContext.executeWithoutScope(
                    () ->
                            numbers.transaction(
                                    Map.of(
                                            DocumentType.TRADE_ORDER,
                                            1,
                                            DocumentType.TRADE_SUBORDER,
                                            1),
                                    () -> {
                                        activeGuard(ref);
                                        var prior = find(ref, true);
                                        if (prior != null) return prior;
                                        var order = numbers.takeReserved(DocumentType.TRADE_ORDER);
                                        var sub = numbers.takeReserved(DocumentType.TRADE_SUBORDER);
                                        var row = new OrdChannelImport();
                                        audit(row, ref.scope());
                                        row.setAdmissionNo(BizKey.next("OCI"));
                                        row.setEntityNo(ref.scope().entityNo());
                                        row.setStoreNo(ref.scope().storeNo());
                                        row.setSourceSystem(ref.sourceSystem());
                                        row.setSourceNo(ref.sourceNo());
                                        row.setSourceVersion(ref.sourceVersion());
                                        row.setSourceHash(ref.sourceHash());
                                        row.setImportRef(ref.importRef());
                                        row.setQuoteNo(BizKey.next("OCQ"));
                                        row.setChannelStoreKey(
                                                facts.data().channel().channelStoreKey());
                                        row.setExternalOrderRef(facts.data().externalOrderRef());
                                        row.setExternalOrderKey(
                                                PricingSnapshotHash.of(
                                                        facts.data().externalOrderRef()));
                                        row.setOrderNo(order.value());
                                        row.setSubOrderNo(sub.value());
                                        row.setStatus("WAITING_FUNDING");
                                        row.setFundingStatus("UNCONFIRMED");
                                        row.setReservedMinor(0L);
                                        row.setFence(0L);
                                        imports.insert(row);
                                        var quote = new OrdChannelQuote();
                                        audit(quote, ref.scope());
                                        quote.setQuoteNo(row.getQuoteNo());
                                        quote.setEntityNo(row.getEntityNo());
                                        quote.setStoreNo(row.getStoreNo());
                                        quote.setAdmissionNo(row.getAdmissionNo());
                                        quote.setSourceSnapshot(
                                                json.writeValueAsString(facts.data()));
                                        quote.setSourceHash(ref.sourceHash());
                                        quote.setCurrency(facts.data().currency());
                                        quote.setMerchantReceivableMinor(
                                                facts.data().totals().merchantReceivableMinor());
                                        quote.setCustomerPayableMinor(
                                                facts.data().totals().customerPayableMinor());
                                        quote.setSourceReceiptRef(facts.receiptRef());
                                        quotes.insert(quote);
                                        numbers.bindReserved(
                                                new DocumentSource(
                                                        ref.scope().tenantNo(),
                                                        "CORE",
                                                        "TRADE_ORDER",
                                                        row.getOrderNo()),
                                                order);
                                        numbers.bindReserved(
                                                new DocumentSource(
                                                        ref.scope().tenantNo(),
                                                        "CORE",
                                                        "TRADE_SUBORDER",
                                                        row.getSubOrderNo()),
                                                sub);
                                        return row;
                                    }));
        } catch (DuplicateKeyException duplicate) {
            var prior = query(() -> find(ref, false));
            if (prior == null) throw BizException.of(ErrorCode.CONFLICT);
            return prior;
        }
    }

    record FundingGrant(
            String policyNo,
            long policyVersion,
            String currency,
            long amountMinor,
            int holdSeconds,
            CollectionFacts originalCollection) {}

    /**
     * A row lock serializes configuration/credit admission and cancellation without any external
     * call.
     */
    OrdChannelImport reserveFunding(Reference ref, Data original, CollectionFacts collection) {
        return atomic(
                () -> {
                    var policy =
                            policies.selectOne(
                                    Wrappers.<OrdChannelPolicy>query()
                                            .eq("tenant_no", ref.scope().tenantNo())
                                            .eq("entity_no", ref.scope().entityNo())
                                            .eq("store_no", ref.scope().storeNo())
                                            .eq(
                                                    "channel_store_key",
                                                    original.channel().channelStoreKey())
                                            .last("FOR UPDATE"));
                    activeGuard(ref);
                    var row = required(ref, true);
                    IndustryChannelOrderFacts.collection(ref, original, collection, clock);
                    if (row.getPolicyNo() != null)
                        return admitCollectionRevision(ref, row, policy, original, collection);
                    if (!"WAITING_FUNDING".equals(row.getStatus())) return row;
                    long amount = original.totals().merchantReceivableMinor();
                    boolean allowed =
                            policy != null
                                    && Boolean.TRUE.equals(policy.getEnabled())
                                    && Objects.equals(policy.getCurrency(), original.currency())
                                    && (collection.collection() == Collection.PLATFORM_COLLECTED
                                                    && Boolean.TRUE.equals(
                                                            policy.getAllowPlatformCollected())
                                            || collection.collection()
                                                            == Collection.CASH_ON_DELIVERY
                                                    && Boolean.TRUE.equals(
                                                            policy.getAllowCashOnDelivery()))
                                    && amount <= policy.getMaxSingleMinor()
                                    && amount
                                            <= policy.getMaxOutstandingMinor()
                                                    - policy.getReservedMinor();
                    if (!allowed) {
                        imports.update(
                                Wrappers.<OrdChannelImport>update()
                                        .eq("tenant_no", ref.scope().tenantNo())
                                        .eq("id", row.getId())
                                        .eq("version", row.getVersion())
                                        .set("status", "WAITING_FUNDING")
                                        .set("funding_status", "WAITING_ADMISSION")
                                        .setSql("version=version+1"));
                        return required(ref, false);
                    }
                    require(
                            policies.update(
                                            Wrappers.<OrdChannelPolicy>update()
                                                    .eq("tenant_no", ref.scope().tenantNo())
                                                    .eq("id", policy.getId())
                                                    .eq("version", policy.getVersion())
                                                    .set(
                                                            "reserved_minor",
                                                            Math.addExact(
                                                                    policy.getReservedMinor(),
                                                                    amount))
                                                    .setSql("version=version+1"))
                                    == 1);
                    var grant =
                            new FundingGrant(
                                    policy.getPolicyNo(),
                                    policy.getVersion(),
                                    original.currency(),
                                    amount,
                                    policy.getHoldSeconds(),
                                    collection);
                    rememberFunding(ref, row, null, grant);
                    require(
                            imports.update(
                                            Wrappers.<OrdChannelImport>update()
                                                    .eq("tenant_no", ref.scope().tenantNo())
                                                    .eq("id", row.getId())
                                                    .eq("version", row.getVersion())
                                                    .set("status", "WAITING_STOCK")
                                                    .set(
                                                            "funding_status",
                                                            collection.collection()
                                                                            == Collection
                                                                                    .PLATFORM_COLLECTED
                                                                    ? "RESERVED_PLATFORM"
                                                                    : "RESERVED_COD")
                                                    .set("policy_no", policy.getPolicyNo())
                                                    .set(
                                                            "policy_snapshot",
                                                            json.writeValueAsString(grant))
                                                    .set("reserved_minor", amount)
                                                    .setSql("version=version+1"))
                                    == 1);
                    return required(ref, false);
                });
    }

    /**
     * A current original collection revision needs its own current merchant admission; credit is
     * not reserved twice.
     */
    private OrdChannelImport admitCollectionRevision(
            Reference ref,
            OrdChannelImport row,
            OrdChannelPolicy policy,
            Data original,
            CollectionFacts collection) {
        var previous = json.readValue(row.getPolicySnapshot(), FundingGrant.class);
        if (previous.originalCollection().collection() == collection.collection()) return row;
        require(
                fundingHistory != null
                        && previous.originalCollection().collection() == Collection.CASH_ON_DELIVERY
                        && collection.collection() == Collection.PLATFORM_COLLECTED
                        && !Set.of("ACCEPTED", "CANCELLING", "CANCELLED").contains(row.getStatus())
                        && (row.getLeaseUntil() == null || !row.getLeaseUntil().isAfter(now()))
                        && policy != null
                        && Boolean.TRUE.equals(policy.getEnabled())
                        && Boolean.TRUE.equals(policy.getAllowPlatformCollected())
                        && Objects.equals(policy.getPolicyNo(), row.getPolicyNo())
                        && Objects.equals(policy.getCurrency(), original.currency())
                        && row.getReservedMinor() == previous.amountMinor()
                        && previous.amountMinor() == original.totals().merchantReceivableMinor()
                        && previous.amountMinor() <= policy.getMaxSingleMinor()
                        && policy.getReservedMinor() <= policy.getMaxOutstandingMinor());
        var admitted =
                new FundingGrant(
                        policy.getPolicyNo(),
                        policy.getVersion(),
                        original.currency(),
                        previous.amountMinor(),
                        policy.getHoldSeconds(),
                        collection);
        rememberFunding(ref, row, previous, admitted);
        require(
                imports.update(
                                Wrappers.<OrdChannelImport>update()
                                        .eq("tenant_no", ref.scope().tenantNo())
                                        .eq("id", row.getId())
                                        .eq("version", row.getVersion())
                                        .set("funding_status", "RESERVED_PLATFORM")
                                        .set("policy_snapshot", json.writeValueAsString(admitted))
                                        .setSql("version=version+1"))
                        == 1);
        return required(ref, false);
    }

    private void rememberFunding(
            Reference ref, OrdChannelImport row, FundingGrant previous, FundingGrant admitted) {
        if (fundingHistory == null)
            return; // Only legacy manually constructed test fixtures omit the production mapper.
        var history = new OrdChannelFundingHistory();
        audit(history, ref.scope());
        history.setHistoryNo(BizKey.next("OCFH"));
        history.setEntityNo(ref.scope().entityNo());
        history.setStoreNo(ref.scope().storeNo());
        history.setAdmissionNo(row.getAdmissionNo());
        history.setPreviousCollection(
                previous == null ? null : previous.originalCollection().collection().name());
        history.setAdmittedCollection(admitted.originalCollection().collection().name());
        history.setPreviousSnapshot(previous == null ? null : json.writeValueAsString(previous));
        history.setAdmittedSnapshot(json.writeValueAsString(admitted));
        history.setOriginalCollectionHash(PricingSnapshotHash.of(admitted.originalCollection()));
        require(fundingHistory.insert(history) == 1);
    }

    /** A local original acceptance receipt authorizes only its own frozen physical plan. */
    Plan acceptedPlan(OrdIndustryOrderSource binding) {
        return query(
                () -> {
                    require(
                            "CHANNEL".equals(binding.getOrderMode())
                                    && "CHANNEL_ORDER".equals(binding.getSourceType())
                                    && "ACCEPTED".equals(binding.getStatus()));
                    var row =
                            imports.selectOne(
                                    Wrappers.<OrdChannelImport>query()
                                            .eq("tenant_no", binding.getTenantNo())
                                            .eq("entity_no", binding.getEntityNo())
                                            .eq("store_no", binding.getStoreNo())
                                            .eq("order_no", binding.getOrderNo())
                                            .eq("sub_order_no", binding.getSubOrderNo()));
                    require(
                            row != null
                                    && "ACCEPTED".equals(row.getStatus())
                                    && row.getAcceptanceReceiptRef() != null
                                    && row.getPlatformAcceptanceSnapshot() != null
                                    && row.getPolicySnapshot() != null
                                    && row.getPolicyNo() != null
                                    && row.getSourceSystem().equals(binding.getSourceSystem())
                                    && row.getSourceNo().equals(binding.getSourceNo())
                                    && row.getSourceVersion().equals(binding.getSourceVersion())
                                    && row.getSourceHash().equals(binding.getSourceHash())
                                    && row.getQuoteNo().equals(binding.getQuoteNo())
                                    && row.getReservedMinor()
                                            .equals(quote(row).getMerchantReceivableMinor()));
                    var preparation = preparation(row, false);
                    require(preparation != null && preparation.getPlanSnapshot() != null);
                    var plan = json.readValue(preparation.getPlanSnapshot(), Plan.class);
                    require(
                            plan.planNo().equals(binding.getDemandPlanNo())
                                    && plan.planHash().equals(binding.getDemandPlanHash())
                                    && plan.equals(
                                            json.readValue(
                                                    binding.getDemandPlanSnapshot(), Plan.class)));
                    return plan;
                });
    }

    static void require(boolean condition) {
        if (!condition) throw BizException.of(ErrorCode.CONFLICT);
    }
}
