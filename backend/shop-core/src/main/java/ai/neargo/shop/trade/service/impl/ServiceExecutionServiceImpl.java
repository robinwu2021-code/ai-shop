package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockDispositionPort;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.EvidenceOutcome;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.OriginalFacts;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.PrepareResult;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.Continuity;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.ContinuityState;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.Suspension;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.UnitState;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort.CurrentActor;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionLookup;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.Grant;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.GrantState;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.PrepareExecution;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntent;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntentLookup;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.SourceSuspension;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import ai.neargo.shop.trade.entity.OrdCheckoutOperation;
import ai.neargo.shop.trade.entity.OrdManualServiceAdmission;
import ai.neargo.shop.trade.entity.OrdServiceExecution;
import ai.neargo.shop.trade.entity.OrdServiceExecutionAction;
import ai.neargo.shop.trade.entity.OrdServiceExecutionUnit;
import ai.neargo.shop.trade.mapper.CheckoutMappers;
import ai.neargo.shop.trade.mapper.ManualAdmissionMapper;
import ai.neargo.shop.trade.mapper.ServiceExecutionMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.policy.ServiceExecutionSchemaVerifier;
import ai.neargo.shop.trade.order.service.create.CheckoutClock;
import ai.neargo.shop.trade.order.service.create.CheckoutDigests;
import ai.neargo.shop.trade.service.ServiceExecutionService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Canonical Core roots serialize permanent units without rewriting purchase or source observation versions. */
@Service
public final class ServiceExecutionServiceImpl implements ServiceExecutionService {
    private final CheckoutMappers.OperationMapper operations;
    private final ManualAdmissionMapper admissions;
    private final ServiceExecutionMapper executions;
    private final ServiceExecutionMapper.UnitMapper units;
    private final ServiceExecutionMapper.ActionMapper actions;
    private final ManualExecutionSourceReader reader;
    private final ObjectProvider<ServiceExecutionSourcePort> sources;
    private final ObjectProvider<PersonnelOperatorFactsPort> personnel;
    private final StockDispositionPort stock;
    private final ServiceExecutionSchemaVerifier schema;
    private final ObjectMapper json;
    private final CheckoutClock clock;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    /** Providers are resolved per use so default retail never requires an enabled beauty plugin. */
    public ServiceExecutionServiceImpl(CheckoutMappers.OperationMapper operations,
            CheckoutMappers.QuoteMapper quotes, CheckoutMappers.ConfirmationMapper confirmations,
            CheckoutMappers.LineMapper mappings, OrderItemMapper items, ManualAdmissionMapper admissions,
            ServiceExecutionMapper executions, ServiceExecutionMapper.UnitMapper units,
            ServiceExecutionMapper.ActionMapper actions, ObjectProvider<ServiceExecutionSourcePort> sources,
            ObjectProvider<PersonnelOperatorFactsPort> personnel, StockDispositionPort stock,
            ServiceExecutionSchemaVerifier schema, ObjectMapper json, CheckoutClock clock,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.operations = operations;
        this.admissions = admissions;
        this.executions = executions;
        this.units = units;
        this.actions = actions;
        this.reader = new ManualExecutionSourceReader(json, quotes, confirmations, mappings, items, admissions);
        this.sources = sources;
        this.personnel = personnel;
        this.stock = stock;
        this.schema = schema;
        this.json = json;
        this.clock = clock;
        writes = new TransactionTemplate(manager);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads = new TransactionTemplate(manager);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Grant prepare(PrepareExecution request) {
        DelegatedIdentityContext.outside();
        available();
        var binding = request.binding();
        var actor = current(binding.scope());
        var intent = source().inspect(new StartIntentLookup(binding));
        require(intent != null && binding.equals(intent.binding())
                && actor.namespaceHash().equals(intent.operatorNamespaceHash())
                && actor.personnel().personnelNo().equals(intent.personnel().personnelNo()));
        var replay = unscoped(() -> reads.execute(tx -> executions.findKey(binding.scope(), request.requestKeyHash())));
        if (replay != null) {
            requireOriginal(replay, binding);
            require(request.actionNo().equals(replay.getPrepareActionNo())
                    && request.actionHash().equals(replay.getPrepareActionHash())
                    && ServiceExecutionContract.intentHash(intent).equals(replay.getSourceIntentHash()));
            requireSameActor(binding.scope(), actor);
            return queryOriginal(new ExecutionLookup(binding));
        }
        requireIntent(intent, binding, actor);
        require(request.actionHash().equals(ServiceExecutionContract.actionHash(binding, "PREPARE",
                request.actionNo(), 0, actor.namespaceHash(), new StartIntentLookup(binding), null)));
        var declaration = unscoped(() -> admissions.snapshot("BEAUTY", binding.sourceOperationNo(),
                binding.scope().entityNo(), binding.scope().storeNo()));
        require(declaration != null && "ADMITTED".equals(declaration.getPhase()));
        var original = json.readValue(declaration.getStockManifestJson(), StockDispositionPort.Original.class);
        var disposition = stock.observeOriginal(new StockDispositionPort.Lookup(original,
                declaration.getStockOperationNo()));
        requireStock(original, declaration.getStockOperationNo(), disposition);
        requireSameActor(binding.scope(), actor);
        return unscoped(() -> writes.execute(tx -> prepareLocked(request, intent, actor, declaration, disposition)));
    }

    private Grant prepareLocked(PrepareExecution request, StartIntent intent, Actor actor,
            OrdManualServiceAdmission observed, StockDispositionPort.Disposition stockProof) {
        var b = request.binding();
        var parent = admissions.lockParent(b.orderNo());
        var children = admissions.lockChildren(b.orderNo());
        var child = children.stream().filter(c -> b.subOrderNo().equals(c.getSubOrderNo()))
                .findFirst().orElseThrow(ServiceExecutionServiceImpl::conflict);
        var line = executions.lockLine(b);
        require(line != null);
        var existingUnits = units.lockLineUnits(b.scope(), b.sourceLineNo());
        executions.lockSourceExecutions(b.scope(), b.sourceLineNo());
        var op = operations.lock("BEAUTY", b.sourceOperationNo());
        requireCoordinates(op, b);
        var declaration = admissions.lockByOrder(b.scope().entityNo(), b.scope().storeNo(), b.orderNo());
        require(declaration != null && Objects.equals(observed.getVersion(), declaration.getVersion())
                && Objects.equals(observed.getStockManifestJson(), declaration.getStockManifestJson())
                && Objects.equals(observed.getStockOperationNo(), declaration.getStockOperationNo())
                && "ADMITTED".equals(declaration.getPhase()));
        var actual = reader.loadLocked(new ExecutionCoordinates(b.scope(), b.caller(), b.sourceOperationNo(),
                b.orderNo(), b.subOrderNo()), b);
        require(Objects.equals(line.getOrderItemId(), actual.line().getOrderItemId())
                && Objects.equals(line.getVersion(), actual.line().getVersion())
                && Objects.equals(declaration.getStockObservationHash(), stockProof.observationHash())
                && b.attemptNo() == 1);
        String partyDigest = IdempotencyScope.requestHash("BEAUTY_EXECUTION_PARTY_V1",
                ServiceExecutionContract.canonical(new CustomerParty(
                        op.getCustomerKind(), op.getBuyerNo(), op.getGuestNo(), op.getIdentityRevision())));
        require(partyDigest.equals(intent.customerPartyDigest()));
        var prior = executions.findKey(b.scope(), request.requestKeyHash());
        if (prior != null) {
            requireOriginal(prior, b);
            require(prior.getPrepareActionNo().equals(request.actionNo())
                    && prior.getPrepareActionHash().equals(request.actionHash()));
            return grant(prior, b);
        }
        require(units.findTask(b.scope(), b.taskNo()) == null
                && existingUnits.stream().noneMatch(u -> u.getUnitOrdinal() == b.unitOrdinal())
                && existingUnits.stream().allMatch(u -> u.getOrderedUnits().equals(line.getQuantity())
                        && b.sourceOperationNo().equals(u.getSourceOperationNo())
                        && b.orderNo().equals(u.getOrderNo()) && b.subOrderNo().equals(u.getSubOrderNo())));
        var unit = new OrdServiceExecutionUnit();
        stamp(unit, actor.identity().actorNo());
        unit.setUnitNo(number());
        unit.setEntityNo(b.scope().entityNo());
        unit.setStoreNo(b.scope().storeNo());
        unit.setCaller("BEAUTY");
        unit.setSourceOperationNo(b.sourceOperationNo());
        unit.setOrderNo(b.orderNo());
        unit.setSubOrderNo(b.subOrderNo());
        unit.setSourceLineNo(b.sourceLineNo());
        unit.setOrderItemId(line.getOrderItemId());
        unit.setUnitOrdinal(b.unitOrdinal());
        unit.setOrderedUnits(line.getQuantity());
        unit.setQuantity(1);
        unit.setUom("COUNT");
        unit.setTaskNo(b.taskNo());
        unit.setWorkOrderNo(b.workOrderNo());
        unit.setBindingNo(b.bindingNo());
        unit.setBindingRevision(b.bindingRevision());
        unit.setState("BOUND");
        unit.setAttemptNo(0);
        unit.setSourceIdentityHash(CheckoutDigests.hash(ServiceExecutionContract.canonical(List.of(
                b.scope(), b.sourceOperationNo(), b.orderNo(), b.subOrderNo(),
                b.sourceLineNo(), line.getOrderItemId()))));
        one(units.insertBound(unit));
        one(units.reserve(b, 0));
        var row = new OrdServiceExecution();
        stamp(row, actor.identity().actorNo());
        row.setExecutionNo(b.executionNo());
        row.setUnitNo(unit.getUnitNo());
        row.setEntityNo(b.scope().entityNo());
        row.setStoreNo(b.scope().storeNo());
        row.setAttemptNo(1);
        row.setGrantNo(number());
        row.setQuantity(1);
        row.setUom("COUNT");
        row.setState("PREPARED");
        row.setBindingJson(ServiceExecutionContract.bindingJson(b));
        row.setBindingHash(ServiceExecutionContract.bindingHash(b));
        row.setSourceIntentHash(ServiceExecutionContract.intentHash(intent));
        row.setPrepareActionNo(request.actionNo());
        row.setPrepareKeyHash(request.requestKeyHash());
        row.setPrepareActionHash(request.actionHash());
        row.setDeclarationNo(declaration.getDeclarationNo());
        row.setAdmissionRevision(declaration.getAdmissionRevision());
        row.setManualSourceRevision(b.sourceRevision());
        row.setManualSourceHash(b.sourceHash());
        row.setOriginalStockManifestHash(declaration.getStockManifestHash());
        row.setConfirmationNo(op.getConfirmationNo());
        row.setStockObservationHash(stockProof.observationHash());
        row.setSourceFenceNo(b.sourceFenceNo());
        row.setSourceFenceRevision(b.sourceFenceRevision());
        row.setResourceCommandNo(intent.resourceCommandNo());
        row.setResourceCommandHash(intent.resourceCommandHash());
        row.setObservationEpoch(0L);
        one(executions.insertPrepared(row));
        var action = new OrdServiceExecutionAction(); stamp(action, actor.identity().actorNo());
        action.setActionNo(request.actionNo());
        action.setExecutionNo(b.executionNo());
        action.setEntityNo(b.scope().entityNo());
        action.setStoreNo(b.scope().storeNo());
        action.setKind("PREPARE");
        action.setActorNamespaceHash(actor.namespaceHash());
        action.setRequestKeyHash(request.requestKeyHash());
        action.setActionHash(request.actionHash());
        action.setExpectedRevision(0L);
        action.setObservationEpoch(0L);
        one(actions.insertPending(action));
        one(actions.finalizeResult(b.scope(), b.executionNo(), request.actionNo(), request.actionHash(),
                0, 0, "PREPARED", 1));
        return grant(row, b);
    }

    @Override
    public Grant query(ExecutionLookup request) {
        DelegatedIdentityContext.outside(); available();
        var actor = current(request.binding().scope());
        var intent = source().inspect(new StartIntentLookup(request.binding()));
        require(intent.binding().equals(request.binding())
                && actor.namespaceHash().equals(intent.operatorNamespaceHash())
                && actor.personnel().personnelNo().equals(intent.personnel().personnelNo()));
        requireSameActor(request.binding().scope(), actor);
        return queryOriginal(request);
    }

    @Override
    public Grant queryOriginal(ExecutionLookup request) {
        DelegatedIdentityContext.outside(); available();
        return unscoped(() -> reads.execute(tx -> {
            var b = request.binding();
            var row = executions.find(b.scope(), b.executionNo());
            if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
            requireOriginal(row, b);
            return grant(row, b);
        }));
    }

    @Override
    public ExecutionReference referenceOriginal(ExecutionLookup request) {
        DelegatedIdentityContext.outside();
        available();
        var b = request.binding();
        var actor = current(b.scope());
        var before = unscoped(() -> reads.execute(tx -> evidenceSnapshot(b.scope(), b.executionNo())));
        if (before == null) throw BizException.of(ErrorCode.NOT_FOUND);
        require(before.binding().equals(b));
        var intent = source().inspect(new StartIntentLookup(b));
        requireEvidenceIntent(before, intent);
        requireHumanIntent(actor, intent);
        var after = unscoped(() -> reads.execute(tx -> evidenceSnapshot(b.scope(), b.executionNo())));
        require(before.equals(after));
        requireSameActor(b.scope(), actor);
        return before.reference();
    }

    @Override
    public CurrentActor authorizeCurrent(ExecutionEvidenceLookup request) {
        DelegatedIdentityContext.outside();
        available();
        var ref = request.reference();
        var actor = current(ref.scope());
        var before = unscoped(() -> reads.execute(tx -> evidenceSnapshot(ref.scope(), ref.executionNo())));
        if (before == null) throw BizException.of(ErrorCode.NOT_FOUND);
        require(ref.equals(before.reference()));
        var intent = source().inspect(new StartIntentLookup(before.binding()));
        requireEvidenceIntent(before, intent);
        requireHumanIntent(actor, intent);
        require("READY_UNCHANGED".equals(intent.continuity().state()) && intent.suspension() == null
                && before.state() == GrantState.PREPARED && before.reason() == null);
        var after = unscoped(() -> reads.execute(tx -> evidenceSnapshot(ref.scope(), ref.executionNo())));
        require(before.equals(after));
        requireSameActor(ref.scope(), actor);
        return new CurrentActor(ref.referenceHash(), ref.scope(), actor.identity().actorNo(), actor.namespaceHash(),
                actor.personnel(), null).signed();
    }

    private static void requireHumanIntent(Actor actor, StartIntent intent) {
        require(actor.namespaceHash().equals(intent.operatorNamespaceHash())
                && actor.personnel().personnelNo().equals(intent.personnel().personnelNo()));
    }

    @Override
    public ExecutionOriginalEvidence queryOriginalEvidence(ExecutionEvidenceLookup request) {
        DelegatedIdentityContext.outside();
        var ref = request.reference();
        if (schema.installation() != ServiceExecutionSchemaVerifier.Installation.INSTALLED) {
            return unavailableEvidence(ref, EvidenceOutcome.PENDING, "EXECUTION_SCHEMA_UNAVAILABLE");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            EvidenceSnapshot before;
            try {
                before = unscoped(() -> reads.execute(tx -> evidenceSnapshot(ref.scope(), ref.executionNo())));
            } catch (UnsupportedOperationException unsupported) {
                return unavailableEvidence(ref, EvidenceOutcome.UNSUPPORTED, "SOURCE_SCHEMA_UNSUPPORTED");
            }
            if (before == null) return unavailableEvidence(ref, EvidenceOutcome.UNKNOWN, "SOURCE_UNKNOWN");
            require(ref.equals(before.reference()));
            StartIntent intent;
            try {
                intent = source().inspectOriginal(new StartIntentLookup(before.binding()));
            }
            catch (RuntimeException unavailable) {
                return unavailableEvidence(ref, EvidenceOutcome.PENDING, "SOURCE_UNKNOWN");
            }
            requireEvidenceIntent(before, intent);
            var after = unscoped(() -> reads.execute(tx -> evidenceSnapshot(ref.scope(), ref.executionNo())));
            if (!before.equals(after)) continue;
            var continuity = intent.continuity();
            String reason = before.reason();
            boolean suspended = before.state() == GrantState.SUSPEND_REQUESTED
                    || before.state() == GrantState.STARTED_SUSPENDED;
            if (!suspended && "PENDING".equals(continuity.state())) {
                return unavailableEvidence(ref, EvidenceOutcome.PENDING, "SOURCE_UNKNOWN");
            }
            var sourceState = suspended ? ContinuityState.SUSPENDED : ContinuityState.valueOf(continuity.state());
            if (!suspended) reason = continuity.reasonCode();
            var s = intent.suspension();
            Suspension suspension = s == null ? null : new Suspension(s.fenceNo(), s.executionNo(), s.bindingHash(),
                    s.intentRevision(), s.manualEpoch(), s.observationVersion(), s.observedSourceRevision(),
                    s.observedSourceHash(), s.reasonCode(), s.proofHash());
            var facts = new OriginalFacts(before.bindingJson(), ref.originalBindingBytesHash(),
                    ref.preparedGrantRevision(), before.prepare(), Math.addExact(before.version(), 1), before.version(),
                    before.epoch(), before.state(), before.unitState(), before.currentExecutionNo(),
                        before.unitAttempt(),
                    1, "COUNT", before.declarationNo(), before.confirmationNo(), before.admissionRevision(),
                    before.original(), before.stockOperation(), before.stockObservation(), ref.sourceIntentHash(),
                    ref.planHash(), ref.plannedResourceCommandNo(), ref.plannedResourceCommandHash(),
                        intent.frozenPlan(),
                    new Continuity(sourceState, continuity.manualEpoch(), continuity.observationVersion(),
                            continuity.sourceRevision(), continuity.sourceHash(), reason), suspension,
                    intent.operatorNamespaceHash(), intent.personnel());
            return new ExecutionOriginalEvidence("SERVICE_EXECUTION_ORIGINAL_EVIDENCE_V1", ref,
                    EvidenceOutcome.PROVEN, reason, facts, clock.now(), null).signed();
        }
        return unavailableEvidence(ref, EvidenceOutcome.PENDING, "SOURCE_UNKNOWN");
    }

    private ExecutionOriginalEvidence unavailableEvidence(ExecutionReference ref, EvidenceOutcome outcome,
        String reason) {
        return new ExecutionOriginalEvidence("SERVICE_EXECUTION_ORIGINAL_EVIDENCE_V1", ref, outcome,
                reason, null, clock.now(), null).signed();
    }

    private void requireEvidenceIntent(EvidenceSnapshot snapshot, StartIntent intent) {
        var b = snapshot.binding();
        var ref = snapshot.reference();
        require(intent != null && b.equals(intent.binding())
                && ref.sourceIntentHash().equals(ServiceExecutionContract.intentHash(intent))
                && ref.planHash().equals(intent.frozenPlanHash())
                && ref.plannedResourceCommandNo().equals(intent.resourceCommandNo())
                && ref.plannedResourceCommandHash().equals(intent.resourceCommandHash())
                && b.payloadHash().equals(ServiceExecutionContract.payloadHash(b, intent.personnel(),
                        intent.operatorNamespaceHash(), intent.resourceCommandNo(), intent.resourceCommandHash())));
        if (intent.suspension() != null) requireCommittedSuspension(intent, b);
    }

    private EvidenceSnapshot evidenceSnapshot(ExecutionScope scope, String executionNo) {
        var row = executions.find(scope, executionNo);
        if (row == null) return null;
        var envelope = json.readTree(row.getBindingJson());
        if (!"MANUAL_EXECUTION_V1".equals(envelope.path("schema").asString())) {
            throw new UnsupportedOperationException("SOURCE_SCHEMA_UNSUPPORTED");
        }
        var b = binding(row);
        require(b.scope().equals(scope) && b.executionNo().equals(executionNo)
                && Integer.valueOf(0).equals(row.getDeleted()) && Integer.valueOf(1).equals(row.getQuantity())
                && "COUNT".equals(row.getUom()) && row.getAttemptNo() == b.attemptNo());
        var unit = units.findUnit(scope, row.getUnitNo());
        require(unit != null && Integer.valueOf(0).equals(unit.getDeleted()) && "BEAUTY".equals(unit.getCaller())
                && b.sourceOperationNo().equals(unit.getSourceOperationNo()) && b.orderNo().equals(unit.getOrderNo())
                && b.subOrderNo().equals(unit.getSubOrderNo()) && b.sourceLineNo().equals(unit.getSourceLineNo())
                && b.unitOrdinal() == unit.getUnitOrdinal() && b.taskNo().equals(unit.getTaskNo())
                && b.workOrderNo().equals(unit.getWorkOrderNo()) && b.bindingNo().equals(unit.getBindingNo())
                && b.bindingRevision() == unit.getBindingRevision() && unit.getQuantity() == 1
                && "COUNT".equals(unit.getUom()) && row.getUnitNo().equals(unit.getUnitNo()));
        String sourceIdentity = CheckoutDigests.hash(ServiceExecutionContract.canonical(List.of(b.scope(),
                b.sourceOperationNo(), b.orderNo(), b.subOrderNo(), b.sourceLineNo(), unit.getOrderItemId())));
        require(sourceIdentity.equals(unit.getSourceIdentityHash()));
        var action = actions.findKey(scope, executionNo, "PREPARE", row.getPrepareKeyHash());
        require(action != null && Integer.valueOf(0).equals(action.getDeleted())
                && row.getPrepareActionNo().equals(action.getActionNo())
                && row.getPrepareActionHash().equals(action.getActionHash())
                && row.getPrepareKeyHash().equals(action.getRequestKeyHash())
                && executionNo.equals(action.getExecutionNo()) && scope.entityNo().equals(action.getEntityNo())
                && scope.storeNo().equals(action.getStoreNo()) && "PREPARE".equals(action.getKind())
                && Long.valueOf(0).equals(action.getExpectedRevision()) && "PREPARED".equals(action.getResultState())
                && action.getResultRevision() != null && action.getResultRevision() > 0);
        var op = operations.find("BEAUTY", b.sourceOperationNo());
        requireCoordinates(op, b);
        var admission = admissions.snapshot("BEAUTY", b.sourceOperationNo(), scope.entityNo(), scope.storeNo());
        require(admission != null && Integer.valueOf(0).equals(admission.getDeleted())
                && b.orderNo().equals(admission.getOrderNo()) && b.subOrderNo().equals(admission.getSubOrderNo())
                && row.getDeclarationNo().equals(admission.getDeclarationNo())
                && row.getConfirmationNo().equals(op.getConfirmationNo())
                && row.getAdmissionRevision() <= admission.getAdmissionRevision()
                && row.getOriginalStockManifestHash().equals(admission.getStockManifestHash()));
        // Admission observation is current and may advance on withdrawal. The grant preserves the accepted original
        // hash.
        ExecutionEvidenceContract.hashValue(row.getStockObservationHash());
        var original = json.readValue(admission.getStockManifestJson(), StockDispositionPort.Original.class);
        require(scope.entityNo().equals(original.entityNo()) && b.orderNo().equals(original.orderNo())
                && original.manifestHash().equals(row.getOriginalStockManifestHash())
                && original.authority().equals(admission.getStockAuthority())
                && original.lifecycleVersion() == admission.getStockLifecycleVersion());
        StockDispositionContract.requireOperation(original, admission.getStockOperationNo());
        var ref = new ExecutionReference("SERVICE_EXECUTION_REFERENCE_V1", scope,
                ExecutionReference.SourceKind.STORE_ORDER, "MANUAL_EXECUTION_V1", executionNo,
                row.getGrantNo(), row.getUnitNo(), b.taskNo(), b.workOrderNo(), b.attemptNo(),
                    action.getResultRevision(),
                row.getBindingHash(), b.payloadHash(), ExecutionEvidenceContract.bindingBytesHash(row.getBindingJson()),
                unit.getSourceIdentityHash(), row.getSourceIntentHash(), b.sourceFenceNo(), b.sourceFenceRevision(),
                b.planHash(), row.getResourceCommandNo(), row.getResourceCommandHash(), action.getActionNo(),
                action.getActionHash(), action.getRequestKeyHash(), null).signed();
        return new EvidenceSnapshot(b, row.getBindingJson(), ref,
                new PrepareResult(action.getActionNo(), action.getActionHash(), action.getRequestKeyHash(),
                        action.getResultState(), action.getResultRevision()),
                row.getVersion(), row.getObservationEpoch(), GrantState.valueOf(row.getState()), row.getReasonCode(),
                UnitState.valueOf(unit.getState()), unit.getCurrentExecutionNo(), unit.getAttemptNo(),
                    unit.getVersion(),
                row.getDeclarationNo(), row.getConfirmationNo(), row.getAdmissionRevision(), admission.getVersion(),
                op.getVersion(), original, admission.getStockOperationNo(), row.getStockObservationHash());
    }

    private record EvidenceSnapshot(ExecutionBinding binding, String bindingJson, ExecutionReference reference,
            PrepareResult prepare, long version, long epoch, GrantState state, String reason, UnitState unitState,
            String currentExecutionNo, int unitAttempt, long unitVersion, String declarationNo, String confirmationNo,
            long admissionRevision, long declarationVersion, long operationVersion,
                StockDispositionPort.Original original,
            String stockOperation, String stockObservation) {
    }

    @Override
    public int recoverPending(int limit) {
        DelegatedIdentityContext.outside();
        if (limit < 1 || limit > 100) throw BizException.of(ErrorCode.BAD_REQUEST);
        if (schema.installation() == ServiceExecutionSchemaVerifier.Installation.ABSENT) return 0;
        available();
        var now = LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC);
        var pending = unscoped(() -> executions.pending(now, null, 0, limit));
        int examined = 0;
        for (var row : pending) {
            var b = binding(row);
            var claim = unscoped(() -> writes.execute(tx -> claimRecovery(row, b)));
            if (claim == null) continue;
            StartIntent observed = null;
            try { observed = source().inspectOriginal(new StartIntentLookup(b)); }
            catch (RuntimeException unavailable) { /* Unknown is retried on the same original, never released. */ }
            var result = observed;
            unscoped(() -> writes.execute(tx -> applyRecovery(claim, result)));
            examined++;
        }
        return examined;
    }

    private RecoveryClaim claimRecovery(OrdServiceExecution observed, ExecutionBinding b) {
        var row = lockOriginalExecution(b);
        if (!row.getVersion().equals(observed.getVersion())
                || !row.getObservationEpoch().equals(observed.getObservationEpoch())) return null;
        var now = LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC);
        if (row.getNextRetryAt() != null && row.getNextRetryAt().isAfter(now)) return null;
        if (executions.claimObservation(b.scope(), b.executionNo(), row.getVersion(), row.getObservationEpoch(),
                now, now.plusSeconds(30)) != 1) return null;
        row.setVersion(Math.addExact(row.getVersion(), 1));
        row.setObservationEpoch(Math.addExact(row.getObservationEpoch(), 1));
        var action = new OrdServiceExecutionAction(); stamp(action, "EXECUTION_RECOVERY");
        action.setActionNo(number());
        action.setExecutionNo(b.executionNo());
        action.setEntityNo(b.scope().entityNo());
        action.setStoreNo(b.scope().storeNo());
        action.setKind("OBSERVE");
        action.setRequestKeyHash(IdempotencyScope.requestHash("CORE_EXECUTION_OBSERVE_V1", b.executionNo(),
                Long.toString(row.getObservationEpoch())));
        action.setActionHash(ServiceExecutionContract.actionHash(b, "OBSERVE", action.getActionNo(), row.getVersion(),
                null, new StartIntentLookup(b), null));
        action.setExpectedRevision(row.getVersion());
        action.setObservationEpoch(row.getObservationEpoch());
        one(actions.insertPending(action));
        return new RecoveryClaim(b, row.getVersion(), row.getObservationEpoch(),
                action.getActionNo(), action.getActionHash());
    }

    private boolean applyRecovery(RecoveryClaim claim, StartIntent observed) {
        var b = claim.binding();
        var row = lockOriginalExecution(b);
        if (row.getVersion() != claim.version() || row.getObservationEpoch() != claim.epoch()) return false;
        String reason = "SOURCE_UNKNOWN";
        if (observed != null) {
            require(b.equals(observed.binding())
                    && ServiceExecutionContract.intentHash(observed).equals(row.getSourceIntentHash()));
            if (observed.suspension() != null) {
                requireCommittedSuspension(observed, b);
                reason = observed.suspension().reasonCode();
            } else if ("READY_UNCHANGED".equals(observed.continuity().state())
                    && observed.continuity().manualEpoch() >= b.sourceEpoch()
                    && Objects.equals(observed.continuity().sourceRevision(), b.sourceRevision())
                    && b.sourceHash().equals(observed.continuity().sourceHash())) {
                reason = null;
            }
        }
        // Preserve an already definitive Core source suspension even when Beauty has not observed it yet.
        if (List.of("SUSPEND_REQUESTED", "STARTED_SUSPENDED").contains(row.getState()) && row.getReasonCode() != null) {
            reason = row.getReasonCode();
        }
        one(executions.applyObservation(b.scope(), b.executionNo(), row.getBindingHash(),
                claim.version(), claim.epoch(),
                claim.actionNo(), claim.actionHash(), reason,
                LocalDateTime.ofInstant(clock.now().plusSeconds(30), ZoneOffset.UTC)));
        row.setVersion(Math.addExact(row.getVersion(), 1));
        if (observed != null && observed.suspension() != null) applySourceSuspensionLocked(b, observed.suspension());
        var current = executions.find(b.scope(), b.executionNo());
        one(actions.finalizeResult(b.scope(), b.executionNo(), claim.actionNo(), claim.actionHash(), 0, claim.epoch(),
                current.getState(), Math.addExact(current.getVersion(), 1)));
        return true;
    }

    static void requireCommittedSuspension(StartIntent observed, ExecutionBinding b) {
        require(observed != null && b.equals(observed.binding()) && observed.suspension() != null
                && observed.state() == ServiceExecutionSourcePort.IntentState.SUSPEND_REQUESTED);
        var proof = observed.suspension();
        require(proof.intentRevision() > b.sourceFenceRevision() && observed.intentRevision() >= proof.intentRevision()
                && "SUSPENDED".equals(observed.continuity().state())
                && proof.reasonCode().equals(observed.continuity().reasonCode())
                && observed.continuity().manualEpoch() >= proof.manualEpoch()
                && observed.continuity().observationVersion() >= proof.observationVersion());
        ServiceExecutionContract.suspensionJson(b, proof);
    }

    private boolean applySourceSuspensionLocked(ExecutionBinding b, SourceSuspension proof) {
        requireTransaction();
        var row = lockOriginalExecution(b);
        require(proof.intentRevision() > b.sourceFenceRevision());
        String proofJson = ServiceExecutionContract.suspensionJson(b, proof);
        String key = IdempotencyScope.requestHash("CORE_EXECUTION_SOURCE_SUSPEND_V1",
                proof.fenceNo(), Long.toString(proof.intentRevision()));
        var prior = actions.findKey(b.scope(), b.executionNo(), "SOURCE_SUSPEND", key);
        if (prior != null) {
            require(proofJson.equals(prior.getProofJson()) && proof.proofHash().equals(prior.getProofHash()));
            return false;
        }
        var latest = actions.latestSuspension(b.scope(), b.executionNo());
        if (latest != null) {
            var lastProof = json.treeToValue(json.readTree(latest.getProofJson()).get("suspension"),
                    SourceSuspension.class);
            if (lastProof.intentRevision() > proof.intentRevision()) return false;
            require(lastProof.intentRevision() < proof.intentRevision());
        }
        var action = new OrdServiceExecutionAction(); stamp(action, "EXECUTION_RECOVERY");
        action.setActionNo(number());
        action.setExecutionNo(b.executionNo());
        action.setEntityNo(b.scope().entityNo());
        action.setStoreNo(b.scope().storeNo());
        action.setKind("SOURCE_SUSPEND");
        action.setRequestKeyHash(key);
        action.setExpectedRevision(row.getVersion());
        action.setObservationEpoch(row.getObservationEpoch());
        action.setProofJson(proofJson);
        action.setProofHash(proof.proofHash());
        action.setActionHash(ServiceExecutionContract.actionHash(b, "SOURCE_SUSPEND", action.getActionNo(),
                row.getVersion(), null, new StartIntentLookup(b), proof));
        one(actions.insertPending(action));
        if (List.of("PREPARED", "STARTED").contains(row.getState())) {
            one(executions.requestSuspend(b, row.getBindingHash(), row.getVersion(), proof.reasonCode(),
                    LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC)));
        }
        var current = executions.find(b.scope(), b.executionNo());
        one(actions.finalizeResult(b.scope(), b.executionNo(), action.getActionNo(), action.getActionHash(), 0,
                row.getObservationEpoch(), current.getState(), Math.addExact(current.getVersion(), 1)));
        return true;
    }

    private OrdServiceExecution lockOriginalExecution(ExecutionBinding b) {
        requireTransaction();
        var parent = admissions.lockParent(b.orderNo());
        var children = admissions.lockChildren(b.orderNo());
        require(parent != null && children.stream().anyMatch(c -> b.subOrderNo().equals(c.getSubOrderNo())));
        require(executions.lockLine(b) != null);
        units.lockLineUnits(b.scope(), b.sourceLineNo());
        executions.lockSourceExecutions(b.scope(), b.sourceLineNo());
        requireCoordinates(operations.lock("BEAUTY", b.sourceOperationNo()), b);
        require(admissions.lockByOrder(b.scope().entityNo(), b.scope().storeNo(), b.orderNo()) != null);
        var row = executions.lockExecution(b.scope(), b.executionNo());
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        requireOriginal(row, b);
        return row;
    }

    private ExecutionBinding binding(OrdServiceExecution row) {
        var b = json.treeToValue(json.readTree(row.getBindingJson()).get("binding"), ExecutionBinding.class);
        requireOriginal(row, b);
        return b;
    }

    private record RecoveryClaim(ExecutionBinding binding, long version, long epoch,
            String actionNo, String actionHash) {}

    @Override
    public void lockSourceLocked(ExecutionCoordinates coordinates) {
        requireTransaction();
        var installation = schema.installation();
        if (installation == ServiceExecutionSchemaVerifier.Installation.ABSENT) return;
        available();
        lockSourceExecutions(coordinates);
    }

    @Override
    public int requestSuspendLocked(ExecutionCoordinates coordinates, String reason) {
        requireTransaction();
        require(List.of("SOURCE_SUSPENDED", "SOURCE_REVISION_CONFLICT").contains(reason));
        var installation = schema.installation();
        if (installation == ServiceExecutionSchemaVerifier.Installation.ABSENT) return 0;
        available();
        int count = 0;
        for (var row : lockSourceExecutions(coordinates)) {
            if (!List.of("PREPARED", "STARTED").contains(row.getState())) continue;
            var b = json.treeToValue(json.readTree(row.getBindingJson()).get("binding"), ExecutionBinding.class);
            requireOriginal(row, b);
            require(b.scope().equals(coordinates.scope())
                    && b.sourceOperationNo().equals(coordinates.sourceOperationNo())
                    && b.orderNo().equals(coordinates.orderNo()) && b.subOrderNo().equals(coordinates.subOrderNo()));
            one(executions.requestSuspend(b, row.getBindingHash(), row.getVersion(), reason,
                    LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC)));
            count++;
        }
        // The calling admission writer appends its real ADMISSION_CHANGED action in the same transaction.
        // This is a Core mutation, never a fabricated Beauty SourceSuspension receipt.
        return count;
    }

    private List<OrdServiceExecution> lockSourceExecutions(ExecutionCoordinates coordinates) {
        var lines = admissions.lockExecutionSourceLines(coordinates);
        for (var line : lines) units.lockLineUnits(coordinates.scope(), line.getSourceLineNo());
        var result = new ArrayList<OrdServiceExecution>();
        for (var line : lines) {
            result.addAll(executions.lockSourceExecutions(coordinates.scope(), line.getSourceLineNo()));
        }
        return result;
    }

    static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("execution source mutation requires the owner's primary transaction");
        }
    }

    private Grant grant(OrdServiceExecution row, ExecutionBinding b) {
        var quantity = units.quantity(b.scope(), b.sourceLineNo());
        require(quantity != null);
        return new Grant(b, row.getGrantNo(), Math.addExact(row.getVersion(), 1), GrantState.valueOf(row.getState()),
                row.getObservationEpoch(), quantity, row.getReasonCode());
    }

    static void requireLiveIntent(StartIntent intent, ExecutionBinding b) {
        require(intent != null && b.equals(intent.binding()) && intent.suspension() == null
                && (intent.state() == ServiceExecutionSourcePort.IntentState.FROZEN
                        || intent.state() == ServiceExecutionSourcePort.IntentState.EXECUTION_PENDING)
                && intent.intentRevision() == b.sourceFenceRevision()
                && "READY_UNCHANGED".equals(intent.continuity().state())
                && intent.continuity().manualEpoch() >= b.sourceEpoch()
                && Objects.equals(intent.continuity().sourceRevision(), b.sourceRevision())
                && b.sourceHash().equals(intent.continuity().sourceHash()));
    }

    private void requireIntent(StartIntent intent, ExecutionBinding b, Actor actor) {
        requireLiveIntent(intent, b);
        require(intent != null && b.equals(intent.binding()) && intent.suspension() == null
                && "READY_UNCHANGED".equals(intent.continuity().state())
                && actor.namespaceHash().equals(intent.operatorNamespaceHash())
                && actor.personnel().equals(intent.personnel())
                && b.payloadHash().equals(ServiceExecutionContract.payloadHash(b, intent.personnel(),
                        intent.operatorNamespaceHash(), intent.resourceCommandNo(), intent.resourceCommandHash())));
    }

    private Actor current(ExecutionScope scope) {
        var identity = DelegatedIdentityContext.current();
        if (!"MAIN".equals(identity.tenantNo()) || !"MERCHANT".equals(identity.realm())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var owner = personnel.getIfAvailable();
        if (owner == null) throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
        var observation = owner.current(new PersonnelOperatorFactsPort.Query(new MerchantOperatorAccessPort.Query(
                scope.tenantNo(), scope.entityNo(), scope.storeNo(), identity.actorNo(), identity.realm(),
                identity.subjectKind(), "biz:service:task:self")));
        if (observation == null || observation.state() != PersonnelOperatorFactsPort.LinkState.LINKED
                || observation.personnel() == null || clock.now().isBefore(observation.personnel().validFrom())
                || observation.personnel().validTo() != null
                        && !clock.now().isBefore(observation.personnel().validTo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return new Actor(identity, observation.personnel(), IdempotencyScope.requestHash("BEAUTY_EXECUTION_ACTOR_V1",
                identity.tenantNo(), scope.entityNo(), scope.storeNo(), identity.realm(), identity.subjectKind(),
                identity.actorNo()));
    }

    private void requireSameActor(ExecutionScope scope, Actor expected) {
        if (!current(scope).equals(expected)) throw BizException.of(ErrorCode.FORBIDDEN);
    }

    static void requireStock(StockDispositionPort.Original original, String operation,
            StockDispositionPort.Disposition proof) {
        if (proof == null || proof.outcome() == StockDispositionPort.Outcome.PENDING
                || proof.outcome() == StockDispositionPort.Outcome.UNKNOWN) {
            throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE, "SOURCE_UNKNOWN");
        }
        require(proof != null && proof.outcome() == StockDispositionPort.Outcome.APPLIED
                && original.equals(proof.original()) && operation.equals(proof.operationNo())
                && proof.observationHash() != null
                && List.of(StockDispositionPort.CurrentState.ALLOCATED, StockDispositionPort.CurrentState.CONSUMED)
                        .contains(proof.state()) && !proof.lines().isEmpty());
        var rows = new HashSet<String>();
        var needs = proof.lines().stream().map(l -> {
            require(rows.add(l.ownerRowNo()) && l.originalQuantity() > 0 && l.revision() >= 0
                    && l.releasedQuantity() == 0 && l.disposition() == proof.state()
                    && (proof.state() == StockDispositionPort.CurrentState.ALLOCATED
                            ? l.allocatedQuantity() == l.originalQuantity() && l.consumedQuantity() == 0
                            : l.consumedQuantity() == l.originalQuantity() && l.allocatedQuantity() == 0));
            return new StockPort.SkuQty(l.skuNo(), l.originalQuantity(), l.sourceStoreNo(), l.fulfillmentRef(),
                    original.lifecycleVersion());
        }).toList();
        String manifest = StockDispositionContract.manifestHash(StockDispositionContract.needs(needs));
        require(original.manifestHash().equals(manifest));
    }

    private static void requireCoordinates(OrdCheckoutOperation op, ExecutionBinding b) {
        require(op != null && "MAIN".equals(op.getTenantNo()) && Integer.valueOf(0).equals(op.getDeleted())
                && b.scope().entityNo().equals(op.getEntityNo()) && b.scope().storeNo().equals(op.getStoreNo())
                && b.orderNo().equals(op.getOrderNo()) && b.subOrderNo().equals(op.getSubOrderNo())
                && "BEAUTY".equals(op.getCaller()) && "MANUAL_OFFLINE".equals(op.getAdmissionKind()));
    }

    private static void requireOriginal(OrdServiceExecution row, ExecutionBinding b) {
        require(ServiceExecutionContract.bindingHash(b).equals(row.getBindingHash())
                && ServiceExecutionContract.bindingJson(b).equals(row.getBindingJson()));
    }

    private ServiceExecutionSourcePort source() {
        var port = sources.getIfAvailable();
        if (port == null) throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
        return port;
    }

    private void available() {
        if (schema.installation() != ServiceExecutionSchemaVerifier.Installation.INSTALLED) {
            throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE, "EXECUTION_SCHEMA_UNAVAILABLE");
        }
    }

    private void stamp(BaseEntity row, String actor) {
        row.setTenantNo("MAIN");
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
        row.setCreatedAt(LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC));
        row.setUpdatedAt(row.getCreatedAt());
        row.setVersion(0L);
        row.setDeleted(0);
    }

    private static String number() { return UUID.randomUUID().toString().replace("-", ""); }
    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }
    private static void require(boolean condition) { if (!condition) throw conflict(); }
    private static void one(int count) { require(count == 1); }
    private static <T> T unscoped(Supplier<T> work) { return DataScopeContext.executeWithoutScope(work); }
    private record Actor(DelegatedIdentity identity, PersonnelOperatorFactsPort.OperatorPersonnelFact personnel,
            String namespaceHash) {}
}
