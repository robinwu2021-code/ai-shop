package ai.neargo.shop.commerce.service.impl;

import ai.neargo.shop.commerce.dto.AuthorizationActionVO;
import ai.neargo.shop.commerce.dto.AuthorizationCallbackResult;
import ai.neargo.shop.commerce.dto.AuthorizationRevisionVO;
import ai.neargo.shop.commerce.dto.AuthorizeChannelAccountReq;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.commerce.dto.RevokeChannelAuthorizationReq;
import ai.neargo.shop.commerce.entity.CimAccount;
import ai.neargo.shop.commerce.entity.CimAuthorizationRevision;
import ai.neargo.shop.commerce.entity.CimOperation;
import ai.neargo.shop.commerce.entity.CimOperationAttempt;
import ai.neargo.shop.commerce.entity.CimProductProfile;
import ai.neargo.shop.commerce.entity.CimProvider;
import ai.neargo.shop.commerce.mapper.CommerceMappers.AccountMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.AuthorizationRevisionMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.OperationAttemptMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.OperationMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ProductProfileMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ProviderMapper;
import ai.neargo.shop.commerce.policy.AuthorizationUrlPolicy;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.commerce.service.ChannelAuthorizationService;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.commerce.ChannelAuthorizationGateway;
import ai.neargo.shop.spi.commerce.ChannelAuthorizationGateway.AuthorizationContext;
import ai.neargo.shop.spi.commerce.ChannelAuthorizationGateway.AuthorizationQuery;
import ai.neargo.shop.spi.commerce.ChannelAuthorizationGateway.Grant;
import ai.neargo.shop.spi.commerce.ChannelAuthorizationGateway.RecoveryStatus;
import ai.neargo.shop.spi.commerce.ChannelAuthorizationGateway.RevocationContext;
import ai.neargo.shop.spi.commerce.ChannelRecoveryPort;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 原意图先提交，外部调用不持事务；state单次消费和fence阻止重放与晚结果覆盖。 */
public class ChannelAuthorizationServiceImpl
        implements ChannelAuthorizationService, ChannelRecoveryPort {
    private final AccountMapper accounts;
    private final ProductProfileMapper profiles;
    private final ProviderMapper providers;
    private final AuthorizationRevisionMapper revisions;
    private final OperationMapper operations;
    private final OperationAttemptMapper attempts;
    private final CommerceCommands commands;
    private final Map<String, ChannelAuthorizationGateway> gateways;
    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    private record Target(String accountNo, Object request) {}

    private record CallbackWork(CimAccount account, CimOperation operation, boolean claimed) {}

    public ChannelAuthorizationServiceImpl(
            AccountMapper accounts,
            ProductProfileMapper profiles,
            ProviderMapper providers,
            AuthorizationRevisionMapper revisions,
            OperationMapper operations,
            OperationAttemptMapper attempts,
            CommerceCommands commands,
            List<ChannelAuthorizationGateway> candidates) {
        this.accounts = accounts;
        this.profiles = profiles;
        this.providers = providers;
        this.revisions = revisions;
        this.operations = operations;
        this.attempts = attempts;
        this.commands = commands;
        var index = new HashMap<String, ChannelAuthorizationGateway>();
        for (var gateway : candidates) {
            if (gateway.profileNo() == null
                    || gateway.profileNo().isBlank()
                    || index.putIfAbsent(gateway.profileNo(), gateway) != null) {
                throw new IllegalStateException(
                        "Duplicate or missing authorization gateway profile");
            }
        }
        gateways = Map.copyOf(index);
    }

    @Override
    public AuthorizationActionVO authorize(
            String entity,
            String accountNo,
            String actor,
            AuthorizeChannelAccountReq req,
            String key) {
        outsideTransaction();
        context(entity, accountNo, actor);
        if (req == null || req.version() == null || req.version() < 0) bad();
        CommerceValidation.text(req.returnTargetCode(), 32);
        var target = new Target(accountNo, req);
        var replay =
                commands.replay(
                        entity,
                        null,
                        actor,
                        "AUTHORIZE_ACCOUNT",
                        key,
                        target,
                        AuthorizationActionVO.class);
        if (replay.isPresent()) return replay.get();
        String candidate = BizKey.next("CIOP");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var action =
                commands.execute(
                        entity,
                        null,
                        actor,
                        "AUTHORIZE_ACCOUNT",
                        key,
                        target,
                        AuthorizationActionVO.class,
                        () -> {
                            var account = account(entity, accountNo, true);
                            if (!req.version().equals(account.getVersion())
                                    || account.getActiveOperationNo() != null
                                    || !Set.of("DRAFT", "ACTIVE", "REVOKED", "AUTH_REJECTED")
                                            .contains(account.getStatus())) conflict();
                            var gateway = gateway(account.getProfileNo());
                            profile(account.getProfileNo());
                            if (!gateway.returnTargetCodes().contains(req.returnTargetCode()))
                                bad();
                            var op =
                                    newOperation(
                                            account,
                                            actor,
                                            candidate,
                                            "AUTHORIZE",
                                            json.writeValueAsString(req));
                            op.setCallbackStateHash(ChannelIdentity.hash(state));
                            op.setReturnTargetCode(req.returnTargetCode());
                            op.setExpiresAt(now().plusMinutes(15));
                            operations.insert(op);
                            attempt(op, "SEND");
                            setAccount(
                                    account,
                                    "AUTHORIZING",
                                    candidate,
                                    account.getActiveAuthorizationNo(),
                                    actor);
                            return new AuthorizationActionVO(
                                    candidate,
                                    "SENDING",
                                    op.getExpiresAt().toInstant(ZoneOffset.UTC));
                        },
                        AuthorizationActionVO::operationNo);
        if (!candidate.equals(action.operationNo())) return action;
        var op = commands.query(() -> operation(entity, candidate, false));
        var account = commands.query(() -> account(entity, accountNo, false));
        var gateway = gateway(op.getProfileNo());
        try {
            var result = gateway.begin(authContext(account, op, state));
            if (result == null) throw new IllegalArgumentException("Missing provider result");
            AuthorizationUrlPolicy.validate(result.authorizationUrl(), state, gateway);
            CommerceValidation.text(result.receiptRef(), 256);
            finishBegin(op, result.authorizationUrl(), result.receiptRef(), false);
        } catch (RuntimeException failure) {
            finishBegin(op, null, null, true);
        }
        return action;
    }

    @Override
    public OperationVO revoke(
            String entity,
            String accountNo,
            String actor,
            RevokeChannelAuthorizationReq req,
            String key) {
        outsideTransaction();
        context(entity, accountNo, actor);
        if (req == null || req.version() == null || req.version() < 0) bad();
        CommerceValidation.text(req.reasonCode(), 64);
        CommerceValidation.text(req.reason(), 256);
        var target = new Target(accountNo, req);
        var replay =
                commands.replay(
                        entity, null, actor, "REVOKE_ACCOUNT", key, target, OperationVO.class);
        if (replay.isPresent()) return replay.get();
        String candidate = BizKey.next("CIOP");
        var response =
                commands.execute(
                        entity,
                        null,
                        actor,
                        "REVOKE_ACCOUNT",
                        key,
                        target,
                        OperationVO.class,
                        () -> {
                            var account = account(entity, accountNo, true);
                            if (!req.version().equals(account.getVersion())
                                    || !Set.of("ACTIVE", "AUTH_REJECTED")
                                            .contains(account.getStatus())
                                    || account.getActiveOperationNo() != null
                                    || account.getActiveAuthorizationNo() == null) conflict();
                            gateway(account.getProfileNo());
                            var op =
                                    newOperation(
                                            account,
                                            actor,
                                            candidate,
                                            "REVOKE",
                                            json.writeValueAsString(req));
                            op.setRequestRef(account.getActiveAuthorizationNo());
                            op.setReasonCode(req.reasonCode());
                            op.setReason(req.reason());
                            operations.insert(op);
                            attempt(op, "SEND");
                            setAccount(
                                    account,
                                    "REVOKING",
                                    candidate,
                                    account.getActiveAuthorizationNo(),
                                    actor);
                            return ChannelOperationServiceImpl.view(op, null);
                        },
                        OperationVO::operationNo);
        if (!candidate.equals(response.operationNo())) return response;
        var op = commands.query(() -> operation(entity, candidate, false));
        var account = commands.query(() -> account(entity, accountNo, false));
        var revision = commands.query(() -> revision(entity, accountNo, op.getRequestRef()));
        try {
            var result =
                    gateway(op.getProfileNo())
                            .revoke(
                                    new RevocationContext(
                                            candidate,
                                            entity,
                                            accountNo,
                                            account.getProfileNo(),
                                            account.getExternalAccountRef(),
                                            revision.getAuthorizationNo(),
                                            revision.getCredentialRef()));
            if (result == null || !result.confirmed())
                throw new IllegalArgumentException("Unconfirmed revocation");
            CommerceValidation.text(result.receiptRef(), 256);
            finishRevoke(op, result.receiptRef(), false);
        } catch (RuntimeException failure) {
            finishRevoke(op, null, true);
        }
        return response;
    }

    @Override
    public AuthorizationCallbackResult completeAuthorization(
            String providerCode, String state, String code, String error) {
        outsideTransaction();
        CommerceValidation.text(providerCode, 32);
        if (state == null
                || !state.matches("[A-Za-z0-9_-]{43}")
                || (code == null) == (error == null)) bad();
        if (code != null) CommerceValidation.text(code, 2048);
        else CommerceValidation.text(error, 64);
        String hash = ChannelIdentity.hash(state);
        var original =
                commands.query(
                        () ->
                                operations.selectOne(
                                        Wrappers.<CimOperation>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq("callback_state_hash", hash)));
        if (original == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var gateway = gateway(original.getProfileNo());
        commands.query(
                () -> {
                    var profile = profile(original.getProfileNo());
                    var provider =
                            providers.selectOne(
                                    Wrappers.<CimProvider>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("provider_no", profile.getProviderNo()));
                    if (provider == null || !providerCode.equals(provider.getProviderCode()))
                        conflict();
                    return true;
                });
        String callbackHash =
                ChannelIdentity.hash(
                        ChannelIdentity.part(code == null ? "" : code)
                                + ChannelIdentity.part(error == null ? "" : error));
        var work =
                commands.atomic(
                        () -> {
                            var account =
                                    account(original.getEntityNo(), original.getAccountNo(), true);
                            var op =
                                    operation(
                                            original.getEntityNo(),
                                            original.getOperationNo(),
                                            true);
                            if (!hash.equals(op.getCallbackStateHash())
                                    || !"AUTHORIZE".equals(op.getOperationType())) conflict();
                            if (op.getCallbackConsumedAt() != null) {
                                if (!callbackHash.equals(op.getCallbackRequestHash())) conflict();
                                return new CallbackWork(account, op, false);
                            }
                            if (op.getExpiresAt() == null
                                    || !op.getExpiresAt().isAfter(now())
                                    || !Set.of("SENDING", "SUBMITTED", "UNKNOWN")
                                            .contains(op.getStatus())) conflict();
                            requireCurrent(account, op);
                            updateOperation(
                                    op,
                                    "EXCHANGING",
                                    Wrappers.<CimOperation>update()
                                            .set("callback_consumed_at", now())
                                            .set("callback_request_hash", callbackHash)
                                            .set("authorization_url", null)
                                            .set("current_attempt_no", op.getCurrentAttemptNo() + 1)
                                            .set("fence", op.getFence() + 1)
                                            .set("lease_until", now().plusSeconds(90))
                                            .set("next_recovery_at", now().plusSeconds(90)));
                            op.setCallbackConsumedAt(now());
                            op.setCallbackRequestHash(callbackHash);
                            op.setCurrentAttemptNo(op.getCurrentAttemptNo() + 1);
                            op.setFence(op.getFence() + 1);
                            attempt(op, "CALLBACK");
                            return new CallbackWork(account, op, true);
                        });
        if (!work.claimed())
            return new AuthorizationCallbackResult(
                    work.operation().getOperationNo(), work.operation().getStatus());
        if (error != null) return finishGrant(work, null, "REJECTED");
        try {
            Grant grant =
                    gateway.exchange(authContext(work.account(), work.operation(), state), code);
            validateGrant(work.account(), grant);
            return finishGrant(work, grant, "SUCCEEDED");
        } catch (RuntimeException failure) {
            return finishGrant(work, null, "UNKNOWN");
        }
    }

    private void finishBegin(CimOperation sent, String url, String evidence, boolean unknown) {
        commands.atomic(
                () -> {
                    var account = account(sent.getEntityNo(), sent.getAccountNo(), true);
                    var current = operation(sent.getEntityNo(), sent.getOperationNo(), true);
                    if (!current.getFence().equals(sent.getFence())) {
                        late(sent, evidence, unknown ? "UNKNOWN" : "SUBMITTED");
                        return true;
                    }
                    closeAttempt(sent, unknown ? "UNKNOWN" : "SUBMITTED", evidence);
                    if (current.getFence().equals(sent.getFence())
                            && "SENDING".equals(current.getStatus())) {
                        requireCurrent(account, current);
                        updateOperation(
                                current,
                                unknown ? "UNKNOWN" : "SUBMITTED",
                                Wrappers.<CimOperation>update()
                                        .set("authorization_url", url)
                                        .set("original_evidence_ref", evidence)
                                        .set("lease_until", null)
                                        .set(
                                                "next_recovery_at",
                                                unknown
                                                        ? now().plusSeconds(30)
                                                        : current.getExpiresAt())
                                        .set(
                                                "business_revision",
                                                unknown
                                                        ? Long.toString(account.getVersion() + 1)
                                                        : current.getBusinessRevision()));
                        if (unknown)
                            setAccount(
                                    account,
                                    "AUTH_UNKNOWN",
                                    current.getOperationNo(),
                                    account.getActiveAuthorizationNo(),
                                    current.getCreatedBy());
                    }
                    return true;
                });
    }

    private AuthorizationCallbackResult finishGrant(CallbackWork work, Grant grant, String status) {
        return commands.atomic(
                () -> {
                    var account =
                            account(
                                    work.account().getEntityNo(),
                                    work.account().getAccountNo(),
                                    true);
                    var op =
                            operation(
                                    account.getEntityNo(), work.operation().getOperationNo(), true);
                    if (!op.getFence().equals(work.operation().getFence())
                            || !"EXCHANGING".equals(op.getStatus())) {
                        late(work.operation(), grant == null ? null : grant.receiptRef(), status);
                        return new AuthorizationCallbackResult(op.getOperationNo(), op.getStatus());
                    }
                    requireCurrent(account, op);
                    String evidence = null;
                    String authorization = account.getActiveAuthorizationNo();
                    if (grant != null) {
                        validateGrant(account, grant);
                        var row = new CimAuthorizationRevision();
                        audit(row, op.getCreatedBy());
                        row.setEntityNo(account.getEntityNo());
                        row.setAccountNo(account.getAccountNo());
                        row.setAuthorizationNo(BizKey.next("CIAR"));
                        row.setRevisionNo(
                                revisions.selectCount(
                                                Wrappers.<CimAuthorizationRevision>query()
                                                        .eq("tenant_no", "MAIN")
                                                        .eq("entity_no", account.getEntityNo())
                                                        .eq("account_no", account.getAccountNo()))
                                        + 1);
                        row.setCredentialRef(grant.credentialRef());
                        row.setGrantedScopes(
                                json.writeValueAsString(
                                        grant.grantedScopes().stream().sorted().toList()));
                        row.setCapabilityHash(ChannelIdentity.hash(row.getGrantedScopes()));
                        row.setStatus("GRANTED");
                        row.setAuthorizedAt(
                                LocalDateTime.ofInstant(grant.authorizedAt(), ZoneOffset.UTC));
                        row.setExpiresAt(
                                LocalDateTime.ofInstant(grant.expiresAt(), ZoneOffset.UTC));
                        row.setEvidenceRef(grant.receiptRef());
                        revisions.insert(row);
                        authorization = row.getAuthorizationNo();
                        evidence = grant.receiptRef();
                    }
                    closeAttempt(work.operation(), status, evidence);
                    updateOperation(
                            op,
                            status,
                            Wrappers.<CimOperation>update()
                                    .set("lease_until", null)
                                    .set(
                                            "next_recovery_at",
                                            "UNKNOWN".equals(status) ? now().plusSeconds(30) : null)
                                    .set("original_evidence_ref", evidence)
                                    .set("authorization_url", null)
                                    .set(
                                            "business_revision",
                                            Long.toString(account.getVersion() + 1)));
                    String accountStatus =
                            grant != null
                                    ? "ACTIVE"
                                    : "REJECTED".equals(status) ? "AUTH_REJECTED" : "AUTH_UNKNOWN";
                    setAccount(
                            account,
                            accountStatus,
                            "UNKNOWN".equals(status) ? op.getOperationNo() : null,
                            authorization,
                            op.getCreatedBy());
                    return new AuthorizationCallbackResult(op.getOperationNo(), status);
                });
    }

    private void finishRevoke(CimOperation sent, String evidence, boolean unknown) {
        commands.atomic(
                () -> {
                    var account = account(sent.getEntityNo(), sent.getAccountNo(), true);
                    var op = operation(sent.getEntityNo(), sent.getOperationNo(), true);
                    if (!op.getFence().equals(sent.getFence())
                            || !"SENDING".equals(op.getStatus())) {
                        late(sent, evidence, unknown ? "UNKNOWN" : "SUCCEEDED");
                        return true;
                    }
                    requireCurrent(account, op);
                    closeAttempt(sent, unknown ? "UNKNOWN" : "SUCCEEDED", evidence);
                    updateOperation(
                            op,
                            unknown ? "UNKNOWN" : "SUCCEEDED",
                            Wrappers.<CimOperation>update()
                                    .set("lease_until", null)
                                    .set("next_recovery_at", unknown ? now().plusSeconds(30) : null)
                                    .set("original_evidence_ref", evidence)
                                    .set(
                                            "business_revision",
                                            Long.toString(account.getVersion() + 1)));
                    setAccount(
                            account,
                            unknown ? "REVOKE_UNKNOWN" : "REVOKED",
                            unknown ? op.getOperationNo() : null,
                            unknown ? account.getActiveAuthorizationNo() : null,
                            op.getCreatedBy());
                    return true;
                });
    }

    @Override
    public PageData<AuthorizationRevisionVO> listRevisions(
            String entity, String accountNo, int page, int size) {
        CommerceValidation.text(entity, 32);
        CommerceValidation.text(accountNo, 32);
        long offset = CommerceValidation.offset(page, size);
        return commands.query(
                () -> {
                    var account = account(entity, accountNo, false);
                    var q =
                            Wrappers.<CimAuthorizationRevision>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", entity)
                                    .eq("account_no", accountNo);
                    long count = revisions.selectCount(q);
                    var records =
                            revisions
                                    .selectList(
                                            q.orderByDesc("revision_no")
                                                    .last("LIMIT " + size + " OFFSET " + offset))
                                    .stream()
                                    .map(
                                            row -> {
                                                List<String> scopes =
                                                        json.readValue(
                                                                row.getGrantedScopes(),
                                                                json.getTypeFactory()
                                                                        .constructCollectionType(
                                                                                List.class,
                                                                                String.class));
                                                return new AuthorizationRevisionVO(
                                                        row.getAuthorizationNo(),
                                                        row.getRevisionNo(),
                                                        scopes,
                                                        row.getAuthorizedAt()
                                                                .toInstant(ZoneOffset.UTC),
                                                        row.getExpiresAt()
                                                                .toInstant(ZoneOffset.UTC),
                                                        row.getStatus(),
                                                        "ACTIVE".equals(account.getStatus())
                                                                && row.getAuthorizationNo()
                                                                        .equals(
                                                                                account
                                                                                        .getActiveAuthorizationNo())
                                                                && row.getExpiresAt()
                                                                        .isAfter(now()));
                                            })
                                    .toList();
                    return PageData.of(records, count, page, size);
                });
    }

    private record RecoveryWork(CimAccount account, CimOperation operation) {}

    @Override
    public String workflow() {
        return "ACCOUNT_AUTHORIZATION";
    }

    /**
     * The existing sole recovery job asks for original outcomes. A one-use code or revoke call is
     * never replayed.
     */
    @Override
    public int recoverDue(int limit) {
        outsideTransaction();
        if (limit < 1 || limit > 100) bad();
        var due =
                commands.query(
                        () ->
                                operations.selectList(
                                        Wrappers.<CimOperation>query()
                                                .eq("tenant_no", "MAIN")
                                                .in("operation_type", "AUTHORIZE", "REVOKE")
                                                .in(
                                                        "status",
                                                        "SENDING",
                                                        "EXCHANGING",
                                                        "UNKNOWN",
                                                        "SUBMITTED")
                                                .and(
                                                        q ->
                                                                q.le("next_recovery_at", now())
                                                                        .or()
                                                                        .isNull("next_recovery_at")
                                                                        .and(
                                                                                old ->
                                                                                        old.le(
                                                                                                        "lease_until",
                                                                                                        now())
                                                                                                .or()
                                                                                                .eq(
                                                                                                        "status",
                                                                                                        "UNKNOWN")
                                                                                                .or()
                                                                                                .le(
                                                                                                        "expires_at",
                                                                                                        now())))
                                                .orderByAsc("id")
                                                .last("LIMIT " + limit)));
        int completed = 0;
        for (var candidate : due) {
            var work = commands.atomic(() -> claimRecovery(candidate));
            if (work == null) continue;
            try {
                if ("REVOKE".equals(work.operation().getOperationType())) {
                    var revision =
                            commands.query(
                                    () ->
                                            revision(
                                                    candidate.getEntityNo(),
                                                    candidate.getAccountNo(),
                                                    candidate.getRequestRef()));
                    var query =
                            new RevocationContext(
                                    candidate.getOperationNo(),
                                    candidate.getEntityNo(),
                                    candidate.getAccountNo(),
                                    candidate.getProfileNo(),
                                    work.account().getExternalAccountRef(),
                                    revision.getAuthorizationNo(),
                                    revision.getCredentialRef());
                    var result = gateway(candidate.getProfileNo()).queryRevocation(query);
                    if (result == null
                            || !query.equals(result.query())
                            || result.status() != RecoveryStatus.FOUND
                            || result.revocation() == null
                            || !result.revocation().confirmed())
                        throw new IllegalStateException("Revocation remains uncertain");
                    CommerceValidation.text(result.originalReceiptRef(), 256);
                    CommerceValidation.text(result.revocation().receiptRef(), 256);
                    finishRevoke(work.operation(), result.originalReceiptRef(), false);
                    completed++;
                } else {
                    var op = work.operation();
                    var query =
                            new AuthorizationQuery(
                                    op.getOperationNo(),
                                    op.getEntityNo(),
                                    op.getAccountNo(),
                                    op.getProfileNo(),
                                    work.account().getExternalAccountRef(),
                                    op.getCreatedBy(),
                                    op.getReturnTargetCode(),
                                    op.getCallbackConsumedAt() != null);
                    var result = gateway(op.getProfileNo()).queryAuthorization(query);
                    if (result == null
                            || !query.equals(result.query())
                            || result.status() == null
                            || result.status() == RecoveryStatus.UNKNOWN)
                        throw new IllegalStateException("Authorization remains uncertain");
                    CommerceValidation.text(result.originalReceiptRef(), 256);
                    if (result.status() == RecoveryStatus.TERMINAL_FAILED) {
                        if (result.start() != null || result.grant() != null) conflict();
                        finishAbandoned(work, result.originalReceiptRef());
                        completed++;
                    } else if (query.callbackConsumed()) {
                        if (result.start() != null) conflict();
                        validateGrant(work.account(), result.grant());
                        finishGrant(
                                new CallbackWork(work.account(), op, true),
                                result.grant(),
                                "SUCCEEDED");
                        completed++;
                    } else {
                        if (result.grant() != null
                                || result.start() == null
                                || op.getExpiresAt() == null
                                || !op.getExpiresAt().isAfter(now())) conflict();
                        AuthorizationUrlPolicy.validateStateHash(
                                result.start().authorizationUrl(),
                                op.getCallbackStateHash(),
                                gateway(op.getProfileNo()));
                        CommerceValidation.text(result.start().receiptRef(), 256);
                        finishBegin(
                                op,
                                result.start().authorizationUrl(),
                                result.originalReceiptRef(),
                                false);
                    }
                }
            } catch (RuntimeException unavailable) {
                try {
                    if ("REVOKE".equals(work.operation().getOperationType()))
                        finishRevoke(work.operation(), null, true);
                    else if (work.operation().getCallbackConsumedAt() != null)
                        finishGrant(
                                new CallbackWork(work.account(), work.operation(), true),
                                null,
                                "UNKNOWN");
                    else finishBegin(work.operation(), null, null, true);
                } catch (RuntimeException superseded) {
                    /* A callback/new fence now owns the original operation. */
                }
            }
        }
        return completed;
    }

    private RecoveryWork claimRecovery(CimOperation candidate) {
        var account = account(candidate.getEntityNo(), candidate.getAccountNo(), true);
        var op = operation(candidate.getEntityNo(), candidate.getOperationNo(), true);
        if (!Set.of("SENDING", "EXCHANGING", "UNKNOWN", "SUBMITTED").contains(op.getStatus())
                || op.getLeaseUntil() != null && op.getLeaseUntil().isAfter(now())
                || op.getNextRecoveryAt() != null && op.getNextRecoveryAt().isAfter(now()))
            return null;
        requireCurrent(account, op);
        attempts.update(
                Wrappers.<CimOperationAttempt>update()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", op.getEntityNo())
                        .eq("operation_no", op.getOperationNo())
                        .eq("attempt_no", op.getCurrentAttemptNo())
                        .eq("outcome", "STARTED")
                        .set("outcome", "UNKNOWN")
                        .set("finished_at", now())
                        .set("error_code", "LOCAL_LEASE_EXPIRED"));
        long attempt = Math.addExact(op.getCurrentAttemptNo(), 1),
                fence = Math.addExact(op.getFence(), 1);
        String status =
                "AUTHORIZE".equals(op.getOperationType()) && op.getCallbackConsumedAt() != null
                        ? "EXCHANGING"
                        : "SENDING";
        updateOperation(
                op,
                status,
                Wrappers.<CimOperation>update()
                        .set("current_attempt_no", attempt)
                        .set("fence", fence)
                        .set("lease_until", now().plusSeconds(90))
                        .set("next_recovery_at", now().plusSeconds(90)));
        op.setCurrentAttemptNo(attempt);
        op.setFence(fence);
        attempt(op, "QUERY");
        return new RecoveryWork(account, op);
    }

    private void finishAbandoned(RecoveryWork work, String evidence) {
        commands.atomic(
                () -> {
                    var account =
                            account(
                                    work.account().getEntityNo(),
                                    work.account().getAccountNo(),
                                    true);
                    var op =
                            operation(
                                    account.getEntityNo(), work.operation().getOperationNo(), true);
                    if (!Objects.equals(op.getFence(), work.operation().getFence())) {
                        late(work.operation(), evidence, "REJECTED");
                        return false;
                    }
                    requireCurrent(account, op);
                    closeAttempt(work.operation(), "REJECTED", evidence);
                    updateOperation(
                            op,
                            "REJECTED",
                            Wrappers.<CimOperation>update()
                                    .set("lease_until", null)
                                    .set("authorization_url", null)
                                    .set("next_recovery_at", null)
                                    .set("original_evidence_ref", evidence)
                                    .set(
                                            "business_revision",
                                            Long.toString(account.getVersion() + 1)));
                    setAccount(
                            account,
                            "AUTH_REJECTED",
                            null,
                            account.getActiveAuthorizationNo(),
                            op.getCreatedBy());
                    return true;
                });
    }

    private void late(CimOperation original, String evidence, String outcome) {
        if (evidence == null) return;
        CommerceValidation.text(evidence, 256);
        attempts.update(
                Wrappers.<CimOperationAttempt>update()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", original.getEntityNo())
                        .eq("operation_no", original.getOperationNo())
                        .eq("attempt_no", original.getCurrentAttemptNo())
                        .eq("fence", original.getFence())
                        .isNull("late_evidence_ref")
                        .set("late_evidence_ref", evidence)
                        .set("late_outcome", outcome));
    }

    private CimOperation newOperation(
            CimAccount account, String actor, String number, String type, String request) {
        var op = new CimOperation();
        audit(op, actor);
        op.setOperationNo(number);
        op.setEntityNo(account.getEntityNo());
        op.setAccountNo(account.getAccountNo());
        op.setProfileNo(account.getProfileNo());
        op.setTargetScopeKind("ACCOUNT");
        op.setTargetScopeKey(
                ChannelIdentity.hash(
                        ChannelIdentity.part(account.getEntityNo())
                                + ChannelIdentity.part(account.getAccountNo())));
        op.setDestination("PROVIDER");
        op.setOperationType(type);
        op.setBusinessRef(account.getAccountNo());
        op.setBusinessRevision(Long.toString(account.getVersion() + 1));
        op.setSemanticKey(ChannelIdentity.hash(type + ":" + account.getVersion()));
        op.setRequestHash(ChannelIdentity.hash(request));
        op.setRequestRef(number);
        op.setStatus("SENDING");
        op.setFence(1L);
        op.setCurrentAttemptNo(1L);
        op.setLeaseUntil(now().plusSeconds(90));
        op.setNextRecoveryAt(op.getLeaseUntil());
        return op;
    }

    private void attempt(CimOperation op, String kind) {
        var row = new CimOperationAttempt();
        audit(row, op.getCreatedBy());
        row.setAttemptRef(BizKey.next("CIAT"));
        row.setEntityNo(op.getEntityNo());
        row.setOperationNo(op.getOperationNo());
        row.setAttemptNo(op.getCurrentAttemptNo());
        row.setFence(op.getFence());
        row.setAttemptKind(kind);
        row.setStartedAt(now());
        row.setOutcome("STARTED");
        attempts.insert(row);
    }

    private void closeAttempt(CimOperation op, String outcome, String evidence) {
        int changed =
                attempts.update(
                        Wrappers.<CimOperationAttempt>update()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", op.getEntityNo())
                                .eq("operation_no", op.getOperationNo())
                                .eq("attempt_no", op.getCurrentAttemptNo())
                                .eq("fence", op.getFence())
                                .eq("outcome", "STARTED")
                                .set("outcome", outcome)
                                .set("finished_at", now())
                                .set("response_ref", evidence)
                                .set(
                                        "error_code",
                                        "UNKNOWN".equals(outcome) ? "PROVIDER_UNCONFIRMED" : null)
                                .set("updated_at", now())
                                .set("updated_by", op.getCreatedBy()));
        if (changed != 1) conflict();
    }

    private void updateOperation(CimOperation op, String status, UpdateWrapper<CimOperation> q) {
        int changed =
                operations.update(
                        q.eq("tenant_no", "MAIN")
                                .eq("entity_no", op.getEntityNo())
                                .eq("operation_no", op.getOperationNo())
                                .eq("version", op.getVersion())
                                .set("status", status)
                                .set("version", op.getVersion() + 1)
                                .set("updated_at", now())
                                .set("updated_by", op.getCreatedBy()));
        if (changed != 1) conflict();
        op.setStatus(status);
        op.setVersion(op.getVersion() + 1);
    }

    private void setAccount(
            CimAccount account,
            String status,
            String activeOperation,
            String authorization,
            String actor) {
        int changed =
                accounts.update(
                        Wrappers.<CimAccount>update()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", account.getEntityNo())
                                .eq("account_no", account.getAccountNo())
                                .eq("version", account.getVersion())
                                .set("status", status)
                                .set("active_operation_no", activeOperation)
                                .set("active_authorization_no", authorization)
                                .set("version", account.getVersion() + 1)
                                .set("updated_at", now())
                                .set("updated_by", actor));
        if (changed != 1) conflict();
    }

    private void requireCurrent(CimAccount account, CimOperation op) {
        if (!op.getOperationNo().equals(account.getActiveOperationNo())
                || !op.getBusinessRevision().equals(account.getVersion().toString())) conflict();
    }

    private CimAccount account(String entity, String accountNo, boolean lock) {
        var q =
                Wrappers.<CimAccount>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", entity)
                        .eq("account_no", accountNo);
        if (lock) q.last("FOR UPDATE");
        var row = accounts.selectOne(q);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private CimOperation operation(String entity, String number, boolean lock) {
        var q =
                Wrappers.<CimOperation>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", entity)
                        .eq("operation_no", number);
        if (lock) q.last("FOR UPDATE");
        var row = operations.selectOne(q);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private CimAuthorizationRevision revision(String entity, String account, String number) {
        var row =
                revisions.selectOne(
                        Wrappers.<CimAuthorizationRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", entity)
                                .eq("account_no", account)
                                .eq("authorization_no", number));
        if (row == null) conflict();
        return row;
    }

    private CimProductProfile profile(String number) {
        var row =
                profiles.selectOne(
                        Wrappers.<CimProductProfile>query()
                                .eq("tenant_no", "MAIN")
                                .eq("profile_no", number));
        if (row == null || !"VERIFIED".equals(row.getStatus())) conflict();
        return row;
    }

    private ChannelAuthorizationGateway gateway(String profile) {
        var gateway = gateways.get(profile);
        if (gateway == null) conflict();
        return gateway;
    }

    private static AuthorizationContext authContext(
            CimAccount account, CimOperation op, String state) {
        return new AuthorizationContext(
                op.getOperationNo(),
                account.getEntityNo(),
                account.getAccountNo(),
                op.getProfileNo(),
                account.getExternalAccountRef(),
                op.getCreatedBy(),
                state,
                op.getReturnTargetCode());
    }

    private static void validateGrant(CimAccount account, Grant grant) {
        if (grant == null
                || !account.getExternalAccountRef().equals(grant.externalAccountRef())
                || grant.grantedScopes() == null
                || grant.grantedScopes().isEmpty()
                || grant.grantedScopes().size() > 64
                || grant.authorizedAt() == null
                || grant.authorizedAt().isAfter(Instant.now())
                || grant.expiresAt() == null
                || !grant.expiresAt().isAfter(Instant.now())) conflict();
        CommerceValidation.text(grant.credentialRef(), 128);
        CommerceValidation.text(grant.receiptRef(), 256);
        grant.grantedScopes().forEach(scope -> CommerceValidation.text(scope, 64));
    }

    private static void audit(BaseEntity row, String actor) {
        row.setTenantNo("MAIN");
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
        row.setCreatedAt(now());
        row.setUpdatedAt(row.getCreatedAt());
        row.setVersion(0L);
        row.setDeleted(0);
    }

    private static void context(String entity, String account, String actor) {
        CommerceValidation.text(entity, 32);
        CommerceValidation.text(account, 32);
        CommerceValidation.text(actor, 32);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) conflict();
    }

    private static void bad() {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static void conflict() {
        throw BizException.of(ErrorCode.CONFLICT);
    }
}
