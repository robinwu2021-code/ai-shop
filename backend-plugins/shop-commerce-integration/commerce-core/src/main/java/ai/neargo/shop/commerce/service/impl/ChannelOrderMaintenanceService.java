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
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.commerce.policy.ChannelOrderPolicy;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelRecoveryPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.time.Clock;
import java.util.Set;

/** Read refresh and original-key recovery use the existing journal and sole recovery scheduler. */
public final class ChannelOrderMaintenanceService implements ChannelRecoveryPort {
    private static final Set<String> RECOVERABLE =
            Set.of(
                    "RECEIVED",
                    "WAITING_MAPPING",
                    "WAITING_OWNER",
                    "PREPARED",
                    "WAITING_DECISION",
                    "WAITING_CONFIRMATION",
                    "WAITING_CANCELLATION");
    private final ChannelOrderServiceImpl data;
    private final Clock clock;

    public ChannelOrderMaintenanceService(ChannelOrderServiceImpl data, Clock clock) {
        this.data = data;
        this.clock = clock;
    }

    @Override
    public String workflow() {
        return "CHANNEL_ORDER_READ";
    }

    private record ReadIntent(
            String orderNo, String connectionRevisionNo, ChannelOrderGateway.Query query) {}

    private record Claim(
            CimOperation operation,
            ReadIntent intent,
            long attempt,
            long fence,
            ChannelOrderGateway gateway) {}

    /**
     * The request commits a read intent; no provider call occurs while its journal transaction is
     * held.
     */
    public OperationVO refresh(
            String entity,
            String store,
            String number,
            String actor,
            ChannelOrderContracts.Refresh request,
            String key) {
        require(request != null && request.version() != null && request.version() >= 0);
        return data.db.commands.execute(
                entity,
                store,
                actor,
                "REFRESH_CHANNEL_ORDER:" + number,
                key,
                request,
                OperationVO.class,
                () -> {
                    var before = data.scoped(entity, store, number, false);
                    var c =
                            data.db.connection(
                                    entity, store, before.getConnectionNo(), true, false);
                    var row = data.scoped(entity, store, number, true);
                    require(row.getVersion().equals(request.version()));
                    var grant = grant(c, c.account().getActiveAuthorizationNo());
                    require(
                            "ACTIVE".equals(c.account().getStatus())
                                    && "ACTIVE".equals(c.connection().getStatus())
                                    && "GRANTED".equals(grant.getStatus())
                                    && (grant.getExpiresAt() == null
                                            || grant.getExpiresAt().isAfter(now())));
                    var scopes = data.db.json.readTree(grant.getGrantedScopes());
                    require(scopes.isArray());
                    boolean allowed = false;
                    for (var scope : scopes)
                        if (scope.isString() && "ORDER".equals(scope.asString())) allowed = true;
                    require(allowed && data.gateways.containsKey(c.connection().getProfileNo()));
                    var op = operation(c, number, actor, "CHANNEL_ORDER_REFRESH");
                    op.setBusinessRevision(row.getConnectionRevisionNo());
                    op.setRequestRef("AUTH:" + grant.getAuthorizationNo());
                    var intent =
                            new ReadIntent(
                                    number,
                                    row.getConnectionRevisionNo(),
                                    query(c, row.getExternalOrderRef(), grant));
                    op.setRequestHash(
                            ChannelIdentity.hash(data.db.json.writeValueAsString(intent)));
                    op.setStatus("QUEUED");
                    op.setNextRecoveryAt(now());
                    data.db.operations.insert(op);
                    return ChannelOperationServiceImpl.view(op, null);
                },
                OperationVO::operationNo);
    }

    /**
     * Successful scheduling means only that the original worker was woken, not that business
     * acceptance succeeded.
     */
    public OperationVO recoverImport(
            String entity,
            String store,
            String number,
            String actor,
            ChannelOrderContracts.Recover request,
            String key) {
        require(request != null && request.version() != null && request.version() >= 0);
        CommerceValidation.text(request.reason(), 256);
        return data.db.commands.execute(
                entity,
                store,
                actor,
                "RECOVER_CHANNEL_IMPORT:" + number,
                key,
                request,
                OperationVO.class,
                () -> {
                    var before = data.scoped(entity, store, number, false);
                    var c =
                            data.db.connection(
                                    entity, store, before.getConnectionNo(), true, false);
                    var row = data.scoped(entity, store, number, true);
                    require(
                            row.getVersion().equals(request.version())
                                    && RECOVERABLE.contains(row.getImportStatus())
                                    && (row.getLeaseUntil() == null
                                            || !row.getLeaseUntil().isAfter(now())));
                    var op = operation(c, number, actor, "CHANNEL_IMPORT_RECOVERY");
                    op.setBusinessRevision(row.getActiveRevisionNo());
                    op.setRequestHash(
                            ChannelIdentity.hash(data.db.json.writeValueAsString(request)));
                    op.setRequestRef("IMPORT:" + row.getImportRef());
                    op.setReason(request.reason());
                    op.setReasonCode("MERCHANT_RECOVERY");
                    op.setStatus("SUCCEEDED");
                    // Preserve all original references and supplier UNKNOWN barriers.
                    require(
                            data.orders.update(
                                            Wrappers.<CimOrderLink>update()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("id", row.getId())
                                                    .eq("version", row.getVersion())
                                                    .set(
                                                            "import_status",
                                                            "WAITING_DECISION"
                                                                            .equals(
                                                                                    row
                                                                                            .getImportStatus())
                                                                    ? "WAITING_OWNER"
                                                                    : row.getImportStatus())
                                                    .set("next_attempt_at", now())
                                                    .set("version", row.getVersion() + 1))
                                    == 1);
                    data.db.operations.insert(op);
                    return ChannelOperationServiceImpl.view(op, null);
                },
                OperationVO::operationNo);
    }

    private CimOperation operation(
            CatalogPersistence.Context c, String number, String actor, String type) {
        var op = audit(new CimOperation(), actor);
        op.setOperationNo(BizKey.next("CIOP"));
        op.setEntityNo(c.connection().getEntityNo());
        op.setStoreNo(c.connection().getStoreNo());
        op.setConnectionNo(c.connection().getConnectionNo());
        op.setAccountNo(c.account().getAccountNo());
        op.setProfileNo(c.connection().getProfileNo());
        op.setDestination("CHANNEL_ORDER_REFRESH".equals(type) ? "CHANNEL" : "ORIGINAL");
        op.setOperationType(type);
        op.setBusinessRef(number);
        op.setSemanticKey(ChannelIdentity.hash(op.getOperationNo()));
        op.setCurrentAttemptNo(0L);
        op.setFence(0L);
        op.setTargetScopeKind("CONNECTION");
        op.setTargetScopeKey(
                ChannelIdentity.hash(c.connection().getChannelStoreKey() + ":" + number));
        return op;
    }

    private CimAuthorizationRevision grant(CatalogPersistence.Context c, String authorization) {
        require(authorization != null);
        var grant =
                data.db.authorizations.selectOne(
                        Wrappers.<CimAuthorizationRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", c.connection().getEntityNo())
                                .eq("account_no", c.account().getAccountNo())
                                .eq("authorization_no", authorization));
        require(grant != null && grant.getCredentialRef() != null);
        return grant;
    }

    private ChannelOrderGateway.Query query(
            CatalogPersistence.Context c, String order, CimAuthorizationRevision grant) {
        return new ChannelOrderGateway.Query(
                c.connection().getEntityNo(),
                c.connection().getStoreNo(),
                c.connection().getConnectionNo(),
                c.connection().getProfileNo(),
                c.account().getExternalAccountRef(),
                c.connection().getExternalStoreRef(),
                order,
                null,
                grant.getAuthorizationNo(),
                grant.getCredentialRef());
    }

    /**
     * Failed reads remain retryable; a successful read atomically persists its immutable revision
     * and query attempt.
     */
    @Override
    public int recoverDue(int limit) {
        require(limit >= 1 && limit <= 100);
        var due =
                data.db.commands.query(
                        () ->
                                data.db.operations.selectList(
                                        Wrappers.<CimOperation>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq("operation_type", "CHANNEL_ORDER_REFRESH")
                                                .in("status", "QUEUED", "RUNNING", "UNKNOWN")
                                                .le("next_recovery_at", now())
                                                .orderByAsc("next_recovery_at", "id")
                                                .last("LIMIT " + limit)));
        int completed = 0;
        for (var candidate : due) {
            Claim claim = null;
            try {
                claim = data.db.commands.atomic(() -> claim(candidate));
                if (claim == null) continue;
                var snapshot = claim.gateway().readOrder(claim.intent().query());
                ChannelOrderPolicy.validate(claim.intent().query(), snapshot, clock);
                var current = claim;
                if (data.db.commands.atomic(() -> finish(current, snapshot))) completed++;
            } catch (RuntimeException unavailable) {
                if (claim != null) {
                    var failed = claim;
                    data.db.commands.atomic(() -> finish(failed, null));
                }
            }
        }
        return completed;
    }

    public record OriginalRead(
            ChannelOrderGateway.Snapshot snapshot, String operationNo, String attemptRef) {}

    /**
     * Industry proof reads use the same durable QUERY journal and recovery worker, never an
     * untracked provider call.
     */
    public OriginalRead readOriginal(String entity, String store, String number) {
        var before = data.db.commands.query(() -> data.scoped(entity, store, number, false));
        var op =
                refresh(
                        entity,
                        store,
                        number,
                        "SYSTEM",
                        new ChannelOrderContracts.Refresh(before.getVersion()),
                        BizKey.next("CIEQ"));
        var candidate =
                data.db.commands.query(
                        () ->
                                data.db.operations.selectOne(
                                        Wrappers.<CimOperation>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq("entity_no", entity)
                                                .eq("operation_no", op.operationNo())));
        require(candidate != null);
        var claim = data.db.commands.atomic(() -> claim(candidate));
        require(claim != null);
        ChannelOrderGateway.Snapshot snapshot;
        try {
            snapshot = claim.gateway().readOrder(claim.intent().query());
            ChannelOrderPolicy.validate(claim.intent().query(), snapshot, clock);
        } catch (RuntimeException unavailable) {
            data.db.commands.atomic(() -> finish(claim, null));
            throw unavailable;
        }
        require(data.db.commands.atomic(() -> finish(claim, snapshot)));
        var attempt =
                data.db.commands.query(
                        () ->
                                data.db.attempts.selectOne(
                                        Wrappers.<CimOperationAttempt>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq("entity_no", entity)
                                                .eq("operation_no", op.operationNo())
                                                .eq("attempt_no", claim.attempt())));
        require(
                attempt != null
                        && "SUCCEEDED".equals(attempt.getOutcome())
                        && attempt.getFinishedAt() != null
                        && snapshot.originalReceiptRef().equals(attempt.getResponseRef())
                        && snapshot.validUntil().isAfter(clock.instant()));
        return new OriginalRead(snapshot, op.operationNo(), attempt.getAttemptRef());
    }

    private Claim claim(CimOperation candidate) {
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
        var op = lock(candidate.getEntityNo(), candidate.getOperationNo());
        if (!Set.of("QUEUED", "RUNNING", "UNKNOWN").contains(op.getStatus())
                || op.getNextRecoveryAt() == null
                || op.getNextRecoveryAt().isAfter(now())
                || op.getLeaseUntil() != null && op.getLeaseUntil().isAfter(now())) {
            return null;
        }
        var gateway = data.gateways.get(op.getProfileNo());
        if (gateway == null) {
            data.db.operations.update(
                    Wrappers.<CimOperation>update()
                            .eq("tenant_no", "MAIN")
                            .eq("id", op.getId())
                            .set("next_recovery_at", now().plusMinutes(1))
                            .setSql("version=version+1"));
            return null;
        }
        require(op.getRequestRef() != null && op.getRequestRef().startsWith("AUTH:"));
        var grant = grant(c, op.getRequestRef().substring(5));
        var intent =
                new ReadIntent(
                        order.getChannelOrderNo(),
                        op.getBusinessRevision(),
                        query(c, order.getExternalOrderRef(), grant));
        require(
                op.getRequestHash()
                        .equals(ChannelIdentity.hash(data.db.json.writeValueAsString(intent))));
        long attempt = Math.addExact(op.getCurrentAttemptNo(), 1),
                fence = Math.addExact(op.getFence(), 1);
        var a = audit(new CimOperationAttempt(), "SYSTEM");
        a.setAttemptRef(BizKey.next("CIAT"));
        a.setEntityNo(op.getEntityNo());
        a.setOperationNo(op.getOperationNo());
        a.setAttemptNo(attempt);
        a.setFence(fence);
        a.setAttemptKind("QUERY");
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
                                        .set("fence", fence)
                                        .set("current_attempt_no", attempt)
                                        .set("status", "RUNNING")
                                        .set("lease_until", now().plusMinutes(2))
                                        .set("next_recovery_at", now().plusMinutes(2))
                                        .setSql("version=version+1"))
                        == 1);
        return new Claim(op, intent, attempt, fence, gateway);
    }

    private CimOperation lock(String entity, String operation) {
        var op =
                data.db.operations.selectOne(
                        Wrappers.<CimOperation>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", entity)
                                .eq("operation_no", operation)
                                .last("FOR UPDATE"));
        require(op != null);
        return op;
    }

    private boolean finish(Claim claim, ChannelOrderGateway.Snapshot snapshot) {
        var q = claim.intent().query();
        data.db.connection(q.entityNo(), q.storeNo(), q.connectionNo(), true, false);
        data.scoped(q.entityNo(), q.storeNo(), claim.intent().orderNo(), true);
        var op = lock(q.entityNo(), claim.operation().getOperationNo());
        var a =
                data.db.attempts.selectOne(
                        Wrappers.<CimOperationAttempt>query()
                                .eq("tenant_no", "MAIN")
                                .eq("operation_no", op.getOperationNo())
                                .eq("attempt_no", claim.attempt())
                                .last("FOR UPDATE"));
        require(a != null);
        if (op.getFence() != claim.fence() || op.getCurrentAttemptNo() != claim.attempt()) {
            if (snapshot != null)
                data.db.attempts.update(
                        Wrappers.<CimOperationAttempt>update()
                                .eq("tenant_no", "MAIN")
                                .eq("id", a.getId())
                                .isNull("late_evidence_ref")
                                .set("late_evidence_ref", snapshot.originalReceiptRef())
                                .set("late_outcome", "READ"));
            return false;
        }
        if (snapshot != null) {
            require(snapshot.validUntil().isAfter(clock.instant()));
            data.capture(
                    new ChannelOrderServiceImpl.Origin(
                            q.entityNo(),
                            q.storeNo(),
                            q.connectionNo(),
                            claim.intent().connectionRevisionNo(),
                            q.profileNo(),
                            q.externalAccountRef(),
                            q.externalStoreRef(),
                            null,
                            op.getOperationNo()),
                    snapshot);
        }
        String result = snapshot == null ? "UNKNOWN" : "SUCCEEDED";
        data.db.attempts.update(
                Wrappers.<CimOperationAttempt>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", a.getId())
                        .isNull("finished_at")
                        .set("finished_at", now())
                        .set("outcome", result)
                        .set(
                                "response_ref",
                                snapshot == null ? null : snapshot.originalReceiptRef()));
        data.db.operations.update(
                Wrappers.<CimOperation>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", op.getId())
                        .eq("fence", claim.fence())
                        .set("status", result)
                        .set("lease_until", null)
                        .set(
                                "original_evidence_ref",
                                snapshot == null ? null : snapshot.originalReceiptRef())
                        .set("next_recovery_at", snapshot == null ? now().plusSeconds(30) : null)
                        .setSql("version=version+1"));
        return snapshot != null;
    }
}
