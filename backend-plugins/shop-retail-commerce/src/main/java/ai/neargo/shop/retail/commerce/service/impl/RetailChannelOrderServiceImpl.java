package ai.neargo.shop.retail.commerce.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.commerce.policy.ChannelOrderEconomicPolicy;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.retail.commerce.entity.RetailChannelImportGuard;
import ai.neargo.shop.retail.commerce.entity.RetailChannelOrderSource;
import ai.neargo.shop.retail.commerce.entity.RetailChannelParty;
import ai.neargo.shop.retail.commerce.mapper.RetailChannelMappers.ImportGuardMapper;
import ai.neargo.shop.retail.commerce.mapper.RetailChannelMappers.OrderSourceMapper;
import ai.neargo.shop.retail.commerce.mapper.RetailChannelMappers.PartyMapper;
import ai.neargo.shop.retail.commerce.port.RetailChannelSourceReader;
import ai.neargo.shop.retail.commerce.service.RetailChannelOrderService;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;
import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidencePort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Branch;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Leaf;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderFacts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Channel;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Collection;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.CollectionFacts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Component;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Data;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Delivery;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Facts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Fulfillment;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.InvoiceLine;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Party;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.PlatformAcceptance;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Totals;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Withdrawal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;

/**
 * Original retail sources persist real core channel receipts without ordinary checkout, local users
 * or fabricated industry documents.
 */
public final class RetailChannelOrderServiceImpl implements RetailChannelOrderService {
    private final ImportGuardMapper guards;
    private final OrderSourceMapper sources;
    private final PartyMapper parties;
    private final RetailChannelSourceReader reader;
    private final IndustryChannelOrderPort core;
    private final MaterialCatalogQueryPort materials;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public RetailChannelOrderServiceImpl(
            ImportGuardMapper guards,
            OrderSourceMapper sources,
            PartyMapper parties,
            RetailChannelSourceReader reader,
            IndustryChannelOrderPort core,
            MaterialCatalogQueryPort materials,
            ObjectMapper json,
            Clock clock,
            PlatformTransactionManager manager) {
        this.guards = guards;
        this.sources = sources;
        this.parties = parties;
        this.reader = reader;
        this.core = core;
        this.materials = materials;
        this.json = json;
        this.clock = clock;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    @Override
    public Owner owner() {
        return Owner.RETAIL;
    }

    @Override
    public String sourceSystem() {
        return "RETAIL";
    }

    @Override
    public Prepared prepareImport(Command command) {
        require(command != null && command.original() != null);
        var reference =
                new ChannelOrderIngestPort.Reference(
                        command.importNo(),
                        command.entityNo(),
                        command.storeNo(),
                        command.original().documentHash());
        scope(reference);
        outside();
        var guard = query(() -> guard(reference, false));
        if (guard != null && "CANCELLED".equals(guard.getStatus()))
            return cancelledPreparation(reference);
        var source = query(() -> find(reference, false));
        if (source == null) source = register(reference, reader.original(reference));
        return reconcile(reference, source);
    }

    @Override
    public Prepared getImport(ChannelOrderIngestPort.Reference reference) {
        scope(reference);
        outside();
        var guard = query(() -> guard(reference, false));
        if (guard != null && "CANCELLED".equals(guard.getStatus()))
            return cancelledPreparation(reference);
        return reconcile(reference, query(() -> required(reference, false)));
    }

    private Prepared reconcile(
            ChannelOrderIngestPort.Reference reference, RetailChannelOrderSource source) {
        var state = query(() -> guard(reference, false));
        require(state != null);
        if ("CANCELLED".equals(state.getStatus()) || "CANCELLED".equals(source.getStatus()))
            return cancelledPreparation(reference);
        if (!"ACTIVE".equals(state.getStatus()))
            return new Prepared(reference, Status.WAITING_OWNER, null, null);
        if ("ACCEPTED".equals(source.getStatus())) {
            require(
                    source.getAcceptanceSnapshot() != null
                            && source.getAcceptanceReceiptRef() != null);
            return new Prepared(reference, Status.ACCEPTED, source.getAcceptanceReceiptRef(), null);
        }
        positive(source, reader.current(reference));
        var own = coreReference(source);
        var actual = core.get(own);
        if (actual == null) actual = core.prepare(own);
        require(actual != null && own.equals(actual.reference()));
        if (actual.status() == IndustryChannelOrderPort.Status.PREPARED) {
            IndustryChannelOrderFacts.key(actual.preparationReceiptRef(), 128);
            require(
                    actual.validUntil() != null
                            && actual.validUntil().isAfter(clock.instant())
                            && actual.inventoryPlan() != null
                            && actual.quoteNo() != null);
        }
        persistPreparation(reference, actual);
        if (actual.status() == IndustryChannelOrderPort.Status.ACCEPTED)
            return new Prepared(reference, Status.COMMITTED, null, null);
        if (actual.status() == IndustryChannelOrderPort.Status.PREPARED)
            return new Prepared(
                    reference,
                    Status.PREPARED,
                    actual.preparationReceiptRef(),
                    actual.validUntil());
        if (actual.status() == IndustryChannelOrderPort.Status.CANCELLED)
            return cancelledPreparation(reference);
        return new Prepared(reference, Status.WAITING_OWNER, null, null);
    }

    @Override
    public Accepted confirmAcceptance(Confirmation confirmation) {
        require(confirmation != null);
        var reference = confirmation.reference();
        scope(reference);
        outside();
        var source = query(() -> required(reference, false));
        if (source.getAcceptanceSnapshot() != null) return accepted(reference, source);
        require(query(() -> "ACTIVE".equals(guard(reference, false).getStatus())));
        positive(source, reader.current(reference));
        var actual = core.confirm(coreReference(source));
        require(actual != null && coreReference(source).equals(actual.reference()));
        var prepared = core.get(actual.reference());
        require(
                prepared != null
                        && prepared.status() == IndustryChannelOrderPort.Status.ACCEPTED
                        && actual.reference().equals(prepared.reference())
                        && actual.orderNo().equals(prepared.orderNo())
                        && actual.subOrderNo().equals(prepared.subOrderNo())
                        && prepared.quoteNo() != null
                        && prepared.inventoryPlan() != null
                        && actual.acceptanceReceiptRef() != null);
        validateAllocations(source, actual);
        var saved =
                atomic(
                        () -> {
                            var currentGuard = guard(reference, true);
                            var row = required(reference, true);
                            if (row.getAcceptanceSnapshot() != null) {
                                require(
                                        json.readValue(
                                                        row.getAcceptanceSnapshot(),
                                                        IndustryChannelOrderPort.Acceptance.class)
                                                .equals(actual));
                                return row;
                            }
                            require(
                                    currentGuard != null
                                            && !"CANCELLED".equals(currentGuard.getStatus()));
                            if (!"ACTIVE".equals(currentGuard.getStatus()))
                                require(
                                        guards.update(
                                                        Wrappers.<RetailChannelImportGuard>update()
                                                                .eq("id", currentGuard.getId())
                                                                .eq(
                                                                        "version",
                                                                        currentGuard.getVersion())
                                                                .set("status", "AFTER_SALE")
                                                                .setSql("version=version+1"))
                                                == 1);
                            require(
                                    sources.update(
                                                    Wrappers.<RetailChannelOrderSource>update()
                                                            .eq("id", row.getId())
                                                            .eq("version", row.getVersion())
                                                            .set("status", "ACCEPTED")
                                                            .set(
                                                                    "preparation_snapshot",
                                                                    json.writeValueAsString(
                                                                            prepared))
                                                            .set(
                                                                    "acceptance_snapshot",
                                                                    json.writeValueAsString(actual))
                                                            .set(
                                                                    "acceptance_receipt_ref",
                                                                    actual.acceptanceReceiptRef())
                                                            .setSql("version=version+1"))
                                            == 1);
                            return required(reference, false);
                        });
        return accepted(reference, saved);
    }

    @Override
    public Cancelled cancelPreparedImport(
            ChannelOrderIngestPort.Reference reference, String reason) {
        scope(reference);
        outside();
        var old = query(() -> guard(reference, false));
        if (old != null && "CANCELLED".equals(old.getStatus()))
            return new Cancelled(reference, old.getCancellationReceiptRef());
        var actual = reader.current(reference);
        require(actual.current().document().state() == ChannelOrderGateway.State.CANCELLED);
        ensureGuard(reference);
        var absent =
                atomic(
                        () -> {
                            var guard = guard(reference, true);
                            if ("CANCELLED".equals(guard.getStatus()))
                                return new Cancelled(reference, guard.getCancellationReceiptRef());
                            require(!"AFTER_SALE".equals(guard.getStatus()));
                            var source = find(reference, true);
                            require(source == null || source.getAcceptanceSnapshot() == null);
                            String receipt = source == null ? BizKey.next("RCX") : null;
                            require(
                                    guards.update(
                                                    Wrappers.<RetailChannelImportGuard>update()
                                                            .eq("id", guard.getId())
                                                            .eq("version", guard.getVersion())
                                                            .set(
                                                                    "status",
                                                                    source == null
                                                                            ? "CANCELLED"
                                                                            : "CANCELLING")
                                                            .set(
                                                                    "withdrawal_snapshot",
                                                                    json.writeValueAsString(actual))
                                                            .set(
                                                                    "cancellation_receipt_ref",
                                                                    receipt)
                                                            .setSql("version=version+1"))
                                            == 1);
                            return receipt == null ? null : new Cancelled(reference, receipt);
                        });
        if (absent != null) return absent;
        var source = query(() -> required(reference, false));
        IndustryChannelOrderPort.Cancellation cancelled;
        try {
            cancelled = core.cancel(coreReference(source));
        } catch (RuntimeException unavailable) {
            var prepared = core.get(coreReference(source));
            if (prepared != null && prepared.status() == IndustryChannelOrderPort.Status.ACCEPTED)
                atomic(
                        () -> {
                            var guard = guard(reference, true);
                            require(
                                    guards.update(
                                                    Wrappers.<RetailChannelImportGuard>update()
                                                            .eq("id", guard.getId())
                                                            .eq("version", guard.getVersion())
                                                            .set("status", "AFTER_SALE")
                                                            .setSql("version=version+1"))
                                            == 1);
                            return null;
                        });
            throw unavailable;
        }
        require(
                cancelled != null
                        && coreReference(source).equals(cancelled.reference())
                        && cancelled.cancellationReceiptRef() != null);
        return atomic(
                () -> {
                    var guard = guard(reference, true);
                    var row = required(reference, true);
                    if ("CANCELLED".equals(guard.getStatus()))
                        return new Cancelled(reference, guard.getCancellationReceiptRef());
                    require(
                            "CANCELLING".equals(guard.getStatus())
                                    && row.getAcceptanceSnapshot() == null);
                    require(
                            sources.update(
                                            Wrappers.<RetailChannelOrderSource>update()
                                                    .eq("id", row.getId())
                                                    .eq("version", row.getVersion())
                                                    .set("status", "CANCELLED")
                                                    .set(
                                                            "cancellation_snapshot",
                                                            json.writeValueAsString(cancelled))
                                                    .set(
                                                            "cancellation_receipt_ref",
                                                            cancelled.cancellationReceiptRef())
                                                    .setSql("version=version+1"))
                                    == 1);
                    require(
                            guards.update(
                                            Wrappers.<RetailChannelImportGuard>update()
                                                    .eq("id", guard.getId())
                                                    .eq("version", guard.getVersion())
                                                    .set("status", "CANCELLED")
                                                    .set(
                                                            "cancellation_receipt_ref",
                                                            cancelled.cancellationReceiptRef())
                                                    .setSql("version=version+1"))
                                    == 1);
                    return new Cancelled(reference, cancelled.cancellationReceiptRef());
                });
    }

    @Override
    public Facts capture(IndustryChannelOrderPort.Reference reference) {
        outside();
        var source = source(reference);
        var current = reader.current(reference(source));
        positive(source, current);
        var snapshot = current.current();
        return new Facts(
                reference,
                data(source),
                snapshot.observedAt(),
                snapshot.validUntil(),
                snapshot.originalReceiptRef());
    }

    @Override
    public Plan demandPlan(
            IndustryChannelOrderPort.Reference reference,
            String reservationRef,
            String subOrderNo) {
        outside();
        var source = source(reference);
        positive(source, reader.current(reference(source)));
        IndustryChannelOrderFacts.key(reservationRef, 32);
        IndustryChannelOrderFacts.key(subOrderNo, 32);
        var data = data(source);
        var branches = new ArrayList<Branch>();
        for (var component : data.components()) {
            var found =
                    materials.getSkuMaterials(
                            source.getEntityNo(), source.getStoreNo(), List.of(component.skuNo()));
            require(
                    found != null
                            && found.size() == 1
                            && component.skuNo().equals(found.getFirst().skuNo()));
            var material = found.getFirst().material();
            require(
                    MaterialCatalogFacts.validFact(material)
                            && source.getEntityNo().equals(material.entityNo())
                            && component.unit().equals(material.baseUom())
                            && (component.physicalQuantity().stripTrailingZeros().scale() <= 0
                                    || Integer.valueOf(1).equals(material.weighed())));
            var leaf =
                    new Leaf(
                            PricingSnapshotHash.of(List.of(component.componentRef(), "SKU")),
                            component.skuNo(),
                            component.physicalQuantity(),
                            material.baseUom(),
                            material.definitionHash());
            branches.add(
                    new Branch(
                            component.componentRef(),
                            subOrderNo,
                            source.getStoreNo(),
                            "FINISHED_SKU",
                            component.physicalQuantity(),
                            component.unit(),
                            List.of(leaf),
                            null));
        }
        var scope =
                new InventoryReservationPort.Scope("MAIN", source.getEntityNo(), reservationRef);
        var raw = new Plan(scope, reservationRef, null, branches);
        return InventoryDemandPlanFacts.validate(
                new Plan(scope, reservationRef, InventoryDemandPlanFacts.hash(raw), branches));
    }

    @Override
    public CollectionFacts collection(IndustryChannelOrderPort.Reference reference) {
        outside();
        var source = source(reference);
        var current = reader.current(reference(source));
        positive(source, current);
        var s = current.current();
        var d = s.document();
        var collection = collection(d);
        return new CollectionFacts(
                reference,
                d.externalOrderRef(),
                s.documentHash(),
                ChannelOrderEconomicPolicy.fingerprint(d),
                collection,
                collection == Collection.PLATFORM_COLLECTED
                        ? d.totals().customerPayableMinor()
                        : null,
                d.currency(),
                d.originalPaymentRef(),
                s.observedAt(),
                s.validUntil(),
                s.originalReceiptRef());
    }

    @Override
    public PlatformAcceptance acceptance(IndustryChannelOrderPort.Reference reference) {
        outside();
        var source = source(reference);
        var current = reader.current(reference(source));
        positive(source, current);
        var s = current.current();
        require(s.document().state() == ChannelOrderGateway.State.ACCEPTED);
        return new PlatformAcceptance(
                reference,
                s.document().externalOrderRef(),
                s.documentHash(),
                ChannelOrderEconomicPolicy.fingerprint(s.document()),
                s.originalReceiptRef(),
                null,
                s.observedAt(),
                s.validUntil(),
                s.originalReceiptRef());
    }

    @Override
    public Withdrawal withdrawal(IndustryChannelOrderPort.Reference reference) {
        outside();
        var source = source(reference);
        var current = reader.current(reference(source));
        require(
                current.current().document().state() == ChannelOrderGateway.State.CANCELLED
                        && source.getExternalOrderRef()
                                .equals(current.current().document().externalOrderRef()));
        var s = current.current();
        return new Withdrawal(
                reference,
                "PLATFORM_CANCELLED",
                s.originalReceiptRef(),
                s.observedAt(),
                s.validUntil(),
                s.originalReceiptRef());
    }

    private RetailChannelOrderSource register(
            ChannelOrderIngestPort.Reference reference,
            RetailChannelSourceReader.Original original) {
        ensureGuard(reference);
        try {
            return atomic(
                    () -> {
                        var guard = guard(reference, true);
                        require(guard != null && "ACTIVE".equals(guard.getStatus()));
                        var prior = find(reference, true);
                        if (prior != null) return prior;
                        var evidence = original.evidence();
                        var command = evidence.original();
                        var doc = command.original().document();
                        IndustryChannelOrderFacts.freshness(
                                evidence.current().observedAt(), original.validUntil(), clock);
                        require(
                                evidence.current().document().state()
                                                != ChannelOrderGateway.State.CANCELLED
                                        && evidence.current().document().state()
                                                != ChannelOrderGateway.State.COMPLETED
                                        && ChannelOrderEconomicPolicy.sameBusiness(
                                                doc, evidence.current().document()));
                        var row = new RetailChannelOrderSource();
                        stamp(row);
                        row.setEntityNo(reference.entityNo());
                        row.setStoreNo(reference.storeNo());
                        row.setSourceNo(BizKey.next("RCS"));
                        row.setImportNo(reference.importNo());
                        row.setOriginalDocumentHash(reference.documentHash());
                        row.setFinancialHash(ChannelOrderEconomicPolicy.fingerprint(doc));
                        row.setChannelStoreKey(evidence.routing().channelStoreKey());
                        row.setExternalOrderKey(PricingSnapshotHash.of(doc.externalOrderRef()));
                        row.setExternalOrderRef(doc.externalOrderRef());
                        row.setSourceVersion(1L);
                        var party = party(reference, original);
                        row.setPartyNo(party.getPartyNo());
                        var components = new ArrayList<Component>();
                        var invoices = new ArrayList<InvoiceLine>();
                        for (var line : original.lines()) {
                            var invoice = line.invoice();
                            var quantity = new BigDecimal(invoice.quantity());
                            String lineRef = BizKey.next("RCSL");
                            String componentRef =
                                    PricingSnapshotHash.of(
                                            List.of(row.getSourceNo(), lineRef, invoice.lineRef()));
                            String basis =
                                    PricingSnapshotHash.of(
                                            List.of(
                                                    "RETAIL_ORIGINAL_INVOICE_V1",
                                                    reference.documentHash(),
                                                    original.publication()
                                                            .originalPublicationReceiptRef(),
                                                    invoice));
                            components.add(
                                    new Component(
                                            invoice.lineRef(),
                                            null,
                                            "MAIN",
                                            lineRef,
                                            componentRef,
                                            "FINISHED_SKU",
                                            line.goodsNo(),
                                            line.skuNo(),
                                            line.offer().name(),
                                            line.offer().images().isEmpty()
                                                    ? null
                                                    : line.offer().images().getFirst().url(),
                                            line.variant().name(),
                                            quantity,
                                            line.unit(),
                                            invoice.grossExTaxMinor(),
                                            invoice.merchantDiscountExTaxMinor(),
                                            invoice.taxMinor(),
                                            invoice.netMinor(),
                                            basis));
                            invoices.add(
                                    new InvoiceLine(
                                            invoice.lineRef(),
                                            quantity,
                                            line.unit(),
                                            invoice.grossExTaxMinor(),
                                            invoice.merchantDiscountExTaxMinor(),
                                            invoice.taxMinor(),
                                            invoice.netMinor()));
                        }
                        var route = evidence.routing();
                        var customer = doc.customer();
                        var totals = doc.totals();
                        var data =
                                new Data(
                                        new IndustryChannelOrderPort.Scope(
                                                "MAIN", reference.entityNo(), reference.storeNo()),
                                        "RETAIL",
                                        row.getSourceNo(),
                                        1L,
                                        reference.importNo(),
                                        new Channel(
                                                route.providerCode(),
                                                route.profileNo(),
                                                route.channelStoreKey(),
                                                doc.externalAccountRef(),
                                                doc.externalStoreRef(),
                                                route.market()),
                                        doc.externalOrderRef(),
                                        reference.documentHash(),
                                        command.original().originalReceiptRef(),
                                        row.getFinancialHash(),
                                        doc.currency(),
                                        collection(doc),
                                        doc.originalPaymentRef(),
                                        fulfillment(doc.fulfillment()),
                                        new Party(
                                                party.getPartyNo(),
                                                customer == null
                                                        ? null
                                                        : customer.externalCustomerRef(),
                                                customer == null ? null : customer.displayName()),
                                        customer == null
                                                ? null
                                                : new Delivery(
                                                        customer.displayName(),
                                                        customer.phone(),
                                                        customer.address(),
                                                        null),
                                        doc.orderedAt(),
                                        invoices,
                                        components,
                                        new Totals(
                                                sum(invoices, 0),
                                                sum(invoices, 1),
                                                sum(invoices, 2),
                                                totals.merchantChargesMinor(),
                                                totals.merchantReceivableMinor(),
                                                totals.platformSubsidyMinor(),
                                                totals.platformFeesMinor(),
                                                totals.deliveryFeesMinor(),
                                                totals.customerPayableMinor()));
                        row.setSourceHash(PricingSnapshotHash.of(data));
                        row.setSourceSnapshot(json.writeValueAsString(data));
                        row.setOriginSnapshot(json.writeValueAsString(original));
                        row.setStatus("REGISTERED");
                        IndustryChannelOrderFacts.validate(
                                coreReference(row),
                                new Facts(
                                        coreReference(row),
                                        data,
                                        evidence.current().observedAt(),
                                        original.validUntil(),
                                        evidence.current().originalReceiptRef()),
                                clock);
                        sources.insert(row);
                        IndustryChannelOrderFacts.freshness(
                                evidence.current().observedAt(), original.validUntil(), clock);
                        return row;
                    });
        } catch (DuplicateKeyException concurrent) {
            var prior = query(() -> find(reference, false));
            if (prior == null) throw conflict();
            return prior;
        }
    }

    private RetailChannelParty party(
            ChannelOrderIngestPort.Reference reference,
            RetailChannelSourceReader.Original original) {
        var d = original.evidence().original().original().document();
        var c = d.customer();
        String external = c == null ? null : c.externalCustomerRef();
        String key =
                PricingSnapshotHash.of(
                        external == null
                                ? List.of("ORDER", d.externalOrderRef())
                                : List.of("CUSTOMER", external));
        String channel = original.evidence().routing().channelStoreKey();
        var prior =
                parties.selectOne(
                        Wrappers.<RetailChannelParty>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", reference.entityNo())
                                .eq("store_no", reference.storeNo())
                                .eq("channel_store_key", channel)
                                .eq("party_key", key)
                                .last("FOR UPDATE"));
        if (prior != null) return prior;
        var party = new RetailChannelParty();
        stamp(party);
        party.setEntityNo(reference.entityNo());
        party.setStoreNo(reference.storeNo());
        party.setPartyNo(BizKey.next("RCP"));
        party.setChannelStoreKey(channel);
        party.setPartyKey(key);
        party.setExternalCustomerRef(external);
        party.setExternalOrderRef(d.externalOrderRef());
        party.setDisplayName(c == null ? null : c.displayName());
        parties.insert(party);
        return party;
    }

    private void persistPreparation(
            ChannelOrderIngestPort.Reference reference,
            IndustryChannelOrderPort.Preparation actual) {
        atomic(
                () -> {
                    var guard = guard(reference, true);
                    require(guard != null && "ACTIVE".equals(guard.getStatus()));
                    var row = required(reference, true);
                    require(coreReference(row).equals(actual.reference()));
                    if (actual.orderNo() != null)
                        require(actual.subOrderNo() != null && actual.quoteNo() != null);
                    if (row.getAcceptanceSnapshot() != null) return null;
                    require(
                            sources.update(
                                            Wrappers.<RetailChannelOrderSource>update()
                                                    .eq("id", row.getId())
                                                    .eq("version", row.getVersion())
                                                    .set(
                                                            "preparation_snapshot",
                                                            json.writeValueAsString(actual))
                                                    .set(
                                                            "status",
                                                            actual.status()
                                                                            == IndustryChannelOrderPort
                                                                                    .Status
                                                                                    .CANCELLED
                                                                    ? "CANCELLED"
                                                                    : "PREPARED")
                                                    .setSql("version=version+1"))
                                    == 1);
                    return null;
                });
    }

    private Accepted accepted(
            ChannelOrderIngestPort.Reference reference, RetailChannelOrderSource source) {
        var actual =
                json.readValue(
                        source.getAcceptanceSnapshot(), IndustryChannelOrderPort.Acceptance.class);
        require(
                coreReference(source).equals(actual.reference())
                        && source.getAcceptanceReceiptRef().equals(actual.acceptanceReceiptRef()));
        validateAllocations(source, actual);
        // The retail business document is the genuine original core store order, not a generated
        // second order.
        return new Accepted(
                reference,
                actual.orderNo(),
                actual.subOrderNo(),
                actual.acceptanceReceiptRef(),
                actual.allocations().stream()
                        .map(
                                a ->
                                        new LineAllocation(
                                                a.externalLineRef(),
                                                a.parentExternalLineRef(),
                                                a.externalKind(),
                                                a.componentRef(),
                                                a.sourceLineRef(),
                                                a.coreItemRef(),
                                                a.physicalQuantity(),
                                                a.unit(),
                                                a.amountMinor(),
                                                a.currency(),
                                                a.receiptRef()))
                        .toList());
    }

    private void validateAllocations(
            RetailChannelOrderSource source, IndustryChannelOrderPort.Acceptance acceptance) {
        var data = data(source);
        require(acceptance.allocations().size() == data.components().size());
        var seen = new HashSet<String>();
        for (var a : acceptance.allocations()) {
            require(a != null && seen.add(a.componentRef()));
            IndustryChannelOrderFacts.key(a.coreItemRef(), 64);
            IndustryChannelOrderFacts.key(a.receiptRef(), 256);
            var matches =
                    data.components().stream()
                            .filter(c -> a.componentRef().equals(c.componentRef()))
                            .toList();
            require(matches.size() == 1);
            var c = matches.getFirst();
            require(
                    c.externalLineRef().equals(a.externalLineRef())
                            && a.parentExternalLineRef() == null
                            && c.externalKind().equals(a.externalKind())
                            && c.sourceLineRef().equals(a.sourceLineRef())
                            && c.physicalQuantity().compareTo(new BigDecimal(a.physicalQuantity()))
                                    == 0
                            && c.unit().equals(a.unit())
                            && c.netMinor() == a.amountMinor()
                            && data.currency().equals(a.currency())
                            && a.coreItemRef() != null
                            && a.receiptRef() != null
                            && a.orderItemId() > 0);
        }
    }

    private void positive(
            RetailChannelOrderSource source, ChannelOrderSourceEvidencePort.Evidence current) {
        var guard = query(() -> guard(reference(source), false));
        require(
                guard != null
                        && "ACTIVE".equals(guard.getStatus())
                        && !"CANCELLED".equals(source.getStatus())
                        && source.getExternalOrderRef()
                                .equals(current.current().document().externalOrderRef())
                        && source.getFinancialHash()
                                .equals(
                                        ChannelOrderEconomicPolicy.fingerprint(
                                                current.current().document()))
                        && current.current().document().state()
                                != ChannelOrderGateway.State.CANCELLED
                        && current.current().document().state()
                                != ChannelOrderGateway.State.COMPLETED);
    }

    private RetailChannelOrderSource source(IndustryChannelOrderPort.Reference reference) {
        IndustryChannelOrderFacts.reference(reference);
        require("RETAIL".equals(reference.sourceSystem()));
        return query(
                () -> {
                    var row =
                            sources.selectOne(
                                    Wrappers.<RetailChannelOrderSource>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("entity_no", reference.scope().entityNo())
                                            .eq("store_no", reference.scope().storeNo())
                                            .eq("source_no", reference.sourceNo()));
                    require(row != null && coreReference(row).equals(reference));
                    return row;
                });
    }

    private Data data(RetailChannelOrderSource source) {
        var data = json.readValue(source.getSourceSnapshot(), Data.class);
        require(source.getSourceHash().equals(PricingSnapshotHash.of(data)));
        return data;
    }

    private IndustryChannelOrderPort.Reference coreReference(RetailChannelOrderSource source) {
        return new IndustryChannelOrderPort.Reference(
                new IndustryChannelOrderPort.Scope(
                        "MAIN", source.getEntityNo(), source.getStoreNo()),
                "RETAIL",
                source.getSourceNo(),
                source.getSourceVersion(),
                source.getSourceHash(),
                source.getImportNo());
    }

    private ChannelOrderIngestPort.Reference reference(RetailChannelOrderSource source) {
        return new ChannelOrderIngestPort.Reference(
                source.getImportNo(),
                source.getEntityNo(),
                source.getStoreNo(),
                source.getOriginalDocumentHash());
    }

    private RetailChannelOrderSource find(
            ChannelOrderIngestPort.Reference reference, boolean lock) {
        var q =
                Wrappers.<RetailChannelOrderSource>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", reference.entityNo())
                        .eq("store_no", reference.storeNo())
                        .eq("import_no", reference.importNo());
        if (lock) q.last("FOR UPDATE");
        var row = sources.selectOne(q);
        if (row != null) require(reference.documentHash().equals(row.getOriginalDocumentHash()));
        return row;
    }

    private RetailChannelOrderSource required(
            ChannelOrderIngestPort.Reference reference, boolean lock) {
        var row = find(reference, lock);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private RetailChannelImportGuard guard(
            ChannelOrderIngestPort.Reference reference, boolean lock) {
        var q =
                Wrappers.<RetailChannelImportGuard>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", reference.entityNo())
                        .eq("store_no", reference.storeNo())
                        .eq("import_no", reference.importNo());
        if (lock) q.last("FOR UPDATE");
        var row = guards.selectOne(q);
        if (row != null) require(reference.documentHash().equals(row.getOriginalDocumentHash()));
        return row;
    }

    private void ensureGuard(ChannelOrderIngestPort.Reference reference) {
        try {
            atomic(
                    () -> {
                        if (guard(reference, true) != null) return null;
                        var row = new RetailChannelImportGuard();
                        stamp(row);
                        row.setEntityNo(reference.entityNo());
                        row.setStoreNo(reference.storeNo());
                        row.setGuardNo(BizKey.next("RCIG"));
                        row.setImportNo(reference.importNo());
                        row.setOriginalDocumentHash(reference.documentHash());
                        row.setStatus("ACTIVE");
                        guards.insert(row);
                        return null;
                    });
        } catch (DuplicateKeyException concurrent) {
            require(query(() -> guard(reference, false)) != null);
        }
    }

    private void stamp(BaseEntity row) {
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        row.setTenantNo("MAIN");
        row.setVersion(0L);
        row.setDeleted(0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setCreatedBy("SYSTEM");
        row.setUpdatedBy("SYSTEM");
    }

    private <T> T query(Supplier<T> work) {
        return DataScopeContext.executeWithoutScope(() -> read.execute(tx -> work.get()));
    }

    private <T> T atomic(Supplier<T> work) {
        return DataScopeContext.executeWithoutScope(() -> write.execute(tx -> work.get()));
    }

    private static void scope(ChannelOrderIngestPort.Reference reference) {
        require(reference != null);
        IndustryChannelOrderFacts.key(reference.entityNo(), 32);
        IndustryChannelOrderFacts.key(reference.storeNo(), 32);
        IndustryChannelOrderFacts.key(reference.importNo(), 32);
        require(IndustryChannelOrderFacts.hash(reference.documentHash()));
    }

    private static Prepared cancelledPreparation(ChannelOrderIngestPort.Reference reference) {
        return new Prepared(reference, Status.CANCELLED, null, null);
    }

    private static Collection collection(ChannelOrderGateway.Document d) {
        return switch (d.paymentState()) {
            case PLATFORM_PAID -> Collection.PLATFORM_COLLECTED;
            case CASH_ON_DELIVERY -> Collection.CASH_ON_DELIVERY;
            case UNPAID -> Collection.UNPAID;
        };
    }

    private static Fulfillment fulfillment(ChannelOrderGateway.Fulfillment f) {
        return switch (f) {
            case INSTANT_DELIVERY -> Fulfillment.CHANNEL_DELIVERY;
            case PICKUP -> Fulfillment.STORE_PICKUP;
            case SHIPMENT -> Fulfillment.SHIPMENT;
            case VOUCHER -> throw conflict();
        };
    }

    private static long sum(List<InvoiceLine> lines, int field) {
        return lines.stream()
                .mapToLong(
                        l ->
                                switch (field) {
                                    case 0 -> l.grossExTaxMinor();
                                    case 1 -> l.merchantDiscountExTaxMinor();
                                    default -> l.taxMinor();
                                })
                .reduce(0, Math::addExact);
    }

    private static void outside() {
        require(!TransactionSynchronizationManager.isActualTransactionActive());
    }

    private static BizException conflict() {
        return BizException.of(ErrorCode.CONFLICT);
    }

    private static void require(boolean valid) {
        if (!valid) throw conflict();
    }
}
