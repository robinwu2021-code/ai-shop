package ai.neargo.shop.commerce.service.impl;

import ai.neargo.shop.commerce.dto.ChannelConnectionVO;
import ai.neargo.shop.commerce.dto.ConnectionLifecycleContracts.Configuration;
import ai.neargo.shop.commerce.dto.ConnectionLifecycleContracts.SetStatus;
import ai.neargo.shop.commerce.dto.ConnectionLifecycleContracts.Verify;
import ai.neargo.shop.commerce.dto.CreateChannelConnectionReq;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.commerce.entity.CimAccount;
import ai.neargo.shop.commerce.entity.CimAuthorizationRevision;
import ai.neargo.shop.commerce.entity.CimConnectionRevision;
import ai.neargo.shop.commerce.entity.CimOperation;
import ai.neargo.shop.commerce.entity.CimOperationAttempt;
import ai.neargo.shop.commerce.entity.CimProductProfile;
import ai.neargo.shop.commerce.entity.CimStoreConnection;
import ai.neargo.shop.commerce.mapper.CommerceMappers.AccountMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.AuthorizationRevisionMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ConnectionRevisionMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.OperationAttemptMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.OperationMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ProductProfileMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.StoreConnectionMapper;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.commerce.service.ChannelConnectionService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort.Identity;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort.Proof;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort.Status;
import ai.neargo.shop.spi.commerce.ChannelRecoveryPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** 稳定远端身份永久唯一；首批只保存草稿，不产生平台接单或履约指令。 */
public class ChannelConnectionServiceImpl implements ChannelConnectionService, ChannelRecoveryPort {
    private final OperationMapper operations;
    private final OperationAttemptMapper attempts;
    private final AuthorizationRevisionMapper grants;
    private final StoreConnectionMapper connections;
    private final ConnectionRevisionMapper revisions;
    private final AccountMapper accounts;
    private final ProductProfileMapper profiles;
    private final CommerceCommands commands;
    private final Supplier<ChannelConnectionIdentityPort> authority;

    public ChannelConnectionServiceImpl(
            StoreConnectionMapper connections,
            ConnectionRevisionMapper revisions,
            AccountMapper accounts,
            ProductProfileMapper profiles,
            CommerceCommands commands,
            Supplier<ChannelConnectionIdentityPort> authority) {
        this(connections, revisions, accounts, profiles, commands, authority, null, null, null);
    }

    public ChannelConnectionServiceImpl(
            StoreConnectionMapper connections,
            ConnectionRevisionMapper revisions,
            AccountMapper accounts,
            ProductProfileMapper profiles,
            CommerceCommands commands,
            Supplier<ChannelConnectionIdentityPort> authority,
            OperationMapper operations,
            OperationAttemptMapper attempts,
            AuthorizationRevisionMapper grants) {
        this.connections = connections;
        this.revisions = revisions;
        this.accounts = accounts;
        this.profiles = profiles;
        this.commands = commands;
        this.authority = authority;
        this.operations = operations;
        this.attempts = attempts;
        this.grants = grants;
    }

    @Override
    public ChannelConnectionVO createConnection(
            String entity, String store, String actor, CreateChannelConnectionReq req, String key) {
        CommerceValidation.text(store, 32);
        if (req == null
                || req.sourceOwner() == null
                || !Set.of("INSTANT", "PARCEL", "REDEMPTION")
                        .contains(
                                req.fulfillmentProfile() == null ? "" : req.fulfillmentProfile())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        CommerceValidation.text(req.accountNo(), 32);
        CommerceValidation.text(req.externalStoreRef(), 128);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw BizException.of(ErrorCode.CONFLICT);
        var replay =
                commands.replay(
                        entity,
                        store,
                        actor,
                        "CREATE_CONNECTION",
                        key,
                        req,
                        ChannelConnectionVO.class);
        if (replay.isPresent()) return replay.get();
        Identity identity = commands.query(() -> identity(entity, store, req, false));
        var verifier = authority.get();
        if (verifier == null) throw BizException.of(ErrorCode.CONFLICT);
        Proof proof = verifier.verify(identity);
        requireProof(identity, proof);

        return commands.execute(
                entity,
                store,
                actor,
                "CREATE_CONNECTION",
                key,
                req,
                ChannelConnectionVO.class,
                () -> {
                    Identity current = identity(entity, store, req, true);
                    if (!identity.equals(current)) throw BizException.of(ErrorCode.CONFLICT);
                    requireProof(current, proof);
                    var row = new CimStoreConnection();
                    row.setTenantNo("MAIN");
                    row.setEntityNo(entity);
                    row.setStoreNo(store);
                    row.setConnectionNo(BizKey.next("CICN"));
                    row.setAccountNo(identity.accountNo());
                    row.setProfileNo(identity.profileNo());
                    row.setExternalStoreRef(req.externalStoreRef());
                    row.setChannelStoreKey(
                            ChannelIdentity.storeKey(
                                    identity.identityNamespace(),
                                    identity.accountScoped(),
                                    identity.externalAccountRef(),
                                    req.externalStoreRef()));
                    row.setIdentityReceiptRef(proof.receiptRef());
                    row.setIdentityVerifiedAt(
                            LocalDateTime.ofInstant(proof.verifiedAt(), ZoneOffset.UTC));
                    row.setIdentityValidUntil(
                            LocalDateTime.ofInstant(proof.validUntil(), ZoneOffset.UTC));
                    row.setStatus("DRAFT");
                    row.setVersion(0L);
                    row.setDeleted(0);
                    row.setCreatedBy(actor);
                    row.setUpdatedBy(actor);
                    var revision = new CimConnectionRevision();
                    revision.setTenantNo("MAIN");
                    revision.setEntityNo(entity);
                    revision.setConnectionNo(row.getConnectionNo());
                    revision.setRevisionNo(BizKey.next("CICR"));
                    revision.setRevision(1L);
                    revision.setSourceOwner(req.sourceOwner().name());
                    revision.setAcceptMode("MANUAL");
                    revision.setFulfillmentProfile(req.fulfillmentProfile());
                    revision.setCatalogAuthority("LOCAL");
                    revision.setConfigJson("{}");
                    revision.setConfigHash(
                            ChannelIdentity.hash(
                                    req.sourceOwner().name()
                                            + ":MANUAL:"
                                            + req.fulfillmentProfile()
                                            + ":LOCAL:{}"));
                    revision.setCreatedBy(actor);
                    revision.setUpdatedBy(actor);
                    revision.setVersion(0L);
                    revision.setDeleted(0);
                    row.setActiveRevisionNo(revision.getRevisionNo());
                    row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
                    row.setUpdatedAt(row.getCreatedAt());
                    revision.setCreatedAt(row.getCreatedAt());
                    revision.setUpdatedAt(row.getCreatedAt());
                    connections.insert(row);
                    revisions.insert(revision);
                    return view(row, revision);
                },
                ChannelConnectionVO::connectionNo);
    }

    @Override
    public String workflow() {
        return "VERIFY_CONNECTION";
    }

    @Override
    public ChannelConnectionVO saveConfiguration(
            String entity,
            String store,
            String number,
            String actor,
            Configuration request,
            String key) {
        if (request == null
                || request.version() == null
                || request.version() < 0
                || !Set.of("MANUAL", "AUTO").contains(request.acceptMode())
                || !Set.of("INSTANT", "PARCEL", "REDEMPTION").contains(request.fulfillmentProfile())
                || !Set.of("LOCAL", "REMOTE").contains(request.catalogAuthority())) bad();
        if (request.sourceRoutingRef() != null)
            CommerceValidation.text(request.sourceRoutingRef(), 128);
        return commands.execute(
                entity,
                store,
                actor,
                "CONFIGURE_CONNECTION:" + number,
                key,
                request,
                ChannelConnectionVO.class,
                () -> {
                    var c = existing(entity, store, number, true);
                    if (!request.version().equals(c.row().getVersion())
                            || !Set.of("DRAFT", "SUSPENDED").contains(c.row().getStatus()))
                        conflict();
                    noPending(number);
                    var next = CatalogPersistence.audit(new CimConnectionRevision(), actor);
                    next.setRevisionNo(BizKey.next("CICR"));
                    next.setEntityNo(entity);
                    next.setConnectionNo(number);
                    next.setRevision(c.revision().getRevision() + 1);
                    next.setSourceOwner(c.revision().getSourceOwner());
                    next.setAcceptMode(request.acceptMode());
                    next.setFulfillmentProfile(request.fulfillmentProfile());
                    next.setCatalogAuthority(request.catalogAuthority());
                    next.setSourceRoutingRef(request.sourceRoutingRef());
                    String data = new ObjectMapper().writeValueAsString(request);
                    next.setConfigJson(data);
                    next.setConfigHash(PricingSnapshotHash.of(request));
                    revisions.insert(next);
                    connections.update(
                            Wrappers.<CimStoreConnection>update()
                                    .eq("tenant_no", "MAIN")
                                    .eq("id", c.row().getId())
                                    .eq("version", request.version())
                                    .set("active_revision_no", next.getRevisionNo())
                                    .set("version", request.version() + 1)
                                    .set("updated_by", actor)
                                    .set("updated_at", LocalDateTime.now(ZoneOffset.UTC)));
                    return getConnection(entity, store, number);
                },
                ChannelConnectionVO::connectionNo);
    }

    @Override
    public OperationVO verify(
            String entity, String store, String number, String actor, Verify request, String key) {
        requireLifecycle();
        if (request == null || request.version() == null || request.version() < 0) bad();
        var replay =
                commands.replay(
                        entity,
                        store,
                        actor,
                        "VERIFY_CONNECTION:" + number,
                        key,
                        request,
                        OperationVO.class);
        if (replay.isPresent()) return replay.get();
        var result =
                commands.execute(
                        entity,
                        store,
                        actor,
                        "VERIFY_CONNECTION:" + number,
                        key,
                        request,
                        OperationVO.class,
                        () -> {
                            var c = existing(entity, store, number, true);
                            if (!request.version().equals(c.row().getVersion())
                                    || "ARCHIVED".equals(c.row().getStatus())) conflict();
                            var op = CatalogPersistence.audit(new CimOperation(), actor);
                            op.setOperationNo(BizKey.next("CIOP"));
                            op.setEntityNo(entity);
                            op.setStoreNo(store);
                            op.setConnectionNo(number);
                            op.setAccountNo(c.row().getAccountNo());
                            op.setProfileNo(c.row().getProfileNo());
                            op.setOperationType("VERIFY_CONNECTION");
                            op.setDestination("PROVIDER");
                            op.setBusinessRef(number);
                            op.setBusinessRevision(c.row().getVersion().toString());
                            op.setRequestRef(c.revision().getRevisionNo());
                            op.setRequestHash(PricingSnapshotHash.of(c.identity()));
                            op.setSemanticKey(
                                    PricingSnapshotHash.of(
                                            Map.of(
                                                    "type",
                                                    "VERIFY_CONNECTION",
                                                    "key",
                                                    key,
                                                    "actor",
                                                    actor)));
                            op.setTargetScopeKind("CONNECTION");
                            op.setTargetScopeKey(
                                    ChannelIdentity.hash(c.row().getChannelStoreKey()));
                            op.setStatus("QUEUED");
                            op.setCurrentAttemptNo(0L);
                            op.setFence(0L);
                            op.setNextRecoveryAt(LocalDateTime.now(ZoneOffset.UTC));
                            operations.insert(op);
                            return ChannelOperationServiceImpl.view(op, null);
                        },
                        OperationVO::operationNo);
        return result;
    }

    @Override
    public ChannelConnectionVO setStatus(
            String entity,
            String store,
            String number,
            String actor,
            SetStatus request,
            String key) {
        requireLifecycle();
        if (request == null
                || request.version() == null
                || request.version() < 0
                || request.reason() == null
                || !Set.of("ACTIVE", "SUSPENDED", "ARCHIVED").contains(request.status())) bad();
        CommerceValidation.text(request.reason(), 256);
        var replay =
                commands.replay(
                        entity,
                        store,
                        actor,
                        "SET_CONNECTION_STATUS:" + number,
                        key,
                        request,
                        ChannelConnectionVO.class);
        if (replay.isPresent()) return replay.get();
        var before = commands.query(() -> existing(entity, store, number, false));
        if (!request.version().equals(before.row().getVersion())) conflict();
        Proof proof = null;
        if ("ACTIVE".equals(request.status())) {
            var verifier = authority.get();
            if (verifier == null) conflict();
            proof = verifier.verify(before.identity());
            requireProof(before.identity(), proof);
        }
        final Proof evidence = proof;
        return commands.execute(
                entity,
                store,
                actor,
                "SET_CONNECTION_STATUS:" + number,
                key,
                request,
                ChannelConnectionVO.class,
                () -> {
                    var c = existing(entity, store, number, true);
                    if (!request.version().equals(c.row().getVersion())
                            || !c.identity().equals(before.identity())
                            || "ARCHIVED".equals(c.row().getStatus())) conflict();
                    if ("ARCHIVED".equals(request.status())) noPending(number);
                    var update =
                            Wrappers.<CimStoreConnection>update()
                                    .eq("tenant_no", "MAIN")
                                    .eq("id", c.row().getId())
                                    .eq("version", request.version())
                                    .set("status", request.status())
                                    .set("version", request.version() + 1)
                                    .set("updated_by", actor)
                                    .set("updated_at", LocalDateTime.now(ZoneOffset.UTC));
                    if (evidence != null) {
                        requireProof(c.identity(), evidence);
                        activeGrant(c);
                        update.set("identity_receipt_ref", evidence.receiptRef())
                                .set(
                                        "identity_verified_at",
                                        LocalDateTime.ofInstant(
                                                evidence.verifiedAt(), ZoneOffset.UTC))
                                .set(
                                        "identity_valid_until",
                                        LocalDateTime.ofInstant(
                                                evidence.validUntil(), ZoneOffset.UTC));
                    }
                    if (connections.update(update) != 1) conflict();
                    return getConnection(entity, store, number);
                },
                ChannelConnectionVO::connectionNo);
    }

    private record Existing(
            CimStoreConnection row,
            CimConnectionRevision revision,
            Identity identity,
            CimAccount account) {}

    private Existing existing(String entity, String store, String number, boolean lock) {
        CommerceValidation.text(entity, 32);
        CommerceValidation.text(store, 32);
        CommerceValidation.text(number, 32);
        var q =
                Wrappers.<CimStoreConnection>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", entity)
                        .eq("store_no", store)
                        .eq("connection_no", number);
        var row = connections.selectOne(q);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var aq =
                Wrappers.<CimAccount>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", entity)
                        .eq("account_no", row.getAccountNo());
        if (lock) aq.last("FOR UPDATE");
        var account = accounts.selectOne(aq);
        if (account == null) conflict();
        if (lock) row = connections.selectOne(q.last("FOR UPDATE"));
        var rev = revision(row);
        var identity =
                identity(
                        entity,
                        store,
                        new CreateChannelConnectionReq(
                                row.getAccountNo(),
                                row.getExternalStoreRef(),
                                Owner.valueOf(rev.getSourceOwner()),
                                rev.getFulfillmentProfile()),
                        lock);
        return new Existing(row, rev, identity, account);
    }

    private void activeGrant(Existing c) {
        if (!"ACTIVE".equals(c.account().getStatus())
                || c.account().getActiveAuthorizationNo() == null) conflict();
        var grant =
                grants.selectOne(
                        Wrappers.<CimAuthorizationRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", c.row().getEntityNo())
                                .eq("account_no", c.row().getAccountNo())
                                .eq("authorization_no", c.account().getActiveAuthorizationNo()));
        if (grant == null
                || !"GRANTED".equals(grant.getStatus())
                || grant.getCredentialRef() == null
                || grant.getExpiresAt() != null
                        && !grant.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC)))
            conflict();
        var scopes = new ObjectMapper().readValue(grant.getGrantedScopes(), List.class);
        if (!scopes.contains("CATALOG")
                && !scopes.contains("ORDER")
                && !scopes.contains("REDEMPTION")) conflict();
    }

    private void noPending(String number) {
        requireLifecycle();
        if (operations.selectCount(
                        Wrappers.<CimOperation>query()
                                .eq("tenant_no", "MAIN")
                                .eq("connection_no", number)
                                .notIn("status", "SUCCEEDED", "FAILED", "CANCELLED", "PARTIAL"))
                > 0) conflict();
    }

    private void requireLifecycle() {
        if (operations == null || attempts == null || grants == null) conflict();
    }

    private static void bad() {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static void conflict() {
        throw BizException.of(ErrorCode.CONFLICT);
    }

    @Override
    public int recoverDue(int limit) {
        requireLifecycle();
        if (limit < 1 || limit > 100) bad();
        var due =
                commands.query(
                        () ->
                                operations.selectList(
                                        Wrappers.<CimOperation>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq("operation_type", "VERIFY_CONNECTION")
                                                .in("status", "QUEUED", "UNKNOWN", "SENDING")
                                                .le(
                                                        "next_recovery_at",
                                                        LocalDateTime.now(ZoneOffset.UTC))
                                                .orderByAsc("next_recovery_at", "id")
                                                .last("LIMIT " + limit)));
        int completed = 0;
        for (var candidate : due) {
            var work =
                    commands.atomic(
                            () -> {
                                var c =
                                        existing(
                                                candidate.getEntityNo(),
                                                candidate.getStoreNo(),
                                                candidate.getConnectionNo(),
                                                true);
                                var op =
                                        operations.selectOne(
                                                Wrappers.<CimOperation>query()
                                                        .eq("tenant_no", "MAIN")
                                                        .eq(
                                                                "operation_no",
                                                                candidate.getOperationNo())
                                                        .last("FOR UPDATE"));
                                if (!Set.of("QUEUED", "UNKNOWN", "SENDING").contains(op.getStatus())
                                        || op.getNextRecoveryAt()
                                                .isAfter(LocalDateTime.now(ZoneOffset.UTC)))
                                    return null;
                                if (!op.getBusinessRevision()
                                                .equals(c.row().getVersion().toString())
                                        || !op.getRequestHash()
                                                .equals(PricingSnapshotHash.of(c.identity()))) {
                                    operations.update(
                                            Wrappers.<CimOperation>update()
                                                    .eq("id", op.getId())
                                                    .set("status", "CANCELLED")
                                                    .set("reason_code", "CONNECTION_CHANGED"));
                                    return null;
                                }
                                op.setCurrentAttemptNo(op.getCurrentAttemptNo() + 1);
                                op.setFence(op.getFence() + 1);
                                operations.update(
                                        Wrappers.<CimOperation>update()
                                                .eq("tenant_no", "MAIN")
                                                .eq("id", op.getId())
                                                .set("status", "SENDING")
                                                .set("current_attempt_no", op.getCurrentAttemptNo())
                                                .set("fence", op.getFence())
                                                .set(
                                                        "next_recovery_at",
                                                        LocalDateTime.now(ZoneOffset.UTC)
                                                                .plusSeconds(30)));
                                var attempt =
                                        CatalogPersistence.audit(
                                                new CimOperationAttempt(), "SYSTEM");
                                attempt.setAttemptRef(BizKey.next("CIAT"));
                                attempt.setEntityNo(op.getEntityNo());
                                attempt.setOperationNo(op.getOperationNo());
                                attempt.setAttemptNo(op.getCurrentAttemptNo());
                                attempt.setFence(op.getFence());
                                attempt.setAttemptKind("VERIFY");
                                attempt.setStartedAt(LocalDateTime.now(ZoneOffset.UTC));
                                attempt.setOutcome("STARTED");
                                attempts.insert(attempt);
                                return new VerificationWork(c, op, attempt);
                            });
            if (work == null) continue;
            Proof proof = null;
            try {
                proof = authority.get().verify(work.context().identity());
                requireProof(work.context().identity(), proof);
            } catch (RuntimeException unavailable) {
                proof = null;
            }
            final Proof result = proof;
            commands.atomic(
                    () -> {
                        var current =
                                existing(
                                        work.context().row().getEntityNo(),
                                        work.context().row().getStoreNo(),
                                        work.context().row().getConnectionNo(),
                                        true);
                        var op =
                                operations.selectOne(
                                        Wrappers.<CimOperation>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq(
                                                        "operation_no",
                                                        work.operation().getOperationNo())
                                                .last("FOR UPDATE"));
                        if (!op.getFence().equals(work.operation().getFence())
                                || !"SENDING".equals(op.getStatus())) return null;
                        boolean unchanged =
                                current.row().getVersion().equals(work.context().row().getVersion())
                                        && current.identity().equals(work.context().identity());
                        String status =
                                !unchanged ? "CANCELLED" : result == null ? "UNKNOWN" : "SUCCEEDED";
                        if (unchanged && result != null) {
                            requireProof(current.identity(), result);
                            connections.update(
                                    Wrappers.<CimStoreConnection>update()
                                            .eq("tenant_no", "MAIN")
                                            .eq("id", current.row().getId())
                                            .set("identity_receipt_ref", result.receiptRef())
                                            .set(
                                                    "identity_verified_at",
                                                    LocalDateTime.ofInstant(
                                                            result.verifiedAt(), ZoneOffset.UTC))
                                            .set(
                                                    "identity_valid_until",
                                                    LocalDateTime.ofInstant(
                                                            result.validUntil(), ZoneOffset.UTC))
                                            .set("version", current.row().getVersion() + 1));
                        }
                        attempts.update(
                                Wrappers.<CimOperationAttempt>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("attempt_ref", work.attempt().getAttemptRef())
                                        .set("outcome", status)
                                        .set(
                                                "response_ref",
                                                result == null ? null : result.receiptRef())
                                        .set("finished_at", LocalDateTime.now(ZoneOffset.UTC)));
                        operations.update(
                                Wrappers.<CimOperation>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("id", op.getId())
                                        .set("status", status)
                                        .set(
                                                "original_evidence_ref",
                                                result == null ? null : result.receiptRef())
                                        .set(
                                                "next_recovery_at",
                                                "UNKNOWN".equals(status)
                                                        ? LocalDateTime.now(ZoneOffset.UTC)
                                                                .plusMinutes(1)
                                                        : null));
                        return null;
                    });
            completed++;
        }
        return completed;
    }

    private record VerificationWork(
            Existing context, CimOperation operation, CimOperationAttempt attempt) {}

    private Identity identity(
            String entity, String store, CreateChannelConnectionReq req, boolean lock) {
        var accountQuery =
                Wrappers.<CimAccount>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", entity)
                        .eq("account_no", req.accountNo());
        if (lock) accountQuery.last("FOR UPDATE");
        var account = accounts.selectOne(accountQuery);
        if (account == null || "ARCHIVED".equals(account.getStatus()))
            throw BizException.of(ErrorCode.NOT_FOUND);
        var profileQuery =
                Wrappers.<CimProductProfile>query()
                        .eq("tenant_no", "MAIN")
                        .eq("profile_no", account.getProfileNo());
        if (lock) profileQuery.last("FOR UPDATE");
        var profile = profiles.selectOne(profileQuery);
        if (profile == null
                || !"VERIFIED".equals(profile.getStatus())
                || profile.getIdentityNamespace() == null
                || profile.getIdentityNamespace().isBlank()
                || profile.getStoreIdentityScope() == null
                || !Set.of("GLOBAL", "ACCOUNT").contains(profile.getStoreIdentityScope())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return new Identity(
                entity,
                store,
                account.getAccountNo(),
                profile.getProfileNo(),
                account.getExternalAccountRef(),
                req.externalStoreRef(),
                profile.getIdentityNamespace(),
                "ACCOUNT".equals(profile.getStoreIdentityScope()),
                account.getVersion(),
                profile.getVersion());
    }

    private static void requireProof(Identity identity, Proof proof) {
        Instant now = Instant.now();
        if (proof == null
                || proof.status() != Status.VERIFIED
                || !identity.equals(proof.identity())
                || proof.receiptRef() == null
                || proof.receiptRef().isBlank()
                || proof.receiptRef().length() > 128
                || proof.verifiedAt() == null
                || proof.validUntil() == null
                || proof.verifiedAt().isAfter(now)
                || !proof.validUntil().isAfter(now)) throw BizException.of(ErrorCode.CONFLICT);
    }

    @Override
    public ChannelConnectionVO getConnection(String entity, String store, String connectionNo) {
        CommerceValidation.text(entity, 32);
        CommerceValidation.text(store, 32);
        CommerceValidation.text(connectionNo, 32);
        return commands.query(
                () -> {
                    var row =
                            connections.selectOne(
                                    Wrappers.<CimStoreConnection>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("entity_no", entity)
                                            .eq("store_no", store)
                                            .eq("connection_no", connectionNo));
                    if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
                    return view(row, revision(row));
                });
    }

    @Override
    public PageData<ChannelConnectionVO> listConnections(
            String entity, String store, int page, int size) {
        CommerceValidation.text(entity, 32);
        CommerceValidation.text(store, 32);
        long offset = CommerceValidation.offset(page, size);
        return commands.query(
                () -> {
                    var query =
                            Wrappers.<CimStoreConnection>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", entity)
                                    .eq("store_no", store);
                    long total = connections.selectCount(query);
                    var records =
                            connections
                                    .selectList(
                                            query.orderByDesc("created_at", "id")
                                                    .last("LIMIT " + size + " OFFSET " + offset))
                                    .stream()
                                    .map(row -> view(row, revision(row)))
                                    .toList();
                    return PageData.of(records, total, page, size);
                });
    }

    private CimConnectionRevision revision(CimStoreConnection row) {
        var revision =
                revisions.selectOne(
                        Wrappers.<CimConnectionRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", row.getEntityNo())
                                .eq("connection_no", row.getConnectionNo())
                                .eq("revision_no", row.getActiveRevisionNo()));
        if (revision == null)
            throw new IllegalStateException("Missing immutable connection revision");
        return revision;
    }

    private static ChannelConnectionVO view(
            CimStoreConnection row, CimConnectionRevision revision) {
        return new ChannelConnectionVO(
                row.getConnectionNo(),
                row.getStoreNo(),
                row.getAccountNo(),
                row.getProfileNo(),
                row.getExternalStoreRef(),
                row.getStatus(),
                Owner.valueOf(revision.getSourceOwner()),
                revision.getFulfillmentProfile(),
                revision.getRevisionNo(),
                row.getVersion());
    }
}
