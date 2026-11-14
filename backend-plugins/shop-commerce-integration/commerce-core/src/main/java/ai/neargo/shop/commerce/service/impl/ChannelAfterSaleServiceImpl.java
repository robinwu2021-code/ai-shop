package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.audit;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.now;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.require;

import ai.neargo.shop.commerce.dto.AfterSaleContracts;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.commerce.entity.CimAfterSaleLineLink;
import ai.neargo.shop.commerce.entity.CimAfterSaleLink;
import ai.neargo.shop.commerce.entity.CimAfterSaleRevision;
import ai.neargo.shop.commerce.entity.CimAuthorizationRevision;
import ai.neargo.shop.commerce.entity.CimOperation;
import ai.neargo.shop.commerce.entity.CimOperationAttempt;
import ai.neargo.shop.commerce.entity.CimOrderLineLink;
import ai.neargo.shop.commerce.entity.CimOrderLink;
import ai.neargo.shop.commerce.mapper.AfterSaleMappers.LineMapper;
import ai.neargo.shop.commerce.mapper.AfterSaleMappers.LinkMapper;
import ai.neargo.shop.commerce.mapper.AfterSaleMappers.RevisionMapper;
import ai.neargo.shop.commerce.policy.ChannelAfterSalePolicy;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.commerce.policy.ChannelOrderEconomicPolicy;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.commerce.service.ChannelAfterSaleService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelAfterSaleFundsPort;
import ai.neargo.shop.spi.commerce.ChannelAfterSaleGateway;
import ai.neargo.shop.spi.commerce.ChannelAfterSaleGateway.Document;
import ai.neargo.shop.spi.commerce.ChannelAfterSaleGateway.Publication;
import ai.neargo.shop.spi.commerce.ChannelAfterSaleGateway.ResultStatus;
import ai.neargo.shop.spi.commerce.ChannelAfterSaleGateway.Snapshot;
import ai.neargo.shop.spi.commerce.ChannelAfterSaleSourcePort;
import ai.neargo.shop.spi.commerce.ChannelInboundHandler;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;
import ai.neargo.shop.spi.commerce.ChannelRecoveryPort;
import ai.neargo.shop.spi.commerce.CommerceGateway.Capability;
import ai.neargo.shop.spi.trade.IndustryChannelAfterSalePort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Durable supplier workflow consumes genuine original decisions; no supplier result mutates local
 * funds or inventory.
 */
public final class ChannelAfterSaleServiceImpl
        implements ChannelAfterSaleService, ChannelInboundHandler, ChannelRecoveryPort {
    private final ChannelOrderServiceImpl orders;
    private final LinkMapper links;
    private final RevisionMapper revisions;
    private final LineMapper lines;
    private final Supplier<List<ChannelAfterSaleSourcePort>> sources;
    private final Supplier<List<ChannelAfterSaleFundsPort>> funds;
    private final Map<String, ChannelAfterSaleGateway> gateways;
    private final Clock clock;

    public ChannelAfterSaleServiceImpl(
            ChannelOrderServiceImpl orders,
            LinkMapper links,
            RevisionMapper revisions,
            LineMapper lines,
            Supplier<List<ChannelAfterSaleSourcePort>> sources,
            Supplier<List<ChannelAfterSaleFundsPort>> funds,
            List<ChannelAfterSaleGateway> gateways,
            Clock clock) {
        this.orders = orders;
        this.links = links;
        this.revisions = revisions;
        this.lines = lines;
        this.sources = sources;
        this.funds = funds;
        this.clock = clock;
        var index = new HashMap<String, ChannelAfterSaleGateway>();
        for (var gateway : gateways)
            require(index.putIfAbsent(gateway.profileNo(), gateway) == null);
        this.gateways = Map.copyOf(index);
    }

    private record Mapped(
            IndustryChannelAfterSalePort.Line original, CimOrderLineLink allocation) {}

    private record Claim(
            CimAfterSaleLink link,
            CimOperation operation,
            Publication publication,
            long attempt,
            long fence,
            boolean query,
            ChannelAfterSaleGateway gateway) {}

    private record Fresh(
            ChannelAfterSaleSourcePort.Facts source, ChannelAfterSaleFundsPort.Facts funds) {}

    @Override
    public String profileNo() {
        return "ORIGINAL_AFTER_SALE_ROUTER";
    }

    @Override
    public boolean acceptsProfile(String profile) {
        return gateways.containsKey(profile);
    }

    @Override
    public boolean supports(String type) {
        return Set.of("AFTER_SALE_CREATED", "AFTER_SALE_UPDATED", "AFTER_SALE_REFUNDED")
                .contains(type);
    }

    @Override
    public String workflow() {
        return "CHANNEL_AFTER_SALE";
    }

    @Override
    public ChannelInboundHandler.Result handle(Event event) {
        outside();
        var gateway = gateways.get(event.profileNo());
        if (gateway == null || !gateway.capabilities().contains(Capability.AFTER_SALE))
            return new ChannelInboundHandler.Result(Outcome.WAITING_OWNER, null);
        var scope =
                new ChannelOrderGateway.Query(
                        event.entityNo(),
                        event.storeNo(),
                        event.connectionNo(),
                        event.profileNo(),
                        event.externalAccountRef(),
                        event.externalStoreRef(),
                        event.externalOrderRef(),
                        event.externalRevision(),
                        event.authorizationNo(),
                        event.credentialRef());
        var snapshots = gateway.readAfterSales(scope);
        require(snapshots != null && snapshots.size() <= 100);
        for (var snapshot : snapshots) ChannelAfterSalePolicy.snapshot(scope, snapshot, clock);
        return orders.db.commands.atomic(
                () -> {
                    var c =
                            orders.db.connection(
                                    event.entityNo(),
                                    event.storeNo(),
                                    event.connectionNo(),
                                    true,
                                    false);
                    require(
                            c.account().getExternalAccountRef().equals(event.externalAccountRef())
                                    && c.connection()
                                            .getExternalStoreRef()
                                            .equals(event.externalStoreRef())
                                    && c.connection().getProfileNo().equals(event.profileNo()));
                    var order =
                            orders.orders.selectOne(
                                    Wrappers.<CimOrderLink>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("entity_no", event.entityNo())
                                            .eq("store_no", event.storeNo())
                                            .eq("connection_no", event.connectionNo())
                                            .eq(
                                                    "external_order_key",
                                                    ChannelIdentity.hash(event.externalOrderRef()))
                                            .last("FOR UPDATE"));
                    if (order == null)
                        return new ChannelInboundHandler.Result(Outcome.WAITING_OWNER, null);
                    boolean conflict = false;
                    String result = order.getChannelOrderNo();
                    var seen = new HashSet<String>();
                    for (var snapshot : snapshots) {
                        require(seen.add(snapshot.document().externalCaseRef()));
                        var captured = capture(event, order, scope, snapshot);
                        result = captured.resultRef();
                        conflict |= captured.outcome() == Outcome.CONFLICT;
                    }
                    return new ChannelInboundHandler.Result(
                            conflict ? Outcome.CONFLICT : Outcome.PROCESSED, result);
                });
    }

    private ChannelInboundHandler.Result capture(
            Event event, CimOrderLink order, ChannelOrderGateway.Query scope, Snapshot snapshot) {
        ChannelAfterSalePolicy.snapshot(scope, snapshot, clock);
        var d = snapshot.document();
        var row =
                links.selectOne(
                        Wrappers.<CimAfterSaleLink>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", event.entityNo())
                                .eq("store_no", event.storeNo())
                                .eq("channel_order_no", order.getChannelOrderNo())
                                .eq("external_case_key", ChannelIdentity.hash(d.externalCaseRef()))
                                .last("FOR UPDATE"));
        if (row == null) {
            row = audit(new CimAfterSaleLink(), "SYSTEM");
            row.setCaseNo(BizKey.next("CIAS"));
            row.setEntityNo(event.entityNo());
            row.setStoreNo(event.storeNo());
            row.setConnectionNo(event.connectionNo());
            row.setConnectionRevisionNo(event.connectionRevisionNo());
            row.setChannelOrderNo(order.getChannelOrderNo());
            row.setExternalCaseKey(ChannelIdentity.hash(d.externalCaseRef()));
            row.setExternalCaseRef(d.externalCaseRef());
            row.setActiveRevisionNo(BizKey.next("CIASV"));
            row.setSupplierState(d.state().name());
            row.setType(d.type().name());
            row.setCurrency(d.currency());
            row.setMerchantRefundMinor(d.merchantRefundMinor());
            row.setRequestedAt(LocalDateTime.ofInstant(d.requestedAt(), ZoneOffset.UTC));
            row.setDecisionStatus("WAITING_ORIGINAL_DECISION");
            row.setLocalFundsStatus("UNKNOWN");
            row.setLocalInventoryStatus("UNKNOWN");
            links.insert(row);
            append(row, event, snapshot, row.getActiveRevisionNo());
            return new ChannelInboundHandler.Result(Outcome.PROCESSED, row.getCaseNo());
        }
        require(
                row.getConnectionNo().equals(event.connectionNo())
                        && row.getExternalCaseRef().equals(d.externalCaseRef()));
        var same =
                revisions.selectList(
                        Wrappers.<CimAfterSaleRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", event.entityNo())
                                .eq("case_no", row.getCaseNo())
                                .eq("external_revision", d.revisionRef()));
        if (same.stream().anyMatch(r -> r.getContentHash().equals(snapshot.contentHash())))
            return new ChannelInboundHandler.Result(Outcome.PROCESSED, row.getCaseNo());
        String next = BizKey.next("CIASV");
        append(row, event, snapshot, next);
        var current = revision(row);
        if (!same.isEmpty()
                || d.sequence().equals(current.getExternalSequence())
                || !d.type().name().equals(row.getType())
                || !d.currency().equals(row.getCurrency())
                || !d.requestedAt().equals(document(row).requestedAt())) {
            links.update(
                    Wrappers.<CimAfterSaleLink>update()
                            .eq("tenant_no", "MAIN")
                            .eq("id", row.getId())
                            .set("decision_status", "CONFLICT")
                            .setSql("version=version+1"));
            return new ChannelInboundHandler.Result(Outcome.CONFLICT, row.getCaseNo());
        }
        if (d.sequence() < current.getExternalSequence())
            return new ChannelInboundHandler.Result(Outcome.IGNORED, row.getCaseNo());
        boolean terminal =
                Set.of("REFUNDED", "REJECTED", "CLOSED").contains(row.getSupplierState());
        if (terminal && !d.state().name().equals(row.getSupplierState())) {
            links.update(
                    Wrappers.<CimAfterSaleLink>update()
                            .eq("tenant_no", "MAIN")
                            .eq("id", row.getId())
                            .set("decision_status", "CONFLICT")
                            .setSql("version=version+1"));
            return new ChannelInboundHandler.Result(Outcome.CONFLICT, row.getCaseNo());
        }
        links.update(
                Wrappers.<CimAfterSaleLink>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", row.getId())
                        .eq("version", row.getVersion())
                        .set("active_revision_no", next)
                        .set("supplier_state", d.state().name())
                        .set("merchant_refund_minor", d.merchantRefundMinor())
                        .setSql("version=version+1"));
        return new ChannelInboundHandler.Result(Outcome.PROCESSED, row.getCaseNo());
    }

    private void append(CimAfterSaleLink row, Event event, Snapshot snapshot, String revision) {
        var value = audit(new CimAfterSaleRevision(), "SYSTEM");
        value.setRevisionNo(revision);
        value.setEntityNo(row.getEntityNo());
        value.setCaseNo(row.getCaseNo());
        value.setExternalRevision(snapshot.document().revisionRef());
        value.setExternalSequence(snapshot.document().sequence());
        value.setContentHash(snapshot.contentHash());
        value.setPayloadJson(orders.db.json.writeValueAsString(snapshot.document()));
        value.setOriginalReceiptRef(snapshot.originalReceiptRef());
        value.setOriginEventNo(event.eventNo());
        revisions.insert(value);
    }

    @Override
    public OperationVO applyDecision(
            String entity,
            String store,
            String actor,
            String caseNo,
            AfterSaleContracts.ApplyDecision request,
            String key) {
        outside();
        require(request != null && request.version() != null && request.version() >= 0);
        CommerceValidation.text(caseNo, 32);
        CommerceValidation.text(request.sourceDecisionRef(), 32);
        CommerceValidation.text(request.sourceRevision(), 128);
        var replay =
                orders.db.commands.replay(
                        entity,
                        store,
                        actor,
                        "AFTER_SALE_DECISION",
                        key,
                        List.of(caseNo, request),
                        OperationVO.class);
        if (replay.isPresent()) return replay.get();
        var sourceQuery =
                orders.db.commands.query(
                        () -> {
                            var row = scoped(entity, store, caseNo, false);
                            var order =
                                    orders.scoped(entity, store, row.getChannelOrderNo(), false);
                            business(order);
                            return new ChannelAfterSaleSourcePort.Query(
                                    entity,
                                    store,
                                    order.getCoreOrderRef(),
                                    row.getExternalCaseRef(),
                                    request.sourceDecisionRef(),
                                    request.sourceRevision());
                        });
        var source = captureSource(sourceQuery);
        return orders.db.commands.execute(
                entity,
                store,
                actor,
                "AFTER_SALE_DECISION",
                key,
                List.of(caseNo, request),
                OperationVO.class,
                () -> persist(entity, store, actor, caseNo, request, source),
                OperationVO::operationNo);
    }

    private ChannelAfterSaleSourcePort.Facts captureSource(ChannelAfterSaleSourcePort.Query query) {
        outside();
        var providers = sources.get();
        var matching =
                providers.stream()
                        .filter(s -> s.owner().name().equals(sourceOwner(query)))
                        .toList();
        require(matching.size() == 1);
        var facts = matching.getFirst().capture(query);
        ChannelAfterSalePolicy.source(query, facts, clock);
        return facts;
    }

    private String sourceOwner(ChannelAfterSaleSourcePort.Query q) {
        return orders.db.commands.query(
                () -> {
                    var rows =
                            orders.orders.selectList(
                                    Wrappers.<CimOrderLink>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("entity_no", q.entityNo())
                                            .eq("store_no", q.storeNo())
                                            .eq("core_order_ref", q.coreOrderNo())
                                            .last("LIMIT 2"));
                    require(rows.size() == 1);
                    return rows.getFirst().getSourceOwner();
                });
    }

    private OperationVO persist(
            String entity,
            String store,
            String actor,
            String caseNo,
            AfterSaleContracts.ApplyDecision request,
            ChannelAfterSaleSourcePort.Facts source) {
        var before = scoped(entity, store, caseNo, false);
        var observedOrder = orders.scoped(entity, store, before.getChannelOrderNo(), false);
        var c = orders.db.connection(entity, store, observedOrder.getConnectionNo(), true, false);
        var order = orders.scoped(entity, store, before.getChannelOrderNo(), true);
        business(order);
        var row = scoped(entity, store, caseNo, true);
        if (row.getOperationNo() != null) {
            require(
                    request.sourceDecisionRef().equals(row.getSourceDecisionRef())
                            && request.sourceRevision().equals(row.getSourceRevision()));
            return ChannelOperationServiceImpl.view(operation(entity, row.getOperationNo()), null);
        }
        require(
                row.getVersion().equals(request.version())
                        && !"CONFLICT".equals(row.getDecisionStatus())
                        && Set.of("REQUESTED", "RETURN_IN_PROGRESS")
                                .contains(row.getSupplierState()));
        var sourceQuery =
                new ChannelAfterSaleSourcePort.Query(
                        entity,
                        store,
                        order.getCoreOrderRef(),
                        row.getExternalCaseRef(),
                        request.sourceDecisionRef(),
                        request.sourceRevision());
        ChannelAfterSalePolicy.source(sourceQuery, source, clock);
        require(order.getSourceOwner().equals(source.decision().original().sourceSystem()));
        var original =
                orders.db.json.readValue(
                        order.getImportSnapshot(), ChannelOrderIngestPort.Command.class);
        require(
                source.decision().externalOrderRef().equals(order.getExternalOrderRef())
                        && source.decision()
                                .externalFinancialHash()
                                .equals(
                                        ChannelOrderEconomicPolicy.fingerprint(
                                                original.original().document())));
        var supplier = document(row);
        require(supplier.currency().equals(source.decision().currency()));
        var mapped = map(order, original, supplier, source);
        var grant =
                grant(entity, c.account().getAccountNo(), c.account().getActiveAuthorizationNo());
        active(
                c.account().getStatus(),
                c.connection().getStatus(),
                c.connection().getActiveRevisionNo(),
                order,
                grant);
        var scope =
                new ChannelOrderGateway.Query(
                        entity,
                        store,
                        order.getConnectionNo(),
                        c.connection().getProfileNo(),
                        c.account().getExternalAccountRef(),
                        c.connection().getExternalStoreRef(),
                        order.getExternalOrderRef(),
                        original.original().document().revisionRef(),
                        grant.getAuthorizationNo(),
                        grant.getCredentialRef());
        var gateway = gateways.get(scope.profileNo());
        var action = ChannelAfterSaleGateway.Action.valueOf(source.decision().action().name());
        require(
                gateway != null
                        && gateway.capabilities().contains(Capability.AFTER_SALE)
                        && gateway.supportsAfterSale(supplier.type(), action));
        var publicationLines = aggregate(mapped);
        boolean refund = action != ChannelAfterSaleGateway.Action.REJECT;
        if (refund)
            require(source.decision().refundCeilingMinor() == supplier.merchantRefundMinor());
        String number = BizKey.next("CIOP");
        var publication =
                new Publication(
                        number,
                        scope,
                        row.getExternalCaseRef(),
                        supplier.revisionRef(),
                        revision(row).getContentHash(),
                        original.original().documentHash(),
                        request.sourceDecisionRef(),
                        request.sourceRevision(),
                        source.decisionHash(),
                        action,
                        source.decision().reasonCode(),
                        supplier.currency(),
                        refund ? source.decision().refundCeilingMinor() : 0,
                        publicationLines,
                        null);
        var op = audit(new CimOperation(), actor);
        op.setOperationNo(number);
        op.setEntityNo(entity);
        op.setStoreNo(store);
        op.setConnectionNo(order.getConnectionNo());
        op.setDestination("CHANNEL");
        op.setOperationType("AFTER_SALE_DECISION");
        op.setBusinessRef(caseNo);
        op.setBusinessRevision(request.sourceRevision());
        op.setSemanticKey(ChannelIdentity.hash(caseNo));
        op.setRequestHash(PricingSnapshotHash.of(publication));
        op.setRequestRef("AFTER_SALE:" + caseNo);
        op.setStatus(refund ? "WAITING_FUNDS" : "QUEUED");
        op.setCurrentAttemptNo(0L);
        op.setFence(0L);
        op.setNextRecoveryAt(now());
        op.setAccountNo(c.account().getAccountNo());
        op.setProfileNo(scope.profileNo());
        op.setTargetScopeKind("CONNECTION");
        op.setTargetScopeKey(ChannelIdentity.hash(order.getChannelStoreKey()));
        orders.db.operations.insert(op);
        require(
                links.update(
                                Wrappers.<CimAfterSaleLink>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("id", row.getId())
                                        .eq("version", row.getVersion())
                                        .set("source_decision_ref", request.sourceDecisionRef())
                                        .set("source_revision", request.sourceRevision())
                                        .set("source_decision_hash", source.decisionHash())
                                        .set(
                                                "source_decision_snapshot",
                                                orders.db.json.writeValueAsString(
                                                        source.decision()))
                                        .set("local_funds_status", source.localFundsStatus())
                                        .set(
                                                "local_inventory_status",
                                                source.localInventoryStatus())
                                        .set("operation_no", number)
                                        .set(
                                                "payload_json",
                                                orders.db.json.writeValueAsString(publication))
                                        .set("decision_status", refund ? "WAITING_FUNDS" : "QUEUED")
                                        .setSql("version=version+1"))
                        == 1);
        for (var selected : mapped) {
            var line = audit(new CimAfterSaleLineLink(), actor);
            line.setLinkNo(BizKey.next("CIASL"));
            line.setEntityNo(entity);
            line.setCaseNo(caseNo);
            line.setOrderLineLinkNo(selected.allocation().getLinkNo());
            line.setSourceLineRef(selected.original().sourceLineRef());
            line.setComponentRef(selected.original().componentRef());
            line.setCoreItemRef(selected.original().coreItemRef());
            line.setQuantity(new BigDecimal(selected.original().physicalQuantity()));
            line.setUnit(selected.original().unit());
            line.setRefundCeilingMinor(selected.original().refundCeilingMinor());
            line.setAllocationEvidenceRef(selected.original().originalAllocationReceiptRef());
            lines.insert(line);
        }
        return ChannelOperationServiceImpl.view(op, null);
    }

    private List<Mapped> map(
            CimOrderLink order,
            ChannelOrderIngestPort.Command original,
            Document supplier,
            ChannelAfterSaleSourcePort.Facts facts) {
        require(orders.lineLinks != null);
        var allocated =
                orders.lineLinks.selectList(
                        Wrappers.<CimOrderLineLink>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", order.getEntityNo())
                                .eq("channel_order_no", order.getChannelOrderNo())
                                .eq("order_revision_no", original.externalSnapshotNo())
                                .last("LIMIT 10001"));
        require(allocated.size() <= 10000);
        var selected = new ArrayList<Mapped>();
        for (var line : facts.decision().lines()) {
            var matching =
                    allocated.stream()
                            .filter(
                                    l ->
                                            l.getSourceLineRef().equals(line.sourceLineRef())
                                                    && l.getComponentRef()
                                                            .equals(line.componentRef())
                                                    && l.getCoreItemRef()
                                                            .equals(line.coreItemRef()))
                            .toList();
            require(matching.size() == 1);
            var allocation = matching.getFirst();
            require(
                    line.externalLineRef().equals(allocation.getExternalLineRef())
                            && Objects.equals(
                                    line.parentExternalLineRef(),
                                    allocation.getParentExternalLineRef())
                            && line.unit().equals(allocation.getQuantityUnit())
                            && line.originalAllocationReceiptRef()
                                    .equals(allocation.getAllocationEvidenceRef())
                            && new BigDecimal(line.physicalQuantity())
                                            .compareTo(allocation.getSaleQuantity())
                                    <= 0
                            && line.refundCeilingMinor() <= allocation.getAmountMinor());
            selected.add(new Mapped(line, allocation));
        }
        if (facts.decision().action() != IndustryChannelAfterSalePort.Action.REJECT) {
            var normalized = aggregate(selected);
            require(normalized.size() == supplier.lines().size());
            for (var line : normalized)
                require(
                        supplier.lines().stream()
                                .anyMatch(
                                        l ->
                                                l.externalLineRef().equals(line.externalLineRef())
                                                        && new BigDecimal(l.quantity())
                                                                        .compareTo(
                                                                                new BigDecimal(
                                                                                        line
                                                                                                .quantity()))
                                                                == 0
                                                        && l.unit().equals(line.unit())
                                                        && l.merchantRefundMinor()
                                                                == line.merchantRefundMinor()));
        }
        return selected;
    }

    private static List<ChannelAfterSaleGateway.DecisionLine> aggregate(List<Mapped> selected) {
        var quantities = new HashMap<String, BigDecimal>();
        var amounts = new HashMap<String, Long>();
        var units = new HashMap<String, String>();
        for (var line : selected) {
            String external = line.original().externalLineRef();
            quantities.merge(
                    external, new BigDecimal(line.original().physicalQuantity()), BigDecimal::add);
            amounts.merge(external, line.original().refundCeilingMinor(), Math::addExact);
            String prior = units.putIfAbsent(external, line.original().unit());
            require(prior == null || prior.equals(line.original().unit()));
        }
        return quantities.keySet().stream()
                .sorted()
                .map(
                        external ->
                                new ChannelAfterSaleGateway.DecisionLine(
                                        external,
                                        quantities
                                                .get(external)
                                                .stripTrailingZeros()
                                                .toPlainString(),
                                        units.get(external),
                                        amounts.get(external)))
                .toList();
    }

    @Override
    public int recoverDue(int limit) {
        require(limit >= 1 && limit <= 100);
        var due =
                orders.db.commands.query(
                        () ->
                                orders.db.operations.selectList(
                                        Wrappers.<CimOperation>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq("operation_type", "AFTER_SALE_DECISION")
                                                .in(
                                                        "status",
                                                        "QUEUED",
                                                        "WAITING_FUNDS",
                                                        "RUNNING",
                                                        "UNKNOWN")
                                                .le("next_recovery_at", now())
                                                .orderByAsc("next_recovery_at", "id")
                                                .last("LIMIT " + limit)));
        int completed = 0;
        for (var candidate : due) {
            Claim claim = null;
            try {
                Fresh fresh = null;
                if (Set.of("QUEUED", "WAITING_FUNDS").contains(candidate.getStatus()))
                    fresh = refresh(candidate);
                var original = fresh;
                claim = orders.db.commands.atomic(() -> claim(candidate, original));
                if (claim == null) continue;
                var response =
                        claim.query()
                                ? claim.gateway().queryAfterSale(claim.publication())
                                : claim.gateway().applyAfterSale(claim.publication());
                var current = claim;
                if (orders.db.commands.atomic(() -> finish(current, response))) completed++;
            } catch (RuntimeException unknown) {
                if (claim != null) {
                    var failed = claim;
                    orders.db.commands.atomic(() -> finish(failed, null));
                } else defer(candidate);
            }
        }
        return completed;
    }

    private Fresh refresh(CimOperation candidate) {
        outside();
        var row =
                orders.db.commands.query(
                        () ->
                                scoped(
                                        candidate.getEntityNo(),
                                        candidate.getStoreNo(),
                                        candidate.getBusinessRef(),
                                        false));
        var publication = orders.db.json.readValue(row.getPayloadJson(), Publication.class);
        var coreOrder =
                orders.db.commands.query(
                        () ->
                                orders.scoped(
                                                candidate.getEntityNo(),
                                                candidate.getStoreNo(),
                                                row.getChannelOrderNo(),
                                                false)
                                        .getCoreOrderRef());
        var q =
                new ChannelAfterSaleSourcePort.Query(
                        candidate.getEntityNo(),
                        candidate.getStoreNo(),
                        coreOrder,
                        row.getExternalCaseRef(),
                        row.getSourceDecisionRef(),
                        row.getSourceRevision());
        var source = captureSource(q);
        require(source.decisionHash().equals(row.getSourceDecisionHash()));
        ChannelAfterSaleFundsPort.Facts financial = null;
        if (publication.action() != ChannelAfterSaleGateway.Action.REJECT) {
            var providers = funds.get();
            require(providers.size() <= 1);
            if (!providers.isEmpty()) {
                var query = fundsQuery(publication, coreOrder);
                financial = providers.getFirst().capture(query);
                ChannelAfterSalePolicy.funds(query, financial, clock);
            }
        }
        return new Fresh(source, financial);
    }

    private Claim claim(CimOperation candidate, Fresh fresh) {
        var before =
                scoped(
                        candidate.getEntityNo(),
                        candidate.getStoreNo(),
                        candidate.getBusinessRef(),
                        false);
        var c =
                orders.db.connection(
                        candidate.getEntityNo(),
                        candidate.getStoreNo(),
                        candidate.getConnectionNo(),
                        true,
                        false);
        var order =
                orders.scoped(
                        candidate.getEntityNo(),
                        candidate.getStoreNo(),
                        before.getChannelOrderNo(),
                        true);
        var op = operation(candidate.getEntityNo(), candidate.getOperationNo());
        var row =
                scoped(
                        candidate.getEntityNo(),
                        candidate.getStoreNo(),
                        candidate.getBusinessRef(),
                        true);
        if (!Set.of("QUEUED", "WAITING_FUNDS", "RUNNING", "UNKNOWN").contains(op.getStatus())
                || op.getNextRecoveryAt() == null
                || op.getNextRecoveryAt().isAfter(now())
                || op.getLeaseUntil() != null && op.getLeaseUntil().isAfter(now())) return null;
        var publication = orders.db.json.readValue(row.getPayloadJson(), Publication.class);
        require(op.getRequestHash().equals(PricingSnapshotHash.of(publication)));
        var gateway = gateways.get(op.getProfileNo());
        require(gateway != null);
        boolean query = Set.of("RUNNING", "UNKNOWN").contains(op.getStatus());
        if (!query) {
            business(order);
            require(fresh != null && !"CONFLICT".equals(row.getDecisionStatus()));
            var sq =
                    new ChannelAfterSaleSourcePort.Query(
                            op.getEntityNo(),
                            op.getStoreNo(),
                            order.getCoreOrderRef(),
                            row.getExternalCaseRef(),
                            row.getSourceDecisionRef(),
                            row.getSourceRevision());
            ChannelAfterSalePolicy.source(sq, fresh.source(), clock);
            require(
                    row.getSourceDecisionHash().equals(fresh.source().decisionHash())
                            && publication.caseHash().equals(revision(row).getContentHash())
                            && Set.of("REQUESTED", "RETURN_IN_PROGRESS")
                                    .contains(row.getSupplierState()));
            var grant =
                    grant(
                            op.getEntityNo(),
                            op.getAccountNo(),
                            publication.scope().authorizationNo());
            active(
                    c.account().getStatus(),
                    c.connection().getStatus(),
                    c.connection().getActiveRevisionNo(),
                    order,
                    grant);
            require(
                    publication
                                    .scope()
                                    .authorizationNo()
                                    .equals(c.account().getActiveAuthorizationNo())
                            && Objects.equals(
                                    grant.getCredentialRef(), publication.scope().credentialRef()));
            if (publication.action() != ChannelAfterSaleGateway.Action.REJECT) {
                if (fresh.funds() == null
                        || fresh.funds().status() != ChannelAfterSaleFundsPort.Status.AUTHORIZED) {
                    orders.db.operations.update(
                            Wrappers.<CimOperation>update()
                                    .eq("tenant_no", "MAIN")
                                    .eq("id", op.getId())
                                    .eq("version", op.getVersion())
                                    .set("status", "WAITING_FUNDS")
                                    .set("next_recovery_at", now().plusMinutes(1))
                                    .setSql("version=version+1"));
                    return null;
                }
                ChannelAfterSalePolicy.funds(
                        fundsQuery(publication, order.getCoreOrderRef()), fresh.funds(), clock);
                publication = authorized(publication, fresh.funds().originalAuthorizationRef());
                require(row.getPayloadJson() != null && op.getCurrentAttemptNo() == 0);
                orders.db.operations.update(
                        Wrappers.<CimOperation>update()
                                .eq("tenant_no", "MAIN")
                                .eq("id", op.getId())
                                .set("request_hash", PricingSnapshotHash.of(publication)));
                links.update(
                        Wrappers.<CimAfterSaleLink>update()
                                .eq("tenant_no", "MAIN")
                                .eq("id", row.getId())
                                .set(
                                        "payload_json",
                                        orders.db.json.writeValueAsString(publication)));
            }
        }
        long attempt = Math.addExact(op.getCurrentAttemptNo(), 1),
                fence = Math.addExact(op.getFence(), 1);
        var a = audit(new CimOperationAttempt(), "SYSTEM");
        a.setAttemptRef(BizKey.next("CIAT"));
        a.setEntityNo(op.getEntityNo());
        a.setOperationNo(op.getOperationNo());
        a.setAttemptNo(attempt);
        a.setFence(fence);
        a.setAttemptKind(query ? "QUERY" : "SEND");
        a.setProviderRequestRef(op.getOperationNo());
        a.setStartedAt(now());
        a.setOutcome("RUNNING");
        orders.db.attempts.insert(a);
        require(
                orders.db.operations.update(
                                Wrappers.<CimOperation>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("id", op.getId())
                                        .eq("fence", op.getFence())
                                        .set("status", "RUNNING")
                                        .set("fence", fence)
                                        .set("current_attempt_no", attempt)
                                        .set("lease_until", now().plusMinutes(2))
                                        .set("next_recovery_at", now().plusMinutes(2))
                                        .setSql("version=version+1"))
                        == 1);
        return new Claim(row, op, publication, attempt, fence, query, gateway);
    }

    private boolean finish(Claim claim, ChannelAfterSaleGateway.Result response) {
        var op = operation(claim.operation().getEntityNo(), claim.operation().getOperationNo());
        var row = scoped(op.getEntityNo(), op.getStoreNo(), op.getBusinessRef(), true);
        var attempt =
                orders.db.attempts.selectOne(
                        Wrappers.<CimOperationAttempt>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", op.getEntityNo())
                                .eq("operation_no", op.getOperationNo())
                                .eq("attempt_no", claim.attempt())
                                .last("FOR UPDATE"));
        require(attempt != null);
        boolean valid =
                response != null
                        && response.status() != null
                        && Objects.equals(response.operationNo(), op.getOperationNo())
                        && Objects.equals(
                                response.externalOrderRef(),
                                claim.publication().scope().externalOrderRef())
                        && Objects.equals(
                                response.externalCaseRef(), claim.publication().externalCaseRef())
                        && Objects.equals(
                                response.publicationHash(),
                                PricingSnapshotHash.of(claim.publication()))
                        && Objects.equals(
                                op.getRequestHash(), PricingSnapshotHash.of(claim.publication()))
                        && (response.status() != ResultStatus.REFUNDED
                                || claim.publication().action()
                                        != ChannelAfterSaleGateway.Action.REJECT);
        if (valid && response.status() != ResultStatus.UNKNOWN) {
            try {
                CommerceValidation.text(response.originalReceiptRef(), 256);
            } catch (RuntimeException bad) {
                valid = false;
            }
        }
        if (op.getFence() != claim.fence() || op.getCurrentAttemptNo() != claim.attempt()) {
            if (valid && response.originalReceiptRef() != null)
                orders.db.attempts.update(
                        Wrappers.<CimOperationAttempt>update()
                                .eq("tenant_no", "MAIN")
                                .eq("id", attempt.getId())
                                .isNull("late_evidence_ref")
                                .set("late_evidence_ref", response.originalReceiptRef())
                                .set("late_outcome", response.status().name()));
            return false;
        }
        boolean success =
                valid
                        && Set.of(ResultStatus.ACKNOWLEDGED, ResultStatus.REFUNDED)
                                .contains(response.status());
        boolean rejected = valid && response.status() == ResultStatus.REJECTED;
        String status = success ? "SUCCEEDED" : rejected ? "FAILED" : "UNKNOWN";
        String supplier =
                success
                        ? "SUPPLIER_" + response.status().name()
                        : rejected ? "SUPPLIER_REJECTED" : "UNKNOWN";
        String receipt = valid ? response.originalReceiptRef() : null;
        orders.db.attempts.update(
                Wrappers.<CimOperationAttempt>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", attempt.getId())
                        .isNull("finished_at")
                        .set("finished_at", now())
                        .set("outcome", status)
                        .set("response_ref", receipt));
        orders.db.operations.update(
                Wrappers.<CimOperation>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", op.getId())
                        .eq("fence", claim.fence())
                        .set("status", status)
                        .set("original_evidence_ref", receipt)
                        .set("lease_until", null)
                        .set("next_recovery_at", success || rejected ? null : now().plusSeconds(30))
                        .setSql("version=version+1"));
        // Supplier REFUNDED stays supplier evidence only. No local order, refund, inventory or
        // treasury state is touched.
        links.update(
                Wrappers.<CimAfterSaleLink>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", row.getId())
                        .set("decision_status", supplier)
                        .setSql("version=version+1"));
        return success || rejected;
    }

    private static Publication authorized(Publication p, String authorization) {
        return new Publication(
                p.operationNo(),
                p.scope(),
                p.externalCaseRef(),
                p.caseRevision(),
                p.caseHash(),
                p.originalOrderHash(),
                p.sourceDecisionRef(),
                p.sourceRevision(),
                p.sourceDecisionHash(),
                p.action(),
                p.reasonCode(),
                p.currency(),
                p.merchantRefundMinor(),
                p.lines(),
                authorization);
    }

    private static ChannelAfterSaleFundsPort.Query fundsQuery(Publication p, String coreOrder) {
        return new ChannelAfterSaleFundsPort.Query(
                p.scope().entityNo(),
                p.scope().storeNo(),
                coreOrder,
                p.externalCaseRef(),
                p.sourceDecisionRef(),
                p.sourceRevision(),
                p.caseHash(),
                p.currency(),
                p.merchantRefundMinor());
    }

    private CimAuthorizationRevision grant(String entity, String account, String authorization) {
        var grant =
                orders.db.authorizations.selectOne(
                        Wrappers.<CimAuthorizationRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", entity)
                                .eq("account_no", account)
                                .eq("authorization_no", authorization));
        require(
                grant != null
                        && "GRANTED".equals(grant.getStatus())
                        && grant.getCredentialRef() != null
                        && (grant.getExpiresAt() == null || grant.getExpiresAt().isAfter(now())));
        var scopes = orders.db.json.readTree(grant.getGrantedScopes());
        require(scopes.isArray());
        boolean authorized = false;
        for (var scope : scopes)
            if (scope.isString() && "AFTER_SALE".equals(scope.asString())) authorized = true;
        require(authorized);
        return grant;
    }

    private static void active(
            String account,
            String connection,
            String revision,
            CimOrderLink order,
            CimAuthorizationRevision grant) {
        require(
                "ACTIVE".equals(account)
                        && "ACTIVE".equals(connection)
                        && order.getConnectionRevisionNo().equals(revision)
                        && grant.getCredentialRef() != null);
    }

    private void defer(CimOperation candidate) {
        orders.db.commands.atomic(
                () -> {
                    orders.db.operations.update(
                            Wrappers.<CimOperation>update()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", candidate.getEntityNo())
                                    .eq("operation_no", candidate.getOperationNo())
                                    .eq("version", candidate.getVersion())
                                    .in("status", "QUEUED", "WAITING_FUNDS")
                                    .set("next_recovery_at", now().plusMinutes(1))
                                    .setSql("version=version+1"));
                    return null;
                });
    }

    private CimOperation operation(String entity, String number) {
        var op =
                orders.db.operations.selectOne(
                        Wrappers.<CimOperation>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", entity)
                                .eq("operation_no", number)
                                .last("FOR UPDATE"));
        require(op != null);
        return op;
    }

    private CimAfterSaleLink scoped(String entity, String store, String number, boolean lock) {
        var q =
                Wrappers.<CimAfterSaleLink>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", entity)
                        .eq("store_no", store)
                        .eq("case_no", number);
        if (lock) q.last("FOR UPDATE");
        var row = links.selectOne(q);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private CimAfterSaleRevision revision(CimAfterSaleLink row) {
        var value =
                revisions.selectOne(
                        Wrappers.<CimAfterSaleRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", row.getEntityNo())
                                .eq("case_no", row.getCaseNo())
                                .eq("revision_no", row.getActiveRevisionNo()));
        require(value != null);
        return value;
    }

    private Document document(CimAfterSaleLink row) {
        return orders.db.json.readValue(revision(row).getPayloadJson(), Document.class);
    }

    private static void business(CimOrderLink order) {
        require(
                "CONFIRMED".equals(order.getImportStatus())
                        && "CONFIRMED".equals(order.getAcceptanceStatus())
                        && order.getCoreOrderRef() != null
                        && order.getImportSnapshot() != null);
    }

    @Override
    public AfterSaleContracts.Link getCase(String entity, String store, String number) {
        CommerceValidation.text(number, 32);
        return orders.db.commands.query(() -> view(scoped(entity, store, number, false)));
    }

    @Override
    public PageData<AfterSaleContracts.Link> listCases(
            String entity, String store, AfterSaleContracts.Query query) {
        require(query != null);
        long offset = CommerceValidation.offset(query.page(), query.size());
        if (query.connectionNo() != null) CommerceValidation.text(query.connectionNo(), 32);
        if (query.channelOrderNo() != null) CommerceValidation.text(query.channelOrderNo(), 32);
        if (query.type() != null)
            require(Set.of("REFUND_ONLY", "RETURN_REFUND").contains(query.type()));
        if (query.status() != null)
            require(
                    Set.of("REQUESTED", "RETURN_IN_PROGRESS", "REFUNDED", "REJECTED", "CLOSED")
                            .contains(query.status()));
        require(query.from() == null || query.to() == null || !query.to().isBefore(query.from()));
        return orders.db.commands.query(
                () -> {
                    var q =
                            Wrappers.<CimAfterSaleLink>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", entity)
                                    .eq("store_no", store)
                                    .eq(
                                            query.connectionNo() != null,
                                            "connection_no",
                                            query.connectionNo())
                                    .eq(
                                            query.channelOrderNo() != null,
                                            "channel_order_no",
                                            query.channelOrderNo())
                                    .eq(query.type() != null, "type", query.type())
                                    .eq(query.status() != null, "supplier_state", query.status());
                    if (query.from() != null)
                        q.ge("requested_at", LocalDateTime.ofInstant(query.from(), ZoneOffset.UTC));
                    if (query.to() != null)
                        q.le("requested_at", LocalDateTime.ofInstant(query.to(), ZoneOffset.UTC));
                    long total = links.selectCount(q);
                    var rows =
                            links.selectList(
                                    q.orderByDesc("requested_at", "id")
                                            .last("LIMIT " + query.size() + " OFFSET " + offset));
                    return PageData.of(
                            rows.stream().map(this::view).toList(),
                            total,
                            query.page(),
                            query.size());
                });
    }

    private AfterSaleContracts.Link view(CimAfterSaleLink row) {
        var d = document(row);
        return new AfterSaleContracts.Link(
                row.getCaseNo(),
                row.getChannelOrderNo(),
                row.getConnectionNo(),
                row.getExternalCaseRef(),
                d.type(),
                row.getSupplierState(),
                d.revisionRef(),
                row.getCurrency(),
                row.getMerchantRefundMinor(),
                row.getOperationNo(),
                row.getDecisionStatus(),
                row.getSourceDecisionRef(),
                row.getSourceRevision(),
                row.getLocalFundsStatus(),
                row.getLocalInventoryStatus(),
                d.lines().stream()
                        .map(
                                l ->
                                        new AfterSaleContracts.Line(
                                                l.externalLineRef(),
                                                l.quantity(),
                                                l.unit(),
                                                l.merchantRefundMinor()))
                        .toList(),
                d.requestedAt(),
                row.getVersion());
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("After-sale owner calls require no local transaction");
    }
}
