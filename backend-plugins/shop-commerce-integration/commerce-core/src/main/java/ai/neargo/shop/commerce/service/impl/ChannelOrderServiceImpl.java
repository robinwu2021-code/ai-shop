package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.audit;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.now;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.require;

import ai.neargo.shop.commerce.dto.ChannelOrderContracts;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.commerce.entity.CimConnectionRevision;
import ai.neargo.shop.commerce.entity.CimInboundEvent;
import ai.neargo.shop.commerce.entity.CimOperation;
import ai.neargo.shop.commerce.entity.CimOperationAttempt;
import ai.neargo.shop.commerce.entity.CimOrderLineLink;
import ai.neargo.shop.commerce.entity.CimOrderLink;
import ai.neargo.shop.commerce.entity.CimOrderRevision;
import ai.neargo.shop.commerce.mapper.CommerceMappers.InboundEventMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.OrderLineLinkMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.OrderLinkMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.OrderRevisionMapper;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.commerce.policy.ChannelOrderEconomicPolicy;
import ai.neargo.shop.commerce.policy.ChannelOrderPolicy;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.commerce.service.ChannelOrderService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.commerce.ChannelInboundHandler;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;
import ai.neargo.shop.spi.commerce.CommerceGateway.Capability;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Callbacks only announce a revision; authoritative read and exact snapshot persistence precede
 * business preparation.
 */
public final class ChannelOrderServiceImpl implements ChannelOrderService, ChannelInboundHandler {
    private Supplier<ChannelOrderDecisionService> decisions;
    private Supplier<ChannelOrderMaintenanceService> maintenance;

    /** Lazy routing keeps readonly capture independent from action worker construction. */
    public void maintenance(Supplier<ChannelOrderMaintenanceService> worker) {
        maintenance = worker;
    }

    @Override
    public OperationVO refreshOrder(
            String entity,
            String store,
            String order,
            String actor,
            ChannelOrderContracts.Refresh request,
            String key) {
        return maintenance.get().refresh(entity, store, order, actor, request, key);
    }

    @Override
    public OperationVO recoverImport(
            String entity,
            String store,
            String order,
            String actor,
            ChannelOrderContracts.Recover request,
            String key) {
        return maintenance.get().recoverImport(entity, store, order, actor, request, key);
    }

    /**
     * Lazy original-workflow routing keeps the query/callback service independent of action worker
     * construction.
     */
    public void decisions(Supplier<ChannelOrderDecisionService> worker) {
        decisions = worker;
    }

    @Override
    public OperationVO accept(
            String entity,
            String store,
            String order,
            String actor,
            ChannelOrderContracts.Accept request,
            String key) {
        return decisions.get().accept(entity, store, order, actor, request, key);
    }

    @Override
    public OperationVO reject(
            String entity,
            String store,
            String order,
            String actor,
            ChannelOrderContracts.Reject request,
            String key) {
        return decisions.get().reject(entity, store, order, actor, request, key);
    }

    final CatalogPersistence db;
    final OrderLinkMapper orders;
    final OrderRevisionMapper revisions;
    final OrderLineLinkMapper lineLinks;
    private final InboundEventMapper events;
    final Map<String, ChannelOrderGateway> gateways;
    private final Clock clock;

    public ChannelOrderServiceImpl(
            CatalogPersistence db,
            OrderLinkMapper orders,
            OrderRevisionMapper revisions,
            InboundEventMapper events,
            List<ChannelOrderGateway> adapters,
            Clock clock) {
        this(db, orders, revisions, events, adapters, clock, null);
    }

    public ChannelOrderServiceImpl(
            CatalogPersistence db,
            OrderLinkMapper orders,
            OrderRevisionMapper revisions,
            InboundEventMapper events,
            List<ChannelOrderGateway> adapters,
            Clock clock,
            OrderLineLinkMapper lineLinks) {
        this.lineLinks = lineLinks;
        this.db = db;
        this.orders = orders;
        this.revisions = revisions;
        this.events = events;
        this.clock = clock;
        var index = new HashMap<String, ChannelOrderGateway>();
        for (var gateway : adapters)
            if (index.putIfAbsent(gateway.profileNo(), gateway) != null)
                throw new IllegalStateException("Duplicate original order gateway");
        gateways = Map.copyOf(index);
    }

    @Override
    public String profileNo() {
        return "ORIGINAL_ORDER_ROUTER";
    }

    @Override
    public boolean acceptsProfile(String profile) {
        return gateways.containsKey(profile);
    }

    @Override
    public boolean supports(String type) {
        return Set.of("ORDER_CREATED", "ORDER_UPDATED", "ORDER_CANCELLED").contains(type);
    }

    @Override
    public Result handle(Event event) {
        var gateway = gateways.get(event.profileNo());
        if (gateway == null || !gateway.capabilities().contains(Capability.ORDER))
            return new Result(Outcome.WAITING_OWNER, null);
        CommerceValidation.text(event.externalOrderRef(), 128);
        var query =
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
        var snapshot = gateway.readOrder(query);
        ChannelOrderPolicy.validate(query, snapshot, clock);
        return db.commands.atomic(
                () ->
                        capture(
                                new Origin(
                                        event.entityNo(),
                                        event.storeNo(),
                                        event.connectionNo(),
                                        event.connectionRevisionNo(),
                                        event.profileNo(),
                                        event.externalAccountRef(),
                                        event.externalStoreRef(),
                                        event.eventNo(),
                                        null),
                                snapshot));
    }

    /**
     * Provenance is either a persisted signed callback or a persisted merchant-requested read
     * operation.
     */
    record Origin(
            String entityNo,
            String storeNo,
            String connectionNo,
            String connectionRevisionNo,
            String profileNo,
            String externalAccountRef,
            String externalStoreRef,
            String eventNo,
            String operationNo) {}

    Result capture(Origin event, ChannelOrderGateway.Snapshot snapshot) {
        var c = db.connection(event.entityNo(), event.storeNo(), event.connectionNo(), true, false);
        require(
                c.account().getExternalAccountRef().equals(event.externalAccountRef())
                        && c.connection().getExternalStoreRef().equals(event.externalStoreRef())
                        && c.connection().getProfileNo().equals(event.profileNo())
                        && snapshot.validUntil().isAfter(clock.instant()));
        var route =
                db.revisions.selectOne(
                        Wrappers.<CimConnectionRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", event.entityNo())
                                .eq("connection_no", event.connectionNo())
                                .eq("revision_no", event.connectionRevisionNo()));
        require(route != null);
        var d = snapshot.document();
        String externalKey = ChannelIdentity.hash(d.externalOrderRef());
        var order =
                orders.selectOne(
                        Wrappers.<CimOrderLink>query()
                                .eq("tenant_no", "MAIN")
                                .eq("channel_store_key", c.connection().getChannelStoreKey())
                                .eq("external_order_key", externalKey)
                                .last("FOR UPDATE"));
        if (order == null) {
            order = audit(new CimOrderLink(), "SYSTEM");
            order.setChannelOrderNo(BizKey.next("CIOR"));
            order.setEntityNo(event.entityNo());
            order.setStoreNo(event.storeNo());
            order.setConnectionNo(event.connectionNo());
            order.setConnectionRevisionNo(event.connectionRevisionNo());
            order.setChannelStoreKey(c.connection().getChannelStoreKey());
            order.setExternalOrderRef(d.externalOrderRef());
            order.setOrderedAt(LocalDateTime.ofInstant(d.orderedAt(), ZoneOffset.UTC));
            order.setExternalOrderKey(externalKey);
            order.setSourceOwner(route.getSourceOwner());
            order.setImportRef(BizKey.next("CIIP"));
            order.setActiveRevisionNo(BizKey.next("CIOV"));
            order.setImportStatus(
                    d.state() == ChannelOrderGateway.State.CANCELLED ? "CANCELLED" : "RECEIVED");
            order.setAcceptanceStatus("UNCONFIRMED");
            order.setPlatformStatus(d.state().name());
            order.setFulfillmentStatus("UNCONFIRMED");
            order.setFinancialEvidenceStatus("UNCONFIRMED");
            order.setAttemptNo(0L);
            order.setFence(0L);
            order.setNextAttemptAt(now());
            orders.insert(order);
            append(order, event, snapshot, order.getActiveRevisionNo());
            return new Result(Outcome.PROCESSED, order.getChannelOrderNo());
        }
        require(
                order.getEntityNo().equals(event.entityNo())
                        && order.getStoreNo().equals(event.storeNo())
                        && order.getConnectionNo().equals(event.connectionNo()));
        var same =
                revisions.selectList(
                        Wrappers.<CimOrderRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", event.entityNo())
                                .eq("channel_order_no", order.getChannelOrderNo())
                                .eq("external_revision", d.revisionRef()));
        if (!same.isEmpty()) {
            if (same.stream().anyMatch(r -> r.getContentHash().equals(snapshot.documentHash())))
                return new Result(Outcome.PROCESSED, order.getChannelOrderNo());
            append(order, event, snapshot, BizKey.next("CIOV"));
            conflict(order);
            return new Result(Outcome.CONFLICT, order.getChannelOrderNo());
        }
        var previous = revision(order);
        String revision = BizKey.next("CIOV");
        append(order, event, snapshot, revision);
        if (d.revisionSequence() != null
                && previous.getExternalSequence() != null
                && d.revisionSequence() < previous.getExternalSequence())
            return new Result(Outcome.IGNORED, order.getChannelOrderNo());
        if (d.revisionSequence() == null
                || previous.getExternalSequence() == null
                || d.revisionSequence().equals(previous.getExternalSequence())) {
            conflict(order);
            return new Result(Outcome.CONFLICT, order.getChannelOrderNo());
        }
        if ("CANCELLED".equals(order.getPlatformStatus())
                && d.state() != ChannelOrderGateway.State.CANCELLED) {
            conflict(order);
            return new Result(Outcome.CONFLICT, order.getChannelOrderNo());
        }
        // A prepared or accepted original is immutable. Subsequent revisions need the original
        // amendment/cancellation owner.
        boolean started =
                order.getImportSnapshot() != null
                        || order.getDecisionOperationNo() != null
                        || order.getCoreOrderRef() != null;
        boolean unchanged =
                order.getImportSnapshot() != null
                        && ChannelOrderEconomicPolicy.sameBusiness(
                                db.json
                                        .readValue(
                                                order.getImportSnapshot(),
                                                ChannelOrderIngestPort.Command.class)
                                        .original()
                                        .document(),
                                d);
        boolean observedAcceptance =
                unchanged
                        && d.state() == ChannelOrderGateway.State.ACCEPTED
                        && !"CONFIRMED".equals(order.getAcceptanceStatus())
                        && observedAcceptance(order, snapshot);
        boolean terminalAcceptance =
                observedAcceptance
                        || unchanged
                                && order.getPlatformAcceptanceReceiptRef() != null
                                && "PLATFORM_CONFIRMED".equals(order.getAcceptanceStatus());
        boolean terminalRejection =
                unchanged && "PLATFORM_REJECTED".equals(order.getAcceptanceStatus());
        boolean retargeted =
                unchanged
                        && d.state() == ChannelOrderGateway.State.WAITING_ACCEPTANCE
                        && decisions != null
                        && decisions.get().retargetUnsent(order, revision);
        // Dispatched UNKNOWN actions retain their original request. Only an actual terminal proof
        // resumes native work.
        boolean reusable =
                unchanged
                        && ("CONFIRMED".equals(order.getAcceptanceStatus())
                                || terminalAcceptance
                                || terminalRejection
                                || order.getDecisionOperationNo() == null
                                || retargeted);
        String status =
                d.state() == ChannelOrderGateway.State.CANCELLED
                        ? started ? "WAITING_CANCELLATION" : "CANCELLED"
                        : reusable
                                ? "CONFIRMED".equals(order.getAcceptanceStatus())
                                        ? "CONFIRMED"
                                        : terminalAcceptance
                                                ? "WAITING_CONFIRMATION"
                                                : terminalRejection
                                                        ? "WAITING_CANCELLATION"
                                                        : "WAITING_OWNER"
                                : started
                                        ? "WAITING_AMENDMENT"
                                        : d.state() == ChannelOrderGateway.State.CANCELLED
                                                ? "CANCELLED"
                                                : "RECEIVED";
        require(
                orders.update(
                                Wrappers.<CimOrderLink>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("id", order.getId())
                                        .eq("version", order.getVersion())
                                        .set("active_revision_no", revision)
                                        .set("platform_status", d.state().name())
                                        .set(
                                                "acceptance_status",
                                                observedAcceptance
                                                        ? "PLATFORM_CONFIRMED"
                                                        : order.getAcceptanceStatus())
                                        .set(
                                                "platform_acceptance_receipt_ref",
                                                observedAcceptance
                                                        ? snapshot.originalReceiptRef()
                                                        : order.getPlatformAcceptanceReceiptRef())
                                        .set("import_status", status)
                                        .set("fence", order.getFence() + 1)
                                        .set("lease_until", null)
                                        .set("next_attempt_at", now())
                                        .set("version", order.getVersion() + 1))
                        == 1);
        return new Result(Outcome.PROCESSED, order.getChannelOrderNo());
    }

    /**
     * An actual accepted-order read proves current upstream acceptance, not success of a local
     * outbound action.
     */
    private boolean observedAcceptance(CimOrderLink order, ChannelOrderGateway.Snapshot snapshot) {
        if (order.getDecisionOperationNo() == null) return true;
        var op =
                db.operations.selectOne(
                        Wrappers.<CimOperation>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", order.getEntityNo())
                                .eq("operation_no", order.getDecisionOperationNo())
                                .last("FOR UPDATE"));
        if (op == null || !"CHANNEL_ORDER_ACCEPT".equals(op.getOperationType())) return false;
        if ("QUEUED".equals(op.getStatus()) && op.getCurrentAttemptNo() == 0) {
            require(
                    db.attempts.selectCount(
                                    Wrappers.<CimOperationAttempt>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("entity_no", order.getEntityNo())
                                            .eq("operation_no", op.getOperationNo()))
                            == 0);
            require(
                    db.operations.update(
                                    Wrappers.<CimOperation>update()
                                            .eq("tenant_no", "MAIN")
                                            .eq("id", op.getId())
                                            .eq("version", op.getVersion())
                                            .set("status", "SUPERSEDED")
                                            .set("next_recovery_at", null)
                                            .set(
                                                    "original_evidence_ref",
                                                    snapshot.originalReceiptRef())
                                            .setSql("version=version+1"))
                            == 1);
        }
        // An already dispatched UNKNOWN operation remains frozen and queryable under its original
        // key.
        return true;
    }

    private void append(
            CimOrderLink order, Origin event, ChannelOrderGateway.Snapshot snapshot, String no) {
        var row = audit(new CimOrderRevision(), "SYSTEM");
        var d = snapshot.document();
        row.setRevisionNo(no);
        row.setEntityNo(order.getEntityNo());
        row.setChannelOrderNo(order.getChannelOrderNo());
        row.setExternalRevision(d.revisionRef());
        row.setExternalSequence(d.revisionSequence());
        row.setSourceEventNo(event.eventNo());
        row.setSourceOperationNo(event.operationNo());
        row.setNormalizedSnapshotRef("DB:" + no);
        row.setNormalizedSnapshot(db.json.writeValueAsString(snapshot));
        row.setContentHash(snapshot.documentHash());
        row.setCatalogHash(d.catalogHash());
        row.setCurrency(d.currency());
        row.setOriginalReceiptRef(snapshot.originalReceiptRef());
        row.setCapturedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        require((event.eventNo() == null) != (event.operationNo() == null));
        if (event.eventNo() != null) {
            var incoming =
                    events.selectOne(
                            Wrappers.<CimInboundEvent>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", event.entityNo())
                                    .eq("store_no", event.storeNo())
                                    .eq("event_no", event.eventNo()));
            require(incoming != null && incoming.getConnectionNo().equals(order.getConnectionNo()));
            row.setRawReceiptRef(incoming.getOriginalReceiptNo());
        }
        revisions.insert(row);
    }

    private void conflict(CimOrderLink row) {
        orders.update(
                Wrappers.<CimOrderLink>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", row.getId())
                        .set("import_status", "CONFLICT")
                        .set("fence", row.getFence() + 1)
                        .set("lease_until", null)
                        .set("next_attempt_at", null)
                        .set("version", row.getVersion() + 1));
    }

    CimOrderRevision revision(CimOrderLink order) {
        var row =
                revisions.selectOne(
                        Wrappers.<CimOrderRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", order.getEntityNo())
                                .eq("channel_order_no", order.getChannelOrderNo())
                                .eq("revision_no", order.getActiveRevisionNo()));
        require(row != null);
        return row;
    }

    CimOrderLink scoped(String entity, String store, String number, boolean lock) {
        CommerceValidation.text(entity, 32);
        CommerceValidation.text(store, 32);
        CommerceValidation.text(number, 32);
        var q =
                Wrappers.<CimOrderLink>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", entity)
                        .eq("store_no", store)
                        .eq("channel_order_no", number);
        if (lock) q.last("FOR UPDATE");
        var row = orders.selectOne(q);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    ChannelOrderContracts.Order view(CimOrderLink order) {
        var row = revision(order);
        var d =
                db.json
                        .readValue(row.getNormalizedSnapshot(), ChannelOrderGateway.Snapshot.class)
                        .document();
        return new ChannelOrderContracts.Order(
                order.getChannelOrderNo(),
                order.getConnectionNo(),
                order.getExternalOrderRef(),
                row.getRevisionNo(),
                order.getImportStatus(),
                order.getAcceptanceStatus(),
                order.getPlatformStatus(),
                order.getCoreOrderRef(),
                d.currency(),
                d.totals().merchantReceivableMinor(),
                d.totals().customerPayableMinor(),
                d.orderedAt(),
                order.getVersion());
    }

    @Override
    public PageData<ChannelOrderContracts.Order> listOrders(
            String entity, String store, ChannelOrderContracts.Query request) {
        CommerceValidation.text(entity, 32);
        CommerceValidation.text(store, 32);
        if (request == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        if (request.connectionNo() != null) CommerceValidation.text(request.connectionNo(), 32);
        if (request.importStatus() != null) CommerceValidation.text(request.importStatus(), 24);
        if (request.externalOrderRef() != null)
            CommerceValidation.text(request.externalOrderRef(), 128);
        if (request.sourceOwner() != null
                && !Set.of("FOOD", "RETAIL").contains(request.sourceOwner()))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        if (request.platformStatus() != null) CommerceValidation.text(request.platformStatus(), 64);
        if (request.from() != null
                && request.to() != null
                && !request.from().isBefore(request.to()))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        long offset = CommerceValidation.offset(request.page(), request.size());
        return db.commands.query(
                () -> {
                    var q =
                            Wrappers.<CimOrderLink>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", entity)
                                    .eq("store_no", store)
                                    .eq(
                                            request.connectionNo() != null,
                                            "connection_no",
                                            request.connectionNo())
                                    .eq(
                                            request.importStatus() != null,
                                            "import_status",
                                            request.importStatus())
                                    .eq(
                                            request.externalOrderRef() != null,
                                            "external_order_ref",
                                            request.externalOrderRef())
                                    .eq(
                                            request.sourceOwner() != null,
                                            "source_owner",
                                            request.sourceOwner())
                                    .eq(
                                            request.platformStatus() != null,
                                            "platform_status",
                                            request.platformStatus());
                    if (request.from() != null)
                        q.ge("ordered_at", LocalDateTime.ofInstant(request.from(), ZoneOffset.UTC));
                    if (request.to() != null)
                        q.lt("ordered_at", LocalDateTime.ofInstant(request.to(), ZoneOffset.UTC));
                    long total = orders.selectCount(q);
                    var list =
                            orders.selectList(
                                    q.orderByDesc("created_at", "id")
                                            .last("LIMIT " + request.size() + " OFFSET " + offset));
                    return PageData.of(
                            list.stream().map(this::view).toList(),
                            total,
                            request.page(),
                            request.size());
                });
    }

    @Override
    public PageData<ChannelOrderContracts.Revision> listRevisions(
            String entity, String store, String number, int page, int size) {
        long offset = CommerceValidation.offset(page, size);
        return db.commands.query(
                () -> {
                    scoped(entity, store, number, false);
                    var q =
                            Wrappers.<CimOrderRevision>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", entity)
                                    .eq("channel_order_no", number);
                    long total = revisions.selectCount(q);
                    var list =
                            revisions.selectList(
                                    q.orderByDesc("captured_at", "id")
                                            .last("LIMIT " + size + " OFFSET " + offset));
                    return PageData.of(
                            list.stream()
                                    .map(
                                            row ->
                                                    new ChannelOrderContracts.Revision(
                                                            row.getRevisionNo(),
                                                            row.getExternalRevision(),
                                                            row.getExternalSequence(),
                                                            row.getContentHash(),
                                                            row.getCatalogHash(),
                                                            row.getCurrency(),
                                                            row.getCapturedAt()
                                                                    .toInstant(ZoneOffset.UTC)))
                                    .toList(),
                            total,
                            page,
                            size);
                });
    }

    @Override
    public PageData<ChannelOrderContracts.Line> listLines(
            String entity, String store, String number, String revisionNo, int page, int size) {
        long offset = CommerceValidation.offset(page, size);
        if (revisionNo != null) CommerceValidation.text(revisionNo, 32);
        return db.commands.query(
                () -> {
                    var order = scoped(entity, store, number, false);
                    var row =
                            revisionNo == null
                                    ? revision(order)
                                    : revisions.selectOne(
                                            Wrappers.<CimOrderRevision>query()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("entity_no", entity)
                                                    .eq("channel_order_no", number)
                                                    .eq("revision_no", revisionNo));
                    if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
                    var original =
                            db.json
                                    .readValue(
                                            row.getNormalizedSnapshot(),
                                            ChannelOrderGateway.Snapshot.class)
                                    .document();
                    var links = allocations(entity, number, row.getRevisionNo());
                    return PageData.of(
                            original.lines().stream()
                                    .skip(offset)
                                    .limit(size)
                                    .map(l -> lineView(l, links))
                                    .toList(),
                            original.lines().size(),
                            page,
                            size);
                });
    }

    private List<CimOrderLineLink> allocations(String entity, String order, String revision) {
        return lineLinks == null
                ? List.of()
                : lineLinks.selectList(
                        Wrappers.<CimOrderLineLink>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", entity)
                                .eq("channel_order_no", order)
                                .eq(
                                        "order_revision_no",
                                        allocationRevision(entity, order, revision))
                                .orderByAsc("id")
                                .last("LIMIT 10001"));
    }

    private String allocationRevision(String entity, String number, String requested) {
        var order =
                orders.selectOne(
                        Wrappers.<CimOrderLink>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", entity)
                                .eq("channel_order_no", number));
        if (order == null
                || order.getImportSnapshot() == null
                || !"CONFIRMED".equals(order.getAcceptanceStatus())) return requested;
        var command =
                db.json.readValue(order.getImportSnapshot(), ChannelOrderIngestPort.Command.class);
        if (requested.equals(command.externalSnapshotNo())) return requested;
        var revision =
                revisions.selectOne(
                        Wrappers.<CimOrderRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", entity)
                                .eq("channel_order_no", number)
                                .eq("revision_no", requested));
        if (revision == null) return requested;
        var current =
                db.json.readValue(
                        revision.getNormalizedSnapshot(), ChannelOrderGateway.Snapshot.class);
        return ChannelOrderEconomicPolicy.sameBusiness(
                        command.original().document(), current.document())
                ? command.externalSnapshotNo()
                : requested;
    }

    private ChannelOrderContracts.Line lineView(
            ChannelOrderGateway.Line l, List<CimOrderLineLink> links) {
        require(links.size() <= 10000);
        var mapped =
                links.stream()
                        .filter(
                                link ->
                                        l.lineRef().equals(link.getExternalLineRef())
                                                        && link.getParentExternalLineRef() == null
                                                || l.lineRef()
                                                        .equals(link.getParentExternalLineRef()))
                        .map(
                                link ->
                                        new ChannelOrderContracts.Allocation(
                                                link.getLinkNo(),
                                                link.getExternalLineRef(),
                                                link.getParentExternalLineRef(),
                                                link.getComponentKind(),
                                                link.getSourceLineRef(),
                                                link.getComponentRef(),
                                                link.getSaleQuantity()
                                                        .stripTrailingZeros()
                                                        .toPlainString(),
                                                link.getQuantityUnit(),
                                                link.getAmountMinor(),
                                                link.getCurrency()))
                        .toList();
        return new ChannelOrderContracts.Line(
                l.lineRef(),
                l.skuIdentity().externalRef(),
                l.quantity(),
                l.unit(),
                l.grossExTaxMinor(),
                l.merchantDiscountExTaxMinor(),
                l.taxMinor(),
                l.netMinor(),
                mapped);
    }

    @Override
    public ChannelOrderContracts.Detail getOrder(String entity, String store, String number) {
        return db.commands.query(
                () -> {
                    var order = scoped(entity, store, number, false);
                    var row = revision(order);
                    var d =
                            db.json
                                    .readValue(
                                            row.getNormalizedSnapshot(),
                                            ChannelOrderGateway.Snapshot.class)
                                    .document();
                    var links = allocations(entity, number, row.getRevisionNo());
                    var lines = d.lines().stream().map(l -> lineView(l, links)).toList();
                    return new ChannelOrderContracts.Detail(
                            view(order), lines, row.getContentHash(), row.getCatalogHash());
                });
    }
}
