package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.audit;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.now;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.require;

import ai.neargo.shop.commerce.dto.FulfillmentContracts;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.commerce.entity.CimAuthorizationRevision;
import ai.neargo.shop.commerce.entity.CimFulfillmentLineLink;
import ai.neargo.shop.commerce.entity.CimFulfillmentLink;
import ai.neargo.shop.commerce.entity.CimOperation;
import ai.neargo.shop.commerce.entity.CimOperationAttempt;
import ai.neargo.shop.commerce.entity.CimOrderLineLink;
import ai.neargo.shop.commerce.entity.CimOrderLink;
import ai.neargo.shop.commerce.mapper.CommerceMappers.FulfillmentLineLinkMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.FulfillmentLinkMapper;
import ai.neargo.shop.commerce.policy.ChannelFulfillmentPolicy;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.commerce.service.ChannelFulfillmentService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.SourceQuantityUnits;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.FulfillmentLine;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.FulfillmentPublication;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.PublicationResult;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.PublicationStatus;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentSourcePort;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentSourcePort.Facts;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentSourcePort.Query;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;
import ai.neargo.shop.spi.commerce.ChannelRecoveryPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Original committed fulfillment is published independently; no provider response changes local
 * stock or money.
 */
public final class ChannelFulfillmentServiceImpl
        implements ChannelFulfillmentService, ChannelRecoveryPort {
    private final ChannelOrderServiceImpl orders;
    private final FulfillmentLinkMapper links;
    private final FulfillmentLineLinkMapper lines;
    private final Supplier<List<ChannelFulfillmentSourcePort>> sources;
    private final Map<String, ChannelFulfillmentGateway> gateways;
    private final Clock clock;

    public ChannelFulfillmentServiceImpl(
            ChannelOrderServiceImpl orders,
            FulfillmentLinkMapper links,
            FulfillmentLineLinkMapper lines,
            Supplier<List<ChannelFulfillmentSourcePort>> sources,
            List<ChannelFulfillmentGateway> gateways,
            Clock clock) {
        this.orders = orders;
        this.links = links;
        this.lines = lines;
        this.sources = sources;
        this.clock = clock;
        var index = new HashMap<String, ChannelFulfillmentGateway>();
        for (var gateway : gateways)
            require(index.putIfAbsent(gateway.profileNo(), gateway) == null);
        this.gateways = Map.copyOf(index);
    }

    @Override
    public String workflow() {
        return "CHANNEL_FULFILLMENT";
    }

    private record Claim(
            CimFulfillmentLink link,
            CimOperation operation,
            FulfillmentPublication publication,
            long attempt,
            long fence,
            boolean query,
            ChannelFulfillmentGateway gateway) {}

    private record Mapped(ChannelFulfillmentSourcePort.Line source, CimOrderLineLink link) {}

    /**
     * Public publication accepts references only and re-reads immutable original owner facts
     * outside all transactions.
     */
    @Override
    public OperationVO publishFulfillment(
            String entity,
            String store,
            String actor,
            FulfillmentContracts.Publish request,
            String key) {
        outside();
        validate(request);
        String endpoint = "PUBLISH_FULFILLMENT";
        var replay =
                orders.db.commands.replay(
                        entity, store, actor, endpoint, key, request, OperationVO.class);
        if (replay.isPresent()) return replay.get();
        Query query =
                orders.db.commands.query(
                        () -> {
                            var order =
                                    orders.scoped(entity, store, request.channelOrderNo(), false);
                            business(order, request.sourceOwner().name());
                            return new Query(
                                    entity,
                                    store,
                                    order.getSourceOrderRef(),
                                    order.getCoreOrderRef(),
                                    request.sourceFulfillmentRef(),
                                    request.sourceRevision());
                        });
        var facts = capture(request.sourceOwner().name(), query);
        return orders.db.commands.execute(
                entity,
                store,
                actor,
                endpoint,
                key,
                request,
                OperationVO.class,
                () -> persist(entity, store, actor, request, facts),
                OperationVO::operationNo);
    }

    private void validate(FulfillmentContracts.Publish request) {
        require(request != null && request.sourceOwner() != null);
        CommerceValidation.text(request.channelOrderNo(), 32);
        CommerceValidation.text(request.sourceFulfillmentRef(), 128);
        CommerceValidation.text(request.sourceRevision(), 128);
    }

    private void business(CimOrderLink order, String owner) {
        require(
                owner.equals(order.getSourceOwner())
                        && "CONFIRMED".equals(order.getImportStatus())
                        && "CONFIRMED".equals(order.getAcceptanceStatus())
                        && order.getSourceOrderRef() != null
                        && order.getCoreOrderRef() != null
                        && order.getImportSnapshot() != null);
    }

    private Facts capture(String owner, Query query) {
        outside();
        var providers =
                sources.get().stream()
                        .filter(source -> source.owner().name().equals(owner))
                        .toList();
        require(providers.size() == 1);
        var facts = providers.getFirst().capture(query);
        validateFacts(facts, query);
        return facts;
    }

    private void validateFacts(Facts facts, Query query) {
        require(
                facts != null
                        && facts.data() != null
                        && query.equals(facts.data().query())
                        && facts.observedAt() != null
                        && !facts.observedAt().isAfter(clock.instant())
                        && facts.validUntil() != null
                        && facts.validUntil().isAfter(clock.instant())
                        && !facts.validUntil().isAfter(facts.observedAt().plusSeconds(30))
                        && facts.data().stage() != null
                        && facts.data().occurredAt() != null
                        && !facts.data().occurredAt().isAfter(clock.instant())
                        && !facts.data().lines().isEmpty()
                        && facts.data().lines().size() <= 1000);
        CommerceValidation.text(facts.verificationReceiptRef(), 256);
        CommerceValidation.text(facts.data().sourceReceiptRef(), 256);
    }

    private OperationVO persist(
            String entity,
            String store,
            String actor,
            FulfillmentContracts.Publish request,
            Facts facts) {
        var before = orders.scoped(entity, store, request.channelOrderNo(), false);
        var c = orders.db.connection(entity, store, before.getConnectionNo(), true, false);
        var order = orders.scoped(entity, store, request.channelOrderNo(), true);
        business(order, request.sourceOwner().name());
        var sourceQuery =
                new Query(
                        entity,
                        store,
                        order.getSourceOrderRef(),
                        order.getCoreOrderRef(),
                        request.sourceFulfillmentRef(),
                        request.sourceRevision());
        validateFacts(facts, sourceQuery);
        var existing =
                links.selectOne(
                        Wrappers.<CimFulfillmentLink>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", entity)
                                .eq("channel_order_no", order.getChannelOrderNo())
                                .eq("source_owner", order.getSourceOwner())
                                .eq("source_fulfillment_ref", request.sourceFulfillmentRef())
                                .eq("source_revision", request.sourceRevision()));
        if (existing != null) {
            require(existing.getSourceHash().equals(PricingSnapshotHash.of(facts.data())));
            var original =
                    orders.db.operations.selectOne(
                            Wrappers.<CimOperation>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", entity)
                                    .eq("operation_no", existing.getOperationNo()));
            require(original != null);
            return ChannelOperationServiceImpl.view(original, null);
        }
        require(
                "ACTIVE".equals(c.account().getStatus())
                        && "ACTIVE".equals(c.connection().getStatus())
                        && order.getConnectionRevisionNo()
                                .equals(c.connection().getActiveRevisionNo()));
        var grant =
                orders.db.authorizations.selectOne(
                        Wrappers.<CimAuthorizationRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", entity)
                                .eq("account_no", c.account().getAccountNo())
                                .eq("authorization_no", c.account().getActiveAuthorizationNo()));
        require(
                grant != null
                        && "GRANTED".equals(grant.getStatus())
                        && grant.getCredentialRef() != null
                        && (grant.getExpiresAt() == null || grant.getExpiresAt().isAfter(now())));
        var scopes = orders.db.json.readTree(grant.getGrantedScopes());
        require(scopes.isArray());
        boolean allowed = false;
        for (var scope : scopes)
            if (scope.isString() && "FULFILLMENT".equals(scope.asString())) allowed = true;
        require(allowed);
        var original =
                orders.db.json.readValue(
                        order.getImportSnapshot(), ChannelOrderIngestPort.Command.class);
        var mapped = map(order, original, facts);
        var quantities = new HashMap<String, BigDecimal>();
        var units = new HashMap<String, String>();
        for (var mapping : mapped) {
            var externalLineRef = mapping.link().getExternalLineRef();
            var originalLines =
                    original.original().document().lines().stream()
                            .filter(line -> externalLineRef.equals(line.lineRef()))
                            .toList();
            require(originalLines.size() == 1);
            String originalUnit = originalLines.getFirst().unit();
            require(SourceQuantityUnits.same(mapping.source().unit(), originalUnit));
            quantities.merge(
                    mapping.link().getExternalLineRef(),
                    new BigDecimal(mapping.source().quantity()),
                    BigDecimal::add);
            // Restore only the supplier's original label; canonical source quantities never change.
            units.put(externalLineRef, originalUnit);
        }
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
        var operationNo = BizKey.next("CIOP");
        var publication =
                new FulfillmentPublication(
                        operationNo,
                        scope,
                        original.original().documentHash(),
                        request.sourceFulfillmentRef(),
                        request.sourceRevision(),
                        facts.data().stage(),
                        facts.data().packageRef(),
                        facts.data().carrierCode(),
                        facts.data().trackingNo(),
                        quantities.entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .map(
                                        entry ->
                                                new FulfillmentLine(
                                                        entry.getKey(),
                                                        entry.getValue()
                                                                .stripTrailingZeros()
                                                                .toPlainString(),
                                                        units.get(entry.getKey())))
                                .toList(),
                        facts.data().occurredAt(),
                        facts.data().sourceReceiptRef());
        ChannelFulfillmentPolicy.validate(publication, original.original().document(), clock);
        var gateway = gateways.get(scope.profileNo());
        require(
                gateway != null
                        && gateway.supportsFulfillment(
                                original.original().document().fulfillment(), publication.stage()));
        var link = audit(new CimFulfillmentLink(), actor);
        link.setLinkNo(BizKey.next("CIFL"));
        link.setEntityNo(entity);
        link.setChannelOrderNo(order.getChannelOrderNo());
        link.setSourceOwner(order.getSourceOwner());
        link.setSourceFulfillmentRef(request.sourceFulfillmentRef());
        link.setSourceRevision(request.sourceRevision());
        link.setStage(publication.stage().name());
        link.setFulfillmentType(original.original().document().fulfillment().name());
        link.setOperationNo(operationNo);
        link.setStatus("QUEUED");
        link.setSourceHash(PricingSnapshotHash.of(facts.data()));
        link.setSourceSnapshot(orders.db.json.writeValueAsString(facts.data()));
        link.setPayloadJson(orders.db.json.writeValueAsString(publication));
        link.setConnectionRevisionNo(order.getConnectionRevisionNo());
        links.insert(link);
        for (var mapping : mapped) {
            var line = audit(new CimFulfillmentLineLink(), actor);
            line.setLinkNo(BizKey.next("CIFD"));
            line.setEntityNo(entity);
            line.setFulfillmentLinkNo(link.getLinkNo());
            line.setOrderLineLinkNo(mapping.link().getLinkNo());
            line.setQuantity(new BigDecimal(mapping.source().quantity()));
            line.setUnit(mapping.source().unit());
            line.setSourceReceiptRef(facts.data().sourceReceiptRef());
            lines.insert(line);
        }
        var op = audit(new CimOperation(), actor);
        op.setOperationNo(operationNo);
        op.setEntityNo(entity);
        op.setStoreNo(store);
        op.setConnectionNo(order.getConnectionNo());
        op.setAccountNo(c.account().getAccountNo());
        op.setProfileNo(scope.profileNo());
        op.setDestination("CHANNEL");
        op.setOperationType("FULFILLMENT_PUBLISH");
        op.setBusinessRef(link.getLinkNo());
        op.setBusinessRevision(request.sourceRevision());
        op.setSemanticKey(ChannelIdentity.hash(order.getChannelOrderNo() + ":" + link.getLinkNo()));
        op.setRequestHash(PricingSnapshotHash.of(publication));
        op.setRequestRef("FULFILLMENT:" + link.getLinkNo());
        op.setStatus("QUEUED");
        op.setCurrentAttemptNo(0L);
        op.setFence(0L);
        op.setNextRecoveryAt(now());
        op.setTargetScopeKind("CONNECTION");
        op.setTargetScopeKey(ChannelIdentity.hash(order.getChannelStoreKey()));
        orders.db.operations.insert(op);
        return ChannelOperationServiceImpl.view(op, null);
    }

    private List<Mapped> map(
            CimOrderLink order, ChannelOrderIngestPort.Command original, Facts facts) {
        require(orders.lineLinks != null);
        var allocations =
                orders.lineLinks.selectList(
                        Wrappers.<CimOrderLineLink>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", order.getEntityNo())
                                .eq("channel_order_no", order.getChannelOrderNo())
                                .eq("order_revision_no", original.externalSnapshotNo())
                                .eq("component_kind", "MAIN")
                                .last("LIMIT 10001"));
        require(!allocations.isEmpty() && allocations.size() <= 10000);
        var result = new ArrayList<Mapped>();
        var seen = new HashSet<String>();
        for (var line : facts.data().lines()) {
            require(line != null);
            var matches =
                    allocations.stream()
                            .filter(
                                    a ->
                                            a.getSourceLineRef().equals(line.sourceLineRef())
                                                    && a.getComponentRef()
                                                            .equals(line.componentRef())
                                                    && a.getCoreItemRef()
                                                            .equals(line.coreItemRef()))
                            .toList();
            require(matches.size() == 1);
            var allocation = matches.getFirst();
            require(
                    seen.add(allocation.getLinkNo())
                            && line.unit().equals(allocation.getQuantityUnit()));
            CommerceValidation.text(line.quantity(), 32);
            require(line.quantity().matches("(?:0|[1-9][0-9]{0,13})(?:\\.[0-9]{1,6})?"));
            var quantity = new BigDecimal(line.quantity());
            require(quantity.signum() > 0 && quantity.compareTo(allocation.getSaleQuantity()) <= 0);
            result.add(new Mapped(line, allocation));
        }
        return result;
    }

    /**
     * Only the existing recovery scheduler invokes this contribution. SEND occurs once; uncertainty
     * permits QUERY only.
     */
    @Override
    public int recoverDue(int limit) {
        require(limit >= 1 && limit <= 100);
        var due =
                orders.db.commands.query(
                        () ->
                                orders.db.operations.selectList(
                                        Wrappers.<CimOperation>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq("operation_type", "FULFILLMENT_PUBLISH")
                                                .in("status", "QUEUED", "RUNNING", "UNKNOWN")
                                                .le("next_recovery_at", now())
                                                .orderByAsc("next_recovery_at", "id")
                                                .last("LIMIT " + limit)));
        int completed = 0;
        for (var candidate : due) {
            Claim claim = null;
            try {
                Facts fresh = null;
                if ("QUEUED".equals(candidate.getStatus())) {
                    var link =
                            orders.db.commands.query(
                                    () ->
                                            link(
                                                    candidate.getEntityNo(),
                                                    candidate.getBusinessRef(),
                                                    false));
                    var original =
                            orders.db.json.readValue(
                                    link.getSourceSnapshot(),
                                    ChannelFulfillmentSourcePort.Data.class);
                    fresh = capture(link.getSourceOwner(), original.query());
                }
                var proof = fresh;
                claim = orders.db.commands.atomic(() -> claim(candidate, proof));
                if (claim == null) continue;
                var result =
                        claim.query()
                                ? claim.gateway().queryFulfillment(claim.publication())
                                : claim.gateway().publishFulfillment(claim.publication());
                var current = claim;
                if (orders.db.commands.atomic(() -> finish(current, result))) completed++;
            } catch (RuntimeException unavailable) {
                if (claim != null) {
                    var failed = claim;
                    orders.db.commands.atomic(() -> finish(failed, null));
                } else defer(candidate);
            }
        }
        return completed;
    }

    private Claim claim(CimOperation candidate, Facts fresh) {
        var before = link(candidate.getEntityNo(), candidate.getBusinessRef(), false);
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
        var link = link(candidate.getEntityNo(), candidate.getBusinessRef(), true);
        if (!Set.of("QUEUED", "RUNNING", "UNKNOWN").contains(op.getStatus())
                || op.getNextRecoveryAt() == null
                || op.getNextRecoveryAt().isAfter(now())
                || op.getLeaseUntil() != null && op.getLeaseUntil().isAfter(now())) return null;
        var gateway = gateways.get(op.getProfileNo());
        require(gateway != null);
        var publication =
                orders.db.json.readValue(link.getPayloadJson(), FulfillmentPublication.class);
        require(
                op.getRequestHash().equals(PricingSnapshotHash.of(publication))
                        && op.getOperationNo().equals(publication.operationNo()));
        boolean query = !"QUEUED".equals(op.getStatus());
        if (!query) {
            business(order, link.getSourceOwner());
            var source =
                    orders.db.json.readValue(
                            link.getSourceSnapshot(), ChannelFulfillmentSourcePort.Data.class);
            validateFacts(fresh, source.query());
            require(link.getSourceHash().equals(PricingSnapshotHash.of(fresh.data())));
            require(
                    "ACTIVE".equals(c.account().getStatus())
                            && "ACTIVE".equals(c.connection().getStatus())
                            && link.getConnectionRevisionNo()
                                    .equals(c.connection().getActiveRevisionNo())
                            && publication
                                    .scope()
                                    .authorizationNo()
                                    .equals(c.account().getActiveAuthorizationNo()));
            var grant =
                    orders.db.authorizations.selectOne(
                            Wrappers.<CimAuthorizationRevision>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", op.getEntityNo())
                                    .eq("account_no", op.getAccountNo())
                                    .eq("authorization_no", publication.scope().authorizationNo()));
            require(
                    grant != null
                            && "GRANTED".equals(grant.getStatus())
                            && Objects.equals(
                                    grant.getCredentialRef(), publication.scope().credentialRef())
                            && (grant.getExpiresAt() == null
                                    || grant.getExpiresAt().isAfter(now())));
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
        return new Claim(link, op, publication, attempt, fence, query, gateway);
    }

    private boolean finish(Claim claim, PublicationResult result) {
        var scope = claim.publication().scope();
        orders.db.connection(scope.entityNo(), scope.storeNo(), scope.connectionNo(), true, false);
        orders.scoped(scope.entityNo(), scope.storeNo(), claim.link().getChannelOrderNo(), true);
        var op = operation(scope.entityNo(), claim.operation().getOperationNo());
        var link = link(scope.entityNo(), claim.link().getLinkNo(), true);
        var a =
                orders.db.attempts.selectOne(
                        Wrappers.<CimOperationAttempt>query()
                                .eq("tenant_no", "MAIN")
                                .eq("operation_no", op.getOperationNo())
                                .eq("attempt_no", claim.attempt())
                                .last("FOR UPDATE"));
        require(a != null);
        boolean valid =
                result != null
                        && result.status() != null
                        && op.getOperationNo().equals(result.operationNo())
                        && scope.externalOrderRef().equals(result.externalOrderRef())
                        && op.getRequestHash().equals(result.publicationHash());
        if (valid && result.status() != PublicationStatus.UNKNOWN) {
            try {
                CommerceValidation.text(result.originalReceiptRef(), 256);
            } catch (RuntimeException malformed) {
                valid = false;
            }
        }
        if (op.getFence() != claim.fence() || op.getCurrentAttemptNo() != claim.attempt()) {
            if (valid && result.originalReceiptRef() != null)
                orders.db.attempts.update(
                        Wrappers.<CimOperationAttempt>update()
                                .eq("tenant_no", "MAIN")
                                .eq("id", a.getId())
                                .isNull("late_evidence_ref")
                                .set("late_evidence_ref", result.originalReceiptRef())
                                .set("late_outcome", result.status().name()));
            return false;
        }
        boolean confirmed = valid && result.status() == PublicationStatus.CONFIRMED;
        boolean rejected = valid && result.status() == PublicationStatus.REJECTED;
        String status = confirmed ? "SUCCEEDED" : rejected ? "FAILED" : "UNKNOWN";
        var receipt = valid ? result.originalReceiptRef() : null;
        orders.db.attempts.update(
                Wrappers.<CimOperationAttempt>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", a.getId())
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
                        .set("lease_until", null)
                        .set("original_evidence_ref", receipt)
                        .set(
                                "next_recovery_at",
                                confirmed || rejected ? null : now().plusSeconds(30))
                        .setSql("version=version+1"));
        links.update(
                Wrappers.<CimFulfillmentLink>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", link.getId())
                        .set("status", status)
                        .set("tracking_evidence_ref", receipt)
                        .setSql("version=version+1"));
        return confirmed || rejected;
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
                                    .eq("status", "QUEUED")
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

    private CimFulfillmentLink link(String entity, String number, boolean lock) {
        var q =
                Wrappers.<CimFulfillmentLink>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", entity)
                        .eq("link_no", number);
        if (lock) q.last("FOR UPDATE");
        var link = links.selectOne(q);
        if (link == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return link;
    }

    @Override
    public PageData<FulfillmentContracts.Link> listFulfillments(
            String entity, String store, String order, int page, int size) {
        long offset = CommerceValidation.offset(page, size);
        return orders.db.commands.query(
                () -> {
                    orders.scoped(entity, store, order, false);
                    var q =
                            Wrappers.<CimFulfillmentLink>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", entity)
                                    .eq("channel_order_no", order);
                    long total = links.selectCount(q);
                    var rows =
                            links.selectList(
                                    q.orderByDesc("created_at", "id")
                                            .last("LIMIT " + size + " OFFSET " + offset));
                    var detailRows =
                            rows.isEmpty()
                                    ? List.<CimFulfillmentLineLink>of()
                                    : lines.selectList(
                                            Wrappers.<CimFulfillmentLineLink>query()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("entity_no", entity)
                                                    .in(
                                                            "fulfillment_link_no",
                                                            rows.stream()
                                                                    .map(
                                                                            CimFulfillmentLink
                                                                                    ::getLinkNo)
                                                                    .toList())
                                                    .orderByAsc("id")
                                                    .last("LIMIT " + (size * 1000 + 1)));
                    require(detailRows.size() <= size * 1000);
                    var grouped = new HashMap<String, List<CimFulfillmentLineLink>>();
                    for (var detail : detailRows)
                        grouped.computeIfAbsent(
                                        detail.getFulfillmentLinkNo(), ignored -> new ArrayList<>())
                                .add(detail);
                    return PageData.of(
                            rows.stream()
                                    .map(
                                            row -> {
                                                var details =
                                                        grouped.getOrDefault(
                                                                row.getLinkNo(), List.of());
                                                require(details.size() <= 1000);
                                                var publication =
                                                        orders.db.json.readValue(
                                                                row.getPayloadJson(),
                                                                FulfillmentPublication.class);
                                                return new FulfillmentContracts.Link(
                                                        row.getLinkNo(),
                                                        row.getChannelOrderNo(),
                                                        Owner.valueOf(row.getSourceOwner()),
                                                        row.getSourceFulfillmentRef(),
                                                        row.getSourceRevision(),
                                                        row.getStage(),
                                                        row.getOperationNo(),
                                                        row.getStatus(),
                                                        row.getTrackingEvidenceRef(),
                                                        publication.occurredAt(),
                                                        details.stream()
                                                                .map(
                                                                        line ->
                                                                                new FulfillmentContracts
                                                                                        .Line(
                                                                                        line
                                                                                                .getOrderLineLinkNo(),
                                                                                        line.getQuantity()
                                                                                                .stripTrailingZeros()
                                                                                                .toPlainString(),
                                                                                        line
                                                                                                .getUnit()))
                                                                .toList());
                                            })
                                    .toList(),
                            total,
                            page,
                            size);
                });
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                    "Original fulfillment calls require an external transaction boundary");
    }
}
