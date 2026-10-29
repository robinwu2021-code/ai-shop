package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.audit;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.now;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.require;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.text;

import ai.neargo.shop.commerce.dto.StatementContracts;
import ai.neargo.shop.commerce.entity.CimAccount;
import ai.neargo.shop.commerce.entity.CimAuthorizationRevision;
import ai.neargo.shop.commerce.entity.CimOperation;
import ai.neargo.shop.commerce.entity.CimOperationAttempt;
import ai.neargo.shop.commerce.entity.CimStatement;
import ai.neargo.shop.commerce.entity.CimStatementLine;
import ai.neargo.shop.commerce.entity.CimStoreConnection;
import ai.neargo.shop.commerce.mapper.StatementLineMapper;
import ai.neargo.shop.commerce.mapper.StatementMapper;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.commerce.policy.ChannelStatementPolicy;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.commerce.service.ChannelReconciliationService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelRecoveryPort;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Breakdown;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Document;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Lookup;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Receipt;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Scope;
import ai.neargo.shop.spi.commerce.CommerceGateway.Capability;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable supplier evidence is independently queryable; this service never grants local payment
 * or treasury settlement.
 */
public final class ChannelReconciliationServiceImpl
        implements ChannelReconciliationService, ChannelRecoveryPort {
    private final CatalogPersistence db;
    private final StatementMapper statements;
    private final StatementLineMapper lines;
    private final Map<String, ChannelStatementGateway> gateways;
    private final Clock clock;

    public ChannelReconciliationServiceImpl(
            CatalogPersistence db,
            StatementMapper statements,
            StatementLineMapper lines,
            List<ChannelStatementGateway> gateways,
            Clock clock) {
        this.db = db;
        this.statements = statements;
        this.lines = lines;
        this.clock = clock;
        var index = new HashMap<String, ChannelStatementGateway>();
        for (var gateway : gateways)
            require(index.putIfAbsent(gateway.profileNo(), gateway) == null);
        this.gateways = Map.copyOf(index);
    }

    @Override
    public String workflow() {
        return "CHANNEL_STATEMENT";
    }

    private record Claim(
            CimOperation op,
            CimStatement statement,
            Lookup lookup,
            long attempt,
            long fence,
            boolean query,
            ChannelStatementGateway gateway) {}

    @Override
    public StatementContracts.Imported importStatement(
            String entity,
            String account,
            String actor,
            StatementContracts.Import request,
            String key) {
        require(request != null);
        text(request.statementRef(), 128);
        text(request.sourceRevision(), 128);
        require(request.documentHash() != null && request.documentHash().matches("[a-f0-9]{64}"));
        return db.commands.execute(
                entity,
                null,
                actor,
                "IMPORT_STATEMENT:" + account,
                key,
                request,
                StatementContracts.Imported.class,
                () -> queue(entity, account, actor, request),
                StatementContracts.Imported::statementNo);
    }

    private StatementContracts.Imported queue(
            String entity, String account, String actor, StatementContracts.Import request) {
        var owner = account(entity, account, true);
        var grant = grant(owner, null);
        var gateway = gateways.get(owner.getProfileNo());
        require(gateway != null && gateway.capabilities().contains(Capability.STATEMENT));
        var existing =
                statements.selectOne(
                        Wrappers.<CimStatement>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", entity)
                                .eq("account_no", account)
                                .eq("statement_ref", request.statementRef())
                                .eq("source_revision", request.sourceRevision()));
        if (existing != null) {
            require(existing.getExpectedHash().equals(request.documentHash()));
            return new StatementContracts.Imported(
                    existing.getStatementNo(), existing.getOperationNo(), existing.getStatus());
        }
        String number = BizKey.next("CIST"), operation = BizKey.next("CIOP");
        var lookup =
                new Lookup(
                        operation,
                        new Scope(
                                entity,
                                account,
                                owner.getProfileNo(),
                                owner.getExternalAccountRef(),
                                grant.getAuthorizationNo(),
                                grant.getCredentialRef()),
                        request.statementRef(),
                        request.sourceRevision(),
                        request.documentHash());
        ChannelStatementPolicy.lookup(lookup);
        var statement = audit(new CimStatement(), actor);
        statement.setStatementNo(number);
        statement.setEntityNo(entity);
        statement.setAccountNo(account);
        statement.setProfileNo(owner.getProfileNo());
        statement.setStatementRef(request.statementRef());
        statement.setSourceRevision(request.sourceRevision());
        statement.setExpectedHash(request.documentHash());
        statement.setLookupJson(db.json.writeValueAsString(lookup));
        statement.setStatus("QUEUED");
        statement.setOperationNo(operation);
        statements.insert(statement);
        var op = audit(new CimOperation(), actor);
        op.setOperationNo(operation);
        op.setEntityNo(entity);
        op.setAccountNo(account);
        op.setProfileNo(owner.getProfileNo());
        op.setDestination("CHANNEL");
        op.setOperationType("STATEMENT_IMPORT");
        op.setBusinessRef(number);
        op.setBusinessRevision(request.sourceRevision());
        op.setSemanticKey(ChannelIdentity.hash(number));
        op.setRequestHash(PricingSnapshotHash.of(lookup));
        op.setRequestRef("STATEMENT:" + number);
        op.setStatus("QUEUED");
        op.setCurrentAttemptNo(0L);
        op.setFence(0L);
        op.setNextRecoveryAt(now());
        op.setTargetScopeKind("ACCOUNT");
        op.setTargetScopeKey(ChannelIdentity.hash(account));
        db.operations.insert(op);
        return new StatementContracts.Imported(number, operation, "QUEUED");
    }

    private CimAccount account(String entity, String number, boolean lock) {
        text(entity, 32);
        text(number, 32);
        var q =
                Wrappers.<CimAccount>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", entity)
                        .eq("account_no", number);
        if (lock) q.last("FOR UPDATE");
        var row = db.accounts.selectOne(q);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private CimAuthorizationRevision grant(CimAccount owner, Lookup original) {
        require("ACTIVE".equals(owner.getStatus()) && owner.getActiveAuthorizationNo() != null);
        if (original != null)
            require(
                    original.scope().authorizationNo().equals(owner.getActiveAuthorizationNo())
                            && original.scope().profileNo().equals(owner.getProfileNo())
                            && original.scope()
                                    .externalAccountRef()
                                    .equals(owner.getExternalAccountRef()));
        var grant =
                db.authorizations.selectOne(
                        Wrappers.<CimAuthorizationRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", owner.getEntityNo())
                                .eq("account_no", owner.getAccountNo())
                                .eq("authorization_no", owner.getActiveAuthorizationNo()));
        require(
                grant != null
                        && "GRANTED".equals(grant.getStatus())
                        && grant.getCredentialRef() != null
                        && (grant.getExpiresAt() == null || grant.getExpiresAt().isAfter(now()))
                        && (original == null
                                || original.scope()
                                        .credentialRef()
                                        .equals(grant.getCredentialRef())));
        var scopes = db.json.readTree(grant.getGrantedScopes());
        require(scopes.isArray());
        boolean allowed = false;
        // An exact account-wide grant is required; a store-specific scope never widens to the whole
        // account.
        for (var scope : scopes)
            if (scope.isString() && "STATEMENT".equals(scope.asString())) allowed = true;
        require(allowed);
        return grant;
    }

    @Override
    public int recoverDue(int limit) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Statement HTTP must be outside CIM transactions");
        require(limit >= 1 && limit <= 100);
        var due =
                db.commands.query(
                        () ->
                                db.operations.selectList(
                                        Wrappers.<CimOperation>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq("operation_type", "STATEMENT_IMPORT")
                                                .in("status", "QUEUED", "RUNNING", "UNKNOWN")
                                                .le("next_recovery_at", now())
                                                .orderByAsc("next_recovery_at", "id")
                                                .last("LIMIT " + limit)));
        int complete = 0;
        for (var candidate : due) {
            Claim claim = null;
            try {
                claim = db.commands.atomic(() -> claim(candidate));
                if (claim == null) continue;
                var result =
                        claim.query()
                                ? claim.gateway().queryStatement(claim.lookup())
                                : claim.gateway().fetchStatement(claim.lookup());
                ChannelStatementPolicy.receipt(claim.lookup(), result, clock);
                var current = claim;
                if (db.commands.atomic(() -> finish(current, result))) complete++;
            } catch (RuntimeException unknown) {
                if (claim != null) {
                    var failed = claim;
                    db.commands.atomic(() -> finish(failed, null));
                } else defer(candidate);
            }
        }
        return complete;
    }

    private Claim claim(CimOperation candidate) {
        var owner = account(candidate.getEntityNo(), candidate.getAccountNo(), true);
        var op = operation(candidate.getEntityNo(), candidate.getOperationNo());
        var statement = statement(candidate.getEntityNo(), candidate.getBusinessRef(), true);
        if (!Set.of("QUEUED", "RUNNING", "UNKNOWN").contains(op.getStatus())
                || op.getNextRecoveryAt() == null
                || op.getNextRecoveryAt().isAfter(now())
                || op.getLeaseUntil() != null && op.getLeaseUntil().isAfter(now())) return null;
        var lookup = db.json.readValue(statement.getLookupJson(), Lookup.class);
        ChannelStatementPolicy.lookup(lookup);
        require(
                op.getOperationNo().equals(lookup.operationNo())
                        && op.getRequestHash().equals(PricingSnapshotHash.of(lookup)));
        boolean query = !"QUEUED".equals(op.getStatus());
        if (!query) grant(owner, lookup);
        var gateway = gateways.get(op.getProfileNo());
        require(gateway != null && gateway.capabilities().contains(Capability.STATEMENT));
        long attempt = Math.addExact(op.getCurrentAttemptNo(), 1),
                fence = Math.addExact(op.getFence(), 1);
        var journal = audit(new CimOperationAttempt(), "SYSTEM");
        journal.setAttemptRef(BizKey.next("CIAT"));
        journal.setEntityNo(op.getEntityNo());
        journal.setOperationNo(op.getOperationNo());
        journal.setAttemptNo(attempt);
        journal.setFence(fence);
        journal.setAttemptKind("QUERY");
        journal.setProviderRequestRef(op.getOperationNo());
        journal.setStartedAt(now());
        journal.setOutcome("RUNNING");
        db.attempts.insert(journal);
        require(
                db.operations.update(
                                Wrappers.<CimOperation>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("id", op.getId())
                                        .eq("fence", op.getFence())
                                        .set("status", "RUNNING")
                                        .set("current_attempt_no", attempt)
                                        .set("fence", fence)
                                        .set("lease_until", now().plusMinutes(2))
                                        .set("next_recovery_at", now().plusMinutes(2))
                                        .setSql("version=version+1"))
                        == 1);
        return new Claim(op, statement, lookup, attempt, fence, query, gateway);
    }

    private boolean finish(Claim claim, Receipt result) {
        account(claim.lookup().scope().entityNo(), claim.lookup().scope().accountNo(), true);
        var op = operation(claim.op().getEntityNo(), claim.op().getOperationNo());
        var statement = statement(op.getEntityNo(), claim.statement().getStatementNo(), true);
        var attempt =
                db.attempts.selectOne(
                        Wrappers.<CimOperationAttempt>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", op.getEntityNo())
                                .eq("operation_no", op.getOperationNo())
                                .eq("attempt_no", claim.attempt())
                                .last("FOR UPDATE"));
        require(attempt != null);
        if (op.getFence() != claim.fence() || op.getCurrentAttemptNo() != claim.attempt()) {
            if (result != null && result.originalReceiptRef() != null)
                db.attempts.update(
                        Wrappers.<CimOperationAttempt>update()
                                .eq("tenant_no", "MAIN")
                                .eq("id", attempt.getId())
                                .isNull("late_evidence_ref")
                                .set("late_evidence_ref", result.originalReceiptRef())
                                .set("late_outcome", result.status().name()));
            return false;
        }
        boolean found = result != null && result.status() == ChannelStatementGateway.Status.FOUND;
        boolean rejected =
                result != null && result.status() == ChannelStatementGateway.Status.REJECTED;
        String status = found ? "SUCCEEDED" : rejected ? "FAILED" : "UNKNOWN";
        if (found) persistDocument(statement, result);
        else
            statements.update(
                    Wrappers.<CimStatement>update()
                            .eq("tenant_no", "MAIN")
                            .eq("id", statement.getId())
                            .set("status", status)
                            .setSql("version=version+1"));
        var receipt = result == null ? null : result.originalReceiptRef();
        db.attempts.update(
                Wrappers.<CimOperationAttempt>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", attempt.getId())
                        .isNull("finished_at")
                        .set("finished_at", now())
                        .set("outcome", status)
                        .set("response_ref", receipt));
        db.operations.update(
                Wrappers.<CimOperation>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", op.getId())
                        .eq("fence", claim.fence())
                        .set("status", status)
                        .set("lease_until", null)
                        .set("original_evidence_ref", receipt)
                        .set("next_recovery_at", found || rejected ? null : now().plusSeconds(30))
                        .setSql("version=version+1"));
        return found || rejected;
    }

    private void persistDocument(CimStatement statement, Receipt result) {
        var document = result.document();
        require(
                statement.getDocumentHash() == null
                        && statement.getExpectedHash().equals(result.documentHash()));
        var connections =
                db.connections.selectList(
                        Wrappers.<CimStoreConnection>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", statement.getEntityNo())
                                .eq("account_no", statement.getAccountNo())
                                .eq("profile_no", statement.getProfileNo())
                                .last("LIMIT 1001"));
        require(connections.size() <= 1000);
        boolean unmapped = false;
        for (var source : document.lines()) {
            var matched =
                    source.externalStoreRef() == null
                            ? List.<CimStoreConnection>of()
                            : connections.stream()
                                    .filter(
                                            c ->
                                                    source.externalStoreRef()
                                                            .equals(c.getExternalStoreRef()))
                                    .toList();
            var connection = matched.size() == 1 ? matched.getFirst() : null;
            unmapped |= connection == null;
            var line = audit(new CimStatementLine(), "SYSTEM");
            line.setLineNo(BizKey.next("CISL"));
            line.setEntityNo(statement.getEntityNo());
            line.setStatementNo(statement.getStatementNo());
            line.setAccountNo(statement.getAccountNo());
            line.setStoreNo(connection == null ? null : connection.getStoreNo());
            line.setConnectionNo(connection == null ? null : connection.getConnectionNo());
            line.setLineRef(source.lineRef());
            line.setKind(source.kind());
            line.setExternalStoreRef(source.externalStoreRef());
            line.setExternalOrderRef(source.externalOrderRef());
            line.setExternalAfterSaleRef(source.externalAfterSaleRef());
            line.setCurrency(document.currency());
            line.setDifferenceCode(connection == null ? "UNMAPPED" : "UNVERIFIED_LOCAL");
            line.setAmountsJson(db.json.writeValueAsString(source.amounts()));
            lines.insert(line);
        }
        statements.update(
                Wrappers.<CimStatement>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", statement.getId())
                        .isNull("document_hash")
                        .set("document_hash", result.documentHash())
                        .set("payload_json", db.json.writeValueAsString(document))
                        .set("currency", document.currency())
                        .set(
                                "period_start",
                                LocalDateTime.ofInstant(document.periodStart(), ZoneOffset.UTC))
                        .set(
                                "period_end",
                                LocalDateTime.ofInstant(document.periodEnd(), ZoneOffset.UTC))
                        .set("status", unmapped ? "UNMAPPED" : "TECHNICAL_ONLY")
                        .set("original_receipt_ref", result.originalReceiptRef())
                        .setSql("version=version+1"));
    }

    private CimOperation operation(String entity, String number) {
        var row =
                db.operations.selectOne(
                        Wrappers.<CimOperation>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", entity)
                                .eq("operation_no", number)
                                .last("FOR UPDATE"));
        require(row != null);
        return row;
    }

    private CimStatement statement(String entity, String number, boolean lock) {
        var q =
                Wrappers.<CimStatement>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", entity)
                        .eq("statement_no", number);
        if (lock) q.last("FOR UPDATE");
        var row = statements.selectOne(q);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private void defer(CimOperation candidate) {
        db.commands.atomic(
                () -> {
                    db.operations.update(
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

    @Override
    public PageData<StatementContracts.Statement> listStatements(
            String entity, String account, StatementContracts.Query request) {
        require(request != null);
        long offset = CommerceValidation.offset(request.page(), request.size());
        if (request.currency() != null) require(request.currency().matches("[A-Z]{3}"));
        if (request.status() != null)
            require(
                    Set.of("QUEUED", "UNKNOWN", "FAILED", "TECHNICAL_ONLY", "UNMAPPED")
                            .contains(request.status()));
        if (request.from() != null && request.to() != null)
            require(request.from().isBefore(request.to()));
        return db.commands.query(
                () -> {
                    account(entity, account, false);
                    var q =
                            Wrappers.<CimStatement>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", entity)
                                    .eq("account_no", account)
                                    .eq(request.currency() != null, "currency", request.currency())
                                    .eq(request.status() != null, "status", request.status())
                                    .ge(
                                            request.from() != null,
                                            "period_start",
                                            request.from() == null
                                                    ? null
                                                    : LocalDateTime.ofInstant(
                                                            request.from(), ZoneOffset.UTC))
                                    .lt(
                                            request.to() != null,
                                            "period_start",
                                            request.to() == null
                                                    ? null
                                                    : LocalDateTime.ofInstant(
                                                            request.to(), ZoneOffset.UTC));
                    long total = statements.selectCount(q);
                    var rows =
                            statements.selectList(
                                    q.orderByDesc("created_at", "id")
                                            .last("LIMIT " + request.size() + " OFFSET " + offset));
                    return PageData.of(
                            rows.stream()
                                    .map(
                                            row -> {
                                                var doc =
                                                        row.getPayloadJson() == null
                                                                ? null
                                                                : db.json.readValue(
                                                                        row.getPayloadJson(),
                                                                        Document.class);
                                                return new StatementContracts.Statement(
                                                        row.getStatementNo(),
                                                        row.getAccountNo(),
                                                        row.getStatementRef(),
                                                        row.getSourceRevision(),
                                                        row.getDocumentHash(),
                                                        row.getCurrency(),
                                                        row.getPeriodStart() == null
                                                                ? null
                                                                : row.getPeriodStart()
                                                                        .toInstant(ZoneOffset.UTC),
                                                        row.getPeriodEnd() == null
                                                                ? null
                                                                : row.getPeriodEnd()
                                                                        .toInstant(ZoneOffset.UTC),
                                                        row.getStatus(),
                                                        row.getOperationNo(),
                                                        row.getOriginalReceiptRef(),
                                                        doc == null ? null : doc.totals());
                                            })
                                    .toList(),
                            total,
                            request.page(),
                            request.size());
                });
    }

    @Override
    public PageData<StatementContracts.Line> listStoreLines(
            String entity, String store, StatementContracts.LineQuery request) {
        require(request != null);
        text(entity, 32);
        text(store, 32);
        long offset = CommerceValidation.offset(request.page(), request.size());
        if (request.statementNo() != null) text(request.statementNo(), 32);
        if (request.connectionNo() != null) text(request.connectionNo(), 32);
        if (request.differenceCode() != null)
            require(Set.of("UNVERIFIED_LOCAL", "UNMAPPED").contains(request.differenceCode()));
        return db.commands.query(
                () -> {
                    if (request.connectionNo() != null)
                        db.connection(entity, store, request.connectionNo(), false, false);
                    var q =
                            Wrappers.<CimStatementLine>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", entity)
                                    .eq("store_no", store)
                                    .eq(
                                            request.statementNo() != null,
                                            "statement_no",
                                            request.statementNo())
                                    .eq(
                                            request.connectionNo() != null,
                                            "connection_no",
                                            request.connectionNo())
                                    .eq(
                                            request.differenceCode() != null,
                                            "difference_code",
                                            request.differenceCode());
                    long total = lines.selectCount(q);
                    var rows =
                            lines.selectList(
                                    q.orderByDesc("created_at", "id")
                                            .last("LIMIT " + request.size() + " OFFSET " + offset));
                    return PageData.of(
                            rows.stream()
                                    .map(
                                            row ->
                                                    new StatementContracts.Line(
                                                            row.getLineNo(),
                                                            row.getStatementNo(),
                                                            row.getStoreNo(),
                                                            row.getConnectionNo(),
                                                            row.getLineRef(),
                                                            row.getKind(),
                                                            row.getExternalOrderRef(),
                                                            row.getExternalAfterSaleRef(),
                                                            row.getCurrency(),
                                                            row.getDifferenceCode(),
                                                            db.json.readValue(
                                                                    row.getAmountsJson(),
                                                                    Breakdown.class)))
                                    .toList(),
                            total,
                            request.page(),
                            request.size());
                });
    }
}
