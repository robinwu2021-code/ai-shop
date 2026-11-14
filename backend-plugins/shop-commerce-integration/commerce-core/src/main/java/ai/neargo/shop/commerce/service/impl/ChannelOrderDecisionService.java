package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.audit;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.now;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.require;

import ai.neargo.shop.commerce.dto.ChannelOrderContracts;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.commerce.entity.CimAuthorizationRevision;
import ai.neargo.shop.commerce.entity.CimOperation;
import ai.neargo.shop.commerce.entity.CimOperationAttempt;
import ai.neargo.shop.commerce.entity.CimOrderLink;
import ai.neargo.shop.commerce.entity.CimOrderRevision;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.commerce.policy.ChannelPreparationPolicy;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Action;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Decision;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.DecisionResult;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.DecisionStatus;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;
import ai.neargo.shop.spi.commerce.ChannelRecoveryPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Original preparation and supplier acceptance are independent durable stages. UNKNOWN only permits
 * an original query.
 */
public final class ChannelOrderDecisionService implements ChannelRecoveryPort {
    private static final Logger LOG = LoggerFactory.getLogger(ChannelOrderDecisionService.class);
    private final ChannelOrderServiceImpl data;
    private final Supplier<List<ChannelOrderIngestPort>> owners;

    public ChannelOrderDecisionService(ChannelOrderServiceImpl data) {
        this(data, List::of);
    }

    public ChannelOrderDecisionService(
            ChannelOrderServiceImpl data, Supplier<List<ChannelOrderIngestPort>> owners) {
        this.data = data;
        this.owners = owners;
    }

    @Override
    public String workflow() {
        return "CHANNEL_ORDER_DECISION";
    }

    private record Intent(
            String order, String revision, Action action, String reasonCode, String reason) {}

    private record Claim(
            CimOrderLink order,
            CimOperation operation,
            Decision decision,
            long attempt,
            long fence,
            ChannelOrderGateway gateway,
            boolean query) {}

    /** Queues a merchant decision; the request thread never calls the supplier. */
    public OperationVO accept(
            String entity,
            String store,
            String number,
            String actor,
            ChannelOrderContracts.Accept request,
            String key) {
        require(request != null && request.version() != null && request.version() >= 0);
        return data.db.commands.execute(
                entity,
                store,
                actor,
                "ACCEPT_CHANNEL_ORDER:" + number,
                key,
                request,
                OperationVO.class,
                () ->
                        queue(
                                entity,
                                store,
                                number,
                                actor,
                                request.version(),
                                Action.ACCEPT,
                                null,
                                null),
                OperationVO::operationNo);
    }

    /** A rejection reason is classified before any cancellation of the original preparation. */
    public OperationVO reject(
            String entity,
            String store,
            String number,
            String actor,
            ChannelOrderContracts.Reject request,
            String key) {
        require(request != null && request.version() != null && request.version() >= 0);
        require(
                request.reasonCode() != null
                        && Set.of(
                                        "UNAVAILABLE",
                                        "OUT_OF_STOCK",
                                        "STORE_CLOSED",
                                        "PREPARATION_CAPACITY",
                                        "INVALID_ORDER",
                                        "OTHER")
                                .contains(request.reasonCode()));
        CommerceValidation.text(request.reason(), 256);
        return data.db.commands.execute(
                entity,
                store,
                actor,
                "REJECT_CHANNEL_ORDER:" + number,
                key,
                request,
                OperationVO.class,
                () ->
                        queue(
                                entity,
                                store,
                                number,
                                actor,
                                request.version(),
                                Action.REJECT,
                                request.reasonCode(),
                                request.reason()),
                OperationVO::operationNo);
    }

    private OperationVO queue(
            String entity,
            String store,
            String number,
            String actor,
            long version,
            Action action,
            String code,
            String reason) {
        var before = data.scoped(entity, store, number, false);
        var c = data.db.connection(entity, store, before.getConnectionNo(), true, false);
        var row = data.scoped(entity, store, number, true);
        require(
                row.getVersion() == version
                        && row.getDecisionOperationNo() == null
                        && Set.of(
                                        "RECEIVED",
                                        "WAITING_MAPPING",
                                        "WAITING_OWNER",
                                        "PREPARED",
                                        "WAITING_DECISION")
                                .contains(row.getImportStatus()));
        var snapshot =
                data.db.json.readValue(
                        data.revision(row).getNormalizedSnapshot(),
                        ChannelOrderGateway.Snapshot.class);
        var gateway = data.gateways.get(c.connection().getProfileNo());
        require(
                gateway != null
                        && gateway.supportsDecision(snapshot.document().fulfillment(), action)
                        && snapshot.document().state()
                                == ChannelOrderGateway.State.WAITING_ACCEPTANCE);
        var op = audit(new CimOperation(), actor);
        op.setOperationNo(BizKey.next("CIOP"));
        op.setEntityNo(entity);
        op.setStoreNo(store);
        op.setConnectionNo(row.getConnectionNo());
        op.setAccountNo(c.account().getAccountNo());
        op.setProfileNo(c.connection().getProfileNo());
        op.setDestination("CHANNEL");
        op.setOperationType("CHANNEL_ORDER_" + action);
        op.setBusinessRef(number);
        op.setBusinessRevision(row.getActiveRevisionNo());
        op.setSemanticKey(ChannelIdentity.hash(row.getImportRef() + ":" + action));
        op.setRequestHash(
                ChannelIdentity.hash(
                        data.db.json.writeValueAsString(
                                new Intent(
                                        number, row.getActiveRevisionNo(), action, code, reason))));
        op.setRequestRef("ORDER:" + row.getActiveRevisionNo());
        op.setReasonCode(code);
        op.setReason(reason);
        op.setStatus("QUEUED");
        op.setCurrentAttemptNo(0L);
        op.setFence(0L);
        op.setNextRecoveryAt(now());
        op.setTargetScopeKind("CONNECTION");
        op.setTargetScopeKey(ChannelIdentity.hash(row.getChannelStoreKey() + ":" + number));
        data.db.operations.insert(op);
        String status =
                action == Action.ACCEPT && row.getImportSnapshot() != null
                        ? "PREPARED"
                        : row.getImportStatus();
        require(
                data.orders.update(
                                Wrappers.<CimOrderLink>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("id", row.getId())
                                        .eq("version", version)
                                        .set("decision_operation_no", op.getOperationNo())
                                        .set("import_status", status)
                                        .set("lease_until", null)
                                        .set("fence", row.getFence() + 1)
                                        .set("next_attempt_at", now())
                                        .set("version", version + 1))
                        == 1);
        return ChannelOperationServiceImpl.view(op, null);
    }

    @Override
    public int recoverDue(int limit) {
        require(limit >= 1 && limit <= 100);
        queueAutomatic(limit);
        var due =
                data.db.commands.query(
                        () ->
                                data.db.operations.selectList(
                                        Wrappers.<CimOperation>query()
                                                .eq("tenant_no", "MAIN")
                                                .in(
                                                        "operation_type",
                                                        "CHANNEL_ORDER_ACCEPT",
                                                        "CHANNEL_ORDER_REJECT")
                                                .in("status", "QUEUED", "RUNNING", "UNKNOWN")
                                                .le("next_recovery_at", now())
                                                .orderByAsc("next_recovery_at", "id")
                                                .last("LIMIT " + limit)));
        int count = 0;
        for (var row : due) {
            Claim claim;
            try {
                var prepared = freshPreparation(row);
                claim = data.db.commands.atomic(() -> claim(row, prepared));
            } catch (RuntimeException unavailable) {
                deferOperation(row);
                continue;
            }
            if (claim == null) continue;
            DecisionResult result = null;
            try {
                result =
                        claim.query()
                                ? claim.gateway().queryDecision(claim.decision())
                                : claim.gateway().decide(claim.decision());
            } catch (RuntimeException unavailable) {
                /* Original SEND remains uncertain; it is never repeated. */
            }
            var proof = result;
            try {
                if (data.db.commands.atomic(() -> finish(claim, proof))) count++;
            } catch (RuntimeException unavailable) {
                LOG.warn(
                        "Commerce order decision finish deferred for operation {}",
                        row.getOperationNo());
            }
        }
        return count;
    }

    private void queueAutomatic(int limit) {
        var due =
                data.db.commands.query(
                        () ->
                                data.orders.selectList(
                                        Wrappers.<CimOrderLink>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq("import_status", "PREPARED")
                                                .isNull("decision_operation_no")
                                                .le("next_attempt_at", now())
                                                .orderByAsc("next_attempt_at", "id")
                                                .last("LIMIT " + limit)));
        for (var row : due) {
            try {
                data.db.commands.atomic(
                        () -> {
                            var c =
                                    data.db.connection(
                                            row.getEntityNo(),
                                            row.getStoreNo(),
                                            row.getConnectionNo(),
                                            true,
                                            false);
                            var current =
                                    data.scoped(
                                            row.getEntityNo(),
                                            row.getStoreNo(),
                                            row.getChannelOrderNo(),
                                            true);
                            if (current.getDecisionOperationNo() != null
                                    || !"PREPARED".equals(current.getImportStatus())
                                    || !"AUTO".equals(c.revision().getAcceptMode())) return null;
                            queue(
                                    row.getEntityNo(),
                                    row.getStoreNo(),
                                    row.getChannelOrderNo(),
                                    "SYSTEM",
                                    current.getVersion(),
                                    Action.ACCEPT,
                                    null,
                                    null);
                            return null;
                        });
            } catch (RuntimeException unavailable) {
                try {
                    data.db.commands.atomic(
                            () -> {
                                data.orders.update(
                                        Wrappers.<CimOrderLink>update()
                                                .eq("tenant_no", "MAIN")
                                                .eq("entity_no", row.getEntityNo())
                                                .eq("channel_order_no", row.getChannelOrderNo())
                                                .eq("version", row.getVersion())
                                                .eq("import_status", "PREPARED")
                                                .isNull("decision_operation_no")
                                                .set("next_attempt_at", now().plusMinutes(1))
                                                .set("version", row.getVersion() + 1));
                                return null;
                            });
                } catch (RuntimeException persistenceUnavailable) {
                    LOG.warn(
                            "Commerce automatic acceptance deferred for order {}",
                            row.getChannelOrderNo());
                }
            }
        }
    }

    private void deferOperation(CimOperation candidate) {
        try {
            data.db.commands.atomic(
                    () -> {
                        data.db.operations.update(
                                Wrappers.<CimOperation>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("entity_no", candidate.getEntityNo())
                                        .eq("operation_no", candidate.getOperationNo())
                                        .eq("version", candidate.getVersion())
                                        .in("status", "QUEUED", "RUNNING", "UNKNOWN")
                                        .and(
                                                q ->
                                                        q.isNull("lease_until")
                                                                .or()
                                                                .le("lease_until", now()))
                                        .set("next_recovery_at", now().plusMinutes(1))
                                        .setSql("version=version+1"));
                        return null;
                    });
        } catch (RuntimeException unavailable) {
            LOG.warn(
                    "Commerce order decision claim deferred for operation {}",
                    candidate.getOperationNo());
        }
    }

    /**
     * The first ACCEPT SEND re-reads the actual owner outside locks; cached CIM deadlines alone
     * never grant readiness.
     */
    private ChannelOrderIngestPort.Prepared freshPreparation(CimOperation operation) {
        if (!"QUEUED".equals(operation.getStatus())
                || !"CHANNEL_ORDER_ACCEPT".equals(operation.getOperationType())) return null;
        var row =
                data.db.commands.query(
                        () ->
                                data.scoped(
                                        operation.getEntityNo(),
                                        operation.getStoreNo(),
                                        operation.getBusinessRef(),
                                        false));
        if (row.getImportSnapshot() == null
                || "CONFLICT".equals(row.getImportStatus())
                || !row.getActiveRevisionNo().equals(operation.getBusinessRevision())) return null;
        var command =
                data.db.json.readValue(
                        row.getImportSnapshot(), ChannelOrderIngestPort.Command.class);
        var reference =
                new ChannelOrderIngestPort.Reference(
                        command.importNo(),
                        command.entityNo(),
                        command.storeNo(),
                        command.original().documentHash());
        var matches =
                owners.get().stream()
                        .filter(owner -> owner.owner().name().equals(row.getSourceOwner()))
                        .toList();
        if (matches.size() != 1) return null;
        try {
            return matches.getFirst().getImport(reference);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private Claim claim(CimOperation candidate, ChannelOrderIngestPort.Prepared prepared) {
        var c =
                data.db.connection(
                        candidate.getEntityNo(),
                        candidate.getStoreNo(),
                        candidate.getConnectionNo(),
                        true,
                        false);
        var order =
                data.scoped(
                        candidate.getEntityNo(),
                        candidate.getStoreNo(),
                        candidate.getBusinessRef(),
                        true);
        var op =
                data.db.operations.selectOne(
                        Wrappers.<CimOperation>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", candidate.getEntityNo())
                                .eq("operation_no", candidate.getOperationNo())
                                .last("FOR UPDATE"));
        require(op != null);
        if (!Set.of("QUEUED", "RUNNING", "UNKNOWN").contains(op.getStatus())
                || op.getNextRecoveryAt() == null
                || op.getNextRecoveryAt().isAfter(now())
                || op.getLeaseUntil() != null && op.getLeaseUntil().isAfter(now())) return null;
        boolean query = !"QUEUED".equals(op.getStatus());
        var action = op.getOperationType().endsWith("ACCEPT") ? Action.ACCEPT : Action.REJECT;
        if (!query
                && ("CONFLICT".equals(order.getImportStatus())
                        || !order.getActiveRevisionNo().equals(op.getBusinessRevision()))) {
            data.db.operations.update(
                    Wrappers.<CimOperation>update()
                            .eq("tenant_no", "MAIN")
                            .eq("id", op.getId())
                            .set("status", "SUPERSEDED")
                            .set("next_recovery_at", null));
            return null;
        }
        ChannelOrderIngestPort.Reference originalReference = null;
        if (order.getImportSnapshot() != null) {
            var original =
                    data.db.json.readValue(
                            order.getImportSnapshot(), ChannelOrderIngestPort.Command.class);
            originalReference =
                    new ChannelOrderIngestPort.Reference(
                            original.importNo(),
                            original.entityNo(),
                            original.storeNo(),
                            original.original().documentHash());
        }
        if (!query
                && action == Action.ACCEPT
                && (originalReference == null
                        || !ChannelPreparationPolicy.ready(
                                prepared, originalReference, now().toInstant(ZoneOffset.UTC)))) {
            // Re-enter original preparation. This never retries a supplier action already sent.
            data.orders.update(
                    Wrappers.<CimOrderLink>update()
                            .eq("tenant_no", "MAIN")
                            .eq("id", order.getId())
                            .set("import_status", "WAITING_OWNER")
                            .set("next_attempt_at", now())
                            .set("version", order.getVersion() + 1));
            later(op);
            return null;
        }
        if (!query && action == Action.ACCEPT) {
            require(
                    data.orders.update(
                                    Wrappers.<CimOrderLink>update()
                                            .eq("tenant_no", "MAIN")
                                            .eq("id", order.getId())
                                            .eq("version", order.getVersion())
                                            .set(
                                                    "preparation_receipt_ref",
                                                    prepared.preparationReceiptRef())
                                            .set(
                                                    "preparation_valid_until",
                                                    prepared.validUntil()
                                                            .atOffset(ZoneOffset.UTC)
                                                            .toLocalDateTime())
                                            .setSql("version=version+1"))
                            == 1);
            order.setVersion(order.getVersion() + 1);
        }
        var gateway = data.gateways.get(op.getProfileNo());
        if (gateway == null) {
            later(op);
            return null;
        }
        var revision =
                data.revisions.selectOne(
                        Wrappers.<CimOrderRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", op.getEntityNo())
                                .eq("channel_order_no", order.getChannelOrderNo())
                                .eq("revision_no", op.getBusinessRevision()));
        require(revision != null);
        var snapshot =
                data.db.json.readValue(
                        revision.getNormalizedSnapshot(), ChannelOrderGateway.Snapshot.class);
        require(gateway.supportsDecision(snapshot.document().fulfillment(), action));
        if (query) require(op.getRequestRef() != null && op.getRequestRef().startsWith("AUTH:"));
        String authorization =
                query
                        ? op.getRequestRef().substring("AUTH:".length())
                        : c.account().getActiveAuthorizationNo();
        var grant =
                data.db.authorizations.selectOne(
                        Wrappers.<CimAuthorizationRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", op.getEntityNo())
                                .eq("account_no", op.getAccountNo())
                                .eq("authorization_no", authorization));
        require(grant != null && grant.getCredentialRef() != null);
        if (!query) {
            require(
                    "ACTIVE".equals(c.connection().getStatus())
                            && "ACTIVE".equals(c.account().getStatus())
                            && "GRANTED".equals(grant.getStatus())
                            && (grant.getExpiresAt() == null || grant.getExpiresAt().isAfter(now()))
                            && order.getConnectionRevisionNo()
                                    .equals(c.connection().getActiveRevisionNo()));
            var scopes = data.db.json.readTree(grant.getGrantedScopes());
            boolean allowed = false;
            require(scopes.isArray());
            for (var scope : scopes)
                if (scope.isString() && "ORDER".equals(scope.asString())) allowed = true;
            require(allowed);
        }
        var scope =
                new ChannelOrderGateway.Query(
                        order.getEntityNo(),
                        order.getStoreNo(),
                        order.getConnectionNo(),
                        op.getProfileNo(),
                        c.account().getExternalAccountRef(),
                        c.connection().getExternalStoreRef(),
                        order.getExternalOrderRef(),
                        snapshot.document().revisionRef(),
                        grant.getAuthorizationNo(),
                        grant.getCredentialRef());
        var decision =
                new Decision(
                        op.getOperationNo(),
                        scope,
                        snapshot.documentHash(),
                        snapshot.document().revisionRef(),
                        action,
                        op.getReasonCode(),
                        op.getReason());
        String requestHash = ChannelIdentity.hash(data.db.json.writeValueAsString(decision));
        if (query)
            require(
                    op.getRequestRef().startsWith("AUTH:")
                            && requestHash.equals(op.getRequestHash()));
        long attempt = op.getCurrentAttemptNo() + 1, fence = op.getFence() + 1;
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
        data.db.attempts.insert(a);
        require(
                data.db.operations.update(
                                Wrappers.<CimOperation>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("id", op.getId())
                                        .eq("fence", op.getFence())
                                        .set("status", "RUNNING")
                                        .set("current_attempt_no", attempt)
                                        .set("fence", fence)
                                        .set("request_hash", requestHash)
                                        .set("request_ref", "AUTH:" + authorization)
                                        .set("lease_until", now().plusMinutes(2))
                                        .set("next_recovery_at", now().plusMinutes(2))
                                        .setSql("version=version+1"))
                        == 1);
        return new Claim(order, op, decision, attempt, fence, gateway, query);
    }

    /**
     * Only a never-dispatched intent may follow an equivalent payment/status revision; unknown
     * actions stay frozen.
     */
    boolean retargetUnsent(CimOrderLink order, String revision) {
        if (order.getDecisionOperationNo() == null) return false;
        var op =
                data.db.operations.selectOne(
                        Wrappers.<CimOperation>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", order.getEntityNo())
                                .eq("operation_no", order.getDecisionOperationNo())
                                .last("FOR UPDATE"));
        if (op == null
                || !"QUEUED".equals(op.getStatus())
                || op.getCurrentAttemptNo() != 0
                || op.getLeaseUntil() != null && op.getLeaseUntil().isAfter(now())) return false;
        require(
                data.db.attempts.selectCount(
                                Wrappers.<CimOperationAttempt>query()
                                        .eq("tenant_no", "MAIN")
                                        .eq("entity_no", order.getEntityNo())
                                        .eq("operation_no", op.getOperationNo()))
                        == 0);
        var action = op.getOperationType().endsWith("ACCEPT") ? Action.ACCEPT : Action.REJECT;
        require(
                data.db.operations.update(
                                Wrappers.<CimOperation>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("id", op.getId())
                                        .eq("version", op.getVersion())
                                        .eq("status", "QUEUED")
                                        .eq("current_attempt_no", 0)
                                        .set("business_revision", revision)
                                        .set("request_ref", "ORDER:" + revision)
                                        .set(
                                                "request_hash",
                                                ChannelIdentity.hash(
                                                        data.db.json.writeValueAsString(
                                                                new Intent(
                                                                        order.getChannelOrderNo(),
                                                                        revision,
                                                                        action,
                                                                        op.getReasonCode(),
                                                                        op.getReason()))))
                                        .set("next_recovery_at", now())
                                        .setSql("version=version+1"))
                        == 1);
        return true;
    }

    private void later(CimOperation op) {
        data.db.operations.update(
                Wrappers.<CimOperation>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", op.getId())
                        .set("next_recovery_at", now().plusMinutes(1))
                        .setSql("version=version+1"));
    }

    private boolean finish(Claim claim, DecisionResult raw) {
        data.db.connection(
                claim.order().getEntityNo(),
                claim.order().getStoreNo(),
                claim.order().getConnectionNo(),
                true,
                false);
        var order =
                data.scoped(
                        claim.order().getEntityNo(),
                        claim.order().getStoreNo(),
                        claim.order().getChannelOrderNo(),
                        true);
        var op =
                data.db.operations.selectOne(
                        Wrappers.<CimOperation>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", order.getEntityNo())
                                .eq("operation_no", claim.operation().getOperationNo())
                                .last("FOR UPDATE"));
        var a =
                data.db.attempts.selectOne(
                        Wrappers.<CimOperationAttempt>query()
                                .eq("tenant_no", "MAIN")
                                .eq("operation_no", op.getOperationNo())
                                .eq("attempt_no", claim.attempt())
                                .last("FOR UPDATE"));
        require(a != null);
        boolean valid =
                raw != null
                        && op.getOperationNo().equals(raw.operationNo())
                        && claim.decision()
                                .scope()
                                .externalOrderRef()
                                .equals(raw.externalOrderRef())
                        && claim.decision().revisionRef().equals(raw.revisionRef())
                        && claim.decision().action() == raw.action()
                        && raw.status() != null;
        if (valid && (raw.status() != DecisionStatus.UNKNOWN || raw.originalReceiptRef() != null)) {
            try {
                CommerceValidation.text(raw.originalReceiptRef(), 256);
            } catch (RuntimeException bad) {
                valid = false;
            }
        }
        if (op.getFence() != claim.fence() || op.getCurrentAttemptNo() != claim.attempt()) {
            if (valid && raw.originalReceiptRef() != null)
                data.db.attempts.update(
                        Wrappers.<CimOperationAttempt>update()
                                .eq("tenant_no", "MAIN")
                                .eq("id", a.getId())
                                .isNull("late_evidence_ref")
                                .set("late_evidence_ref", raw.originalReceiptRef())
                                .set("late_outcome", raw.status().name()));
            return false;
        }
        boolean confirmed = valid && raw.status() == DecisionStatus.CONFIRMED;
        boolean rejected = valid && raw.status() == DecisionStatus.REJECTED;
        String status = confirmed ? "SUCCEEDED" : rejected ? "FAILED" : "UNKNOWN";
        data.db.attempts.update(
                Wrappers.<CimOperationAttempt>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", a.getId())
                        .isNull("finished_at")
                        .set("finished_at", now())
                        .set("outcome", status)
                        .set("response_ref", valid ? raw.originalReceiptRef() : null)
                        .set("error_code", valid ? null : "UNCONFIRMED_RESPONSE"));
        data.db.operations.update(
                Wrappers.<CimOperation>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", op.getId())
                        .eq("fence", claim.fence())
                        .set("status", status)
                        .set("lease_until", null)
                        .set("original_evidence_ref", valid ? raw.originalReceiptRef() : null)
                        .set(
                                "next_recovery_at",
                                confirmed || rejected ? null : now().plusSeconds(30))
                        .setSql("version=version+1"));
        if (confirmed) {
            boolean accept = claim.decision().action() == Action.ACCEPT;
            boolean conflict = "CONFLICT".equals(order.getImportStatus());
            String businessStatus =
                    conflict
                            ? "CONFLICT"
                            : accept
                                    ? "WAITING_CONFIRMATION"
                                    : order.getImportSnapshot() == null
                                            ? "REJECTED"
                                            : "WAITING_CANCELLATION";
            var update =
                    Wrappers.<CimOrderLink>update()
                            .eq("tenant_no", "MAIN")
                            .eq("id", order.getId())
                            .set(
                                    "acceptance_status",
                                    accept ? "PLATFORM_CONFIRMED" : "PLATFORM_REJECTED")
                            .set("import_status", businessStatus)
                            .set("next_attempt_at", conflict ? null : now())
                            .set("lease_until", null)
                            .set("fence", order.getFence() + 1)
                            .set("version", order.getVersion() + 1);
            if (accept) update.set("platform_acceptance_receipt_ref", raw.originalReceiptRef());
            data.orders.update(update);
        }
        return confirmed || rejected;
    }
}
