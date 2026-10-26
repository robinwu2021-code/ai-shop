package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.dto.AppointmentItemReq;
import ai.neargo.shop.beauty.dto.RecoverSubmissionReq;
import ai.neargo.shop.beauty.dto.RecoveryRequestVO;
import ai.neargo.shop.beauty.dto.ServiceSubmissionVO;
import ai.neargo.shop.beauty.dto.SourceObservationVO;
import ai.neargo.shop.beauty.dto.SubmissionTaskVO;
import ai.neargo.shop.beauty.dto.SubmitServicesReq;
import ai.neargo.shop.beauty.entity.BtyActionLog;
import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.beauty.entity.BtyServiceTask;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.ReceptionIdentity;
import ai.neargo.shop.beauty.policy.ServiceSubmissionAccess;
import ai.neargo.shop.beauty.service.ServiceSubmissionService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCheckoutQuote;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualSourceFacts;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.PrepareCounterReq;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.annotation.JsonInclude;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Persists explicit source intent before shared owner IO; merchant calls never confirm, place or
 * pay.
 */
public final class ServiceSubmissionServiceImpl implements ServiceSubmissionService {
    private final AppointmentPersistence db;
    private final ServiceSubmissionAccess access;
    private final ServiceSubmissionQuery query;
    private final BeautyMappers.WorkOrderMapper orders;
    private final BeautyMappers.ServiceTaskMapper tasks;
    private final BeautyMappers.TradeBindingMapper bindings;
    private final BeautyMappers.ServiceSubmissionMapper submissions;
    private final ObjectProvider<CheckoutPort> checkout;
    private final ObjectProvider<ManualServiceAdmissionPort> manual;
    private final Clock clock;
    private final ServiceSourceObservation observations;
    private final ServiceSourceBinding bindingService;
    private final WorkOrderPreparation preparation;

    public ServiceSubmissionServiceImpl(
            AppointmentPersistence db,
            ServiceSubmissionAccess access,
            ServiceSubmissionQuery query,
            BeautyMappers.WorkOrderMapper orders,
            BeautyMappers.ServiceTaskMapper tasks,
            BeautyMappers.ServiceSubmissionMapper submissions,
            BeautyMappers.TradeBindingMapper bindings,
            ObjectProvider<CheckoutPort> checkout,
            Clock clock,
            ServiceSourceObservation observations,
            ServiceSourceBinding bindingService,
            WorkOrderPreparation preparation) {
        this.manual = null;
        this.db = db;
        this.access = access;
        this.query = query;
        this.orders = orders;
        this.tasks = tasks;
        this.bindings = bindings;
        this.submissions = submissions;
        this.checkout = checkout;
        this.clock = clock;
        this.observations = observations;
        this.bindingService = bindingService;
        this.preparation = preparation;
    }

    public ServiceSubmissionServiceImpl(
            AppointmentPersistence db,
            ServiceSubmissionAccess access,
            ServiceSubmissionQuery query,
            BeautyMappers.WorkOrderMapper orders,
            BeautyMappers.ServiceTaskMapper tasks,
            BeautyMappers.ServiceSubmissionMapper submissions,
            BeautyMappers.TradeBindingMapper bindings,
            ObjectProvider<CheckoutPort> checkout,
            Clock clock,
            ServiceSourceObservation observations,
            ServiceSourceBinding bindingService,
            WorkOrderPreparation preparation,
            ObjectProvider<ManualServiceAdmissionPort> manual) {
        this.manual = manual;
        this.db = db;
        this.access = access;
        this.query = query;
        this.orders = orders;
        this.tasks = tasks;
        this.bindings = bindings;
        this.submissions = submissions;
        this.checkout = checkout;
        this.clock = clock;
        this.observations = observations;
        this.bindingService = bindingService;
        this.preparation = preparation;
    }

    /** Safe immutable task selection contains no quantity/price authored by an employee. */
    public record FrozenTask(
            String taskNo,
            String selectionRef,
            String skuNo,
            String goodsNo,
            String definitionVersionNo,
            BigDecimal qty,
            String uom,
            long revision,
            long bindingRevision,
            String executionHash,
            String clientLineRef,
            String sourceLineNo) {}

    /**
     * Permanent original namespace/selection; the short customer proof is only represented by its
     * digest.
     */
    public record Command(
            int schemaVersion,
            String realm,
            String subjectKind,
            String actorNo,
            String endpoint,
            String namespaceHash,
            String keyHash,
            String originalDraftNo,
            long originalScopeRevision,
            String originalScopeHash,
            String identityKind,
            String identityRef,
            String buyerNo,
            String claimReceiptHash,
            Long claimRevision,
            long targetScopeRevision,
            long acceptedRootRevision,
            String operationNo,
            String prepareKey,
            String sourceMode,
            String customerRefHash,
            String payMode,
            String couponNo,
            Map<String, String> activityChoices,
            String remark,
            String expectedSourceNo,
            Long expectedSourceRevision,
            String expectedSourceHash,
            List<FrozenTask> tasks,
            List<CheckoutPort.CheckoutLineReq> lines,
            @JsonInclude(JsonInclude.Include.NON_NULL) String admissionKind,
            @JsonInclude(JsonInclude.Include.NON_NULL) CustomerParty party,
            @JsonInclude(JsonInclude.Include.NON_NULL) String counterQuoteAuthority) {
        /**
         * V2 fields and serialization order remain byte-for-byte unchanged when new fields are
         * null.
         */
        public Command(
                int schemaVersion,
                String realm,
                String subjectKind,
                String actorNo,
                String endpoint,
                String namespaceHash,
                String keyHash,
                String originalDraftNo,
                long originalScopeRevision,
                String originalScopeHash,
                String identityKind,
                String identityRef,
                String buyerNo,
                String claimReceiptHash,
                Long claimRevision,
                long targetScopeRevision,
                long acceptedRootRevision,
                String operationNo,
                String prepareKey,
                String sourceMode,
                String customerRefHash,
                String payMode,
                String couponNo,
                Map<String, String> activityChoices,
                String remark,
                String expectedSourceNo,
                Long expectedSourceRevision,
                String expectedSourceHash,
                List<FrozenTask> tasks,
                List<CheckoutPort.CheckoutLineReq> lines) {
            this(
                    schemaVersion,
                    realm,
                    subjectKind,
                    actorNo,
                    endpoint,
                    namespaceHash,
                    keyHash,
                    originalDraftNo,
                    originalScopeRevision,
                    originalScopeHash,
                    identityKind,
                    identityRef,
                    buyerNo,
                    claimReceiptHash,
                    claimRevision,
                    targetScopeRevision,
                    acceptedRootRevision,
                    operationNo,
                    prepareKey,
                    sourceMode,
                    customerRefHash,
                    payMode,
                    couponNo,
                    activityChoices,
                    remark,
                    expectedSourceNo,
                    expectedSourceRevision,
                    expectedSourceHash,
                    tasks,
                    lines,
                    null,
                    null,
                    null);
        }

        public Command {
            tasks = List.copyOf(tasks);
            lines = List.copyOf(lines);
            activityChoices = Map.copyOf(activityChoices);
        }
    }

    @Override
    public ServiceSubmissionVO submitForWorkOrder(
            String entity,
            String store,
            String actor,
            String workOrder,
            SubmitServicesReq req,
            String key) {
        if (req == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var user = access.authorize(entity, store, actor, true, preparing(req.source().mode()));
        var root = query.root(entity, store, workOrder);
        String action = req.additions().isEmpty() ? "SUBMIT_SOURCE" : "ADD_SOURCE";
        String keyHash = IdempotencyScope.requestHash(IdempotencyScope.requireKey(key));
        String namespace =
                IdempotencyScope.requestHash(
                        manual(req.source().mode())
                                ? "BEAUTY_MANUAL_SUBMISSION_V1"
                                : "BEAUTY_SUBMISSION_V1",
                        "MAIN",
                        entity,
                        store,
                        user.realm().name(),
                        user.subjectKind().name(),
                        actor,
                        "BAPI-056",
                        keyHash);
        String payloadHash =
                IdempotencyScope.requestHash(workOrder, db.json.writeValueAsString(req));
        var previous = acceptedSubmission(entity, store, namespace, payloadHash, workOrder);
        if (previous != null) {
            AppointmentPersistence.require(payloadHash.equals(previous.getPayloadHash()));
            return resume(previous, command(previous), actor, req);
        }
        ManualSourceFacts sourceFacts = null;
        if ("MANUAL_EXISTING".equals(req.source().mode())) {
            sourceFacts =
                    manualOwner()
                            .source(
                                    scope(entity, store, actor),
                                    new CheckoutPort.SourceLookup(
                                            new CheckoutPort.OperationRef(
                                                    "BEAUTY", req.source().checkoutOperationNo()),
                                            req.source().subOrderNo()));
            AppointmentPersistence.require(
                    sourceFacts != null
                            && req.source()
                                    .checkoutOperationNo()
                                    .equals(sourceFacts.operation().operationNo())
                            && req.source().subOrderNo().equals(sourceFacts.subOrderNo())
                            && req.source().sourceRevision()
                                    == sourceFacts.manualObservationRevision()
                            && req.source()
                                    .sourceHash()
                                    .equals(sourceFacts.manualObservationHash()));
        }
        final CustomerParty selectedParty =
                !manual(req.source().mode())
                        ? null
                        : sourceFacts == null
                                ? access.manualParty(root.context())
                                : access.manualParty(root.context(), sourceFacts);
        final String purchaseAuthority =
                sourceFacts == null
                        ? "MERCHANT_RECORDED_IN_PERSON"
                        : sourceFacts.purchaseAuthority();
        var buyer =
                selectedParty == null
                        ? access.buyer(root.context(), req.source().customerRef())
                        : access.manualBuyer(root.context(), selectedParty);
        var preparedScope =
                req.additions().isEmpty()
                        ? null
                        : preparation.unassigned(
                                entity,
                                store,
                                new ReceptionIdentity.VerifiedIdentity(
                                        root.context().getIdentityKind(),
                                        root.context().getIdentityRef()),
                                req.additions().stream()
                                        .map(
                                                item ->
                                                        new AppointmentItemReq(
                                                                item.skuNo(), item.quantity()))
                                        .toList());
        if (selectedParty != null) {
            var currentActor =
                    access.authorize(entity, store, actor, true, preparing(req.source().mode()));
            AppointmentPersistence.require(
                    user.tenantNo().equals(currentActor.tenantNo())
                            && user.realm().equals(currentActor.realm())
                            && user.subjectKind().equals(currentActor.subjectKind())
                            && user.userNo().equals(currentActor.userNo()));
        }
        BtyServiceSubmission accepted;
        try {
            accepted =
                    db.tx(
                            () -> {
                                if (preparedScope != null) {
                                    preparation.verifyLocked(preparedScope);
                                }
                                var locked = orders.selectScopedForUpdate(entity, store, workOrder);
                                var concurrent =
                                        acceptedSubmission(
                                                entity, store, namespace, payloadHash, workOrder);
                                if (concurrent != null) {
                                    return concurrent;
                                }
                                AppointmentPersistence.require(
                                        locked != null
                                                && locked.getVersion() == req.expectedRevision()
                                                && locked.getScopeRevision()
                                                        == req.expectedScopeRevision()
                                                && !locked.getScopeFrozen()
                                                && List.of("OPEN", "IN_SERVICE")
                                                        .contains(locked.getStatus()));
                                var current = query.root(entity, store, workOrder);
                                for (var active :
                                        submissions.selectActiveForWorkOrder(
                                                entity, store, workOrder)) {
                                    var old = command(active);
                                    boolean overlap = overlaps(old, req);
                                    AppointmentPersistence.require(
                                            !overlap
                                                    || existing(req.source().mode())
                                                            && old.operationNo()
                                                                    .equals(
                                                                            req.source()
                                                                                    .checkoutOperationNo()));
                                }
                                String number = BizKey.next("BTSU");
                                var selected = new ArrayList<BtyServiceTask>();
                                var selectionRefs = new TreeMap<String, String>();
                                for (var selection :
                                        req.tasks().stream()
                                                .sorted(
                                                        Comparator.comparing(
                                                                SubmitServicesReq.TaskSelection
                                                                        ::taskNo))
                                                .toList()) {
                                    var task =
                                            tasks.selectScopedForUpdate(
                                                    entity, store, selection.taskNo());
                                    AppointmentPersistence.require(
                                            task != null
                                                    && workOrder.equals(task.getWorkOrderNo())
                                                    && current.tasks().stream()
                                                            .anyMatch(
                                                                    t ->
                                                                            t.getTaskNo()
                                                                                    .equals(
                                                                                            task
                                                                                                    .getTaskNo()))
                                                    && task.getVersion()
                                                            == selection.expectedRevision()
                                                    && task.getBindingRevision()
                                                            == selection.expectedBindingRevision());
                                    firstBinding(task);
                                    selected.add(task);
                                    selectionRefs.put(task.getTaskNo(), task.getTaskNo());
                                }
                                appendScope(
                                        entity,
                                        store,
                                        actor,
                                        workOrder,
                                        req,
                                        preparedScope,
                                        selected,
                                        selectionRefs);
                                locked = orders.selectScopedForUpdate(entity, store, workOrder);
                                if (existing(req.source().mode())) {
                                    AppointmentPersistence.require(
                                            new HashSet<>(selectionRefs.values())
                                                    .equals(
                                                            req.source().lines().stream()
                                                                    .map(
                                                                            SubmitServicesReq
                                                                                            .ExistingLineSelection
                                                                                    ::selectionRef)
                                                                    .collect(Collectors.toSet())));
                                }
                                var grouped = new TreeMap<String, List<BtyServiceTask>>();
                                selected.forEach(
                                        task ->
                                                grouped.computeIfAbsent(
                                                                task.getSkuNo(),
                                                                ignored -> new ArrayList<>())
                                                        .add(task));
                                var frozen = new ArrayList<FrozenTask>();
                                var lines = new ArrayList<CheckoutPort.CheckoutLineReq>();
                                grouped.forEach(
                                        (sku, rows) -> {
                                            String ref =
                                                    "BTSL"
                                                            + IdempotencyScope.requestHash(
                                                                            number,
                                                                            sku,
                                                                            String.join(
                                                                                    ",",
                                                                                    rows.stream()
                                                                                            .map(
                                                                                                    BtyServiceTask
                                                                                                            ::getTaskNo)
                                                                                            .toList()))
                                                                    .substring(0, 60);
                                            int count = 0;
                                            for (var task : rows) {
                                                count =
                                                        Math.addExact(
                                                                count,
                                                                task.getQty().intValueExact());
                                                var snapshot =
                                                        db.json.readValue(
                                                                task.getExecutionSnapshot(),
                                                                WorkOrderCoordinator
                                                                        .TaskExecutionSnapshot
                                                                        .class);
                                                frozen.add(
                                                        new FrozenTask(
                                                                task.getTaskNo(),
                                                                selectionRefs.get(task.getTaskNo()),
                                                                sku,
                                                                snapshot.taskSnapshot().goodsNo(),
                                                                task.getDefinitionVersionNo(),
                                                                task.getQty(),
                                                                task.getUom(),
                                                                task.getVersion(),
                                                                task.getBindingRevision(),
                                                                IdempotencyScope.requestHash(
                                                                        task
                                                                                .getExecutionSnapshot()),
                                                                ref,
                                                                selectedLine(
                                                                        req,
                                                                        selectionRefs.get(
                                                                                task
                                                                                        .getTaskNo()))));
                                            }
                                            lines.add(
                                                    new CheckoutPort.CheckoutLineReq(
                                                            ref, sku, count));
                                        });
                                var source = req.source();
                                var command =
                                        new Command(
                                                selectedParty == null ? 2 : 3,
                                                user.realm().name(),
                                                user.subjectKind().name(),
                                                actor,
                                                "BAPI-056",
                                                namespace,
                                                keyHash,
                                                current.draft().getSubmissionNo(),
                                                current.original().originalScopeRevision(),
                                                current.original().scopeHash(),
                                                current.context().getIdentityKind(),
                                                current.context().getIdentityRef(),
                                                buyer.userNo(),
                                                buyer.receiptHash(),
                                                buyer.claimRevision(),
                                                locked.getScopeRevision(),
                                                locked.getVersion(),
                                                preparing(source.mode())
                                                        ? number
                                                        : source.checkoutOperationNo(),
                                                IdempotencyScope.requestHash(
                                                        namespace, "PREPARE", number),
                                                source.mode(),
                                                source.counterCustomer() != null
                                                        ? IdempotencyScope.requestHash(
                                                                db.json.writeValueAsString(
                                                                        source.counterCustomer()))
                                                        : source.customerRef() == null
                                                                ? null
                                                                : IdempotencyScope.requestHash(
                                                                        source.customerRef()),
                                                source.payMode(),
                                                source.couponNo(),
                                                source.activityChoices(),
                                                source.remark(),
                                                source.subOrderNo(),
                                                source.sourceRevision(),
                                                source.sourceHash(),
                                                frozen,
                                                lines,
                                                selectedParty == null ? null : "MANUAL_OFFLINE",
                                                selectedParty,
                                                selectedParty == null ? null : purchaseAuthority);
                                var row = new BtyServiceSubmission();
                                row.setEntityNo(entity);
                                row.setStoreNo(store);
                                row.setWorkOrderNo(workOrder);
                                row.setSubmissionNo(number);
                                row.setAction(action);
                                row.setRequestKeyHash(namespace);
                                row.setPayloadHash(payloadHash);
                                row.setFrozenCommand(db.json.writeValueAsString(command));
                                row.setStatus("RECEIVED");
                                row.setNextRetryAt(now().plusSeconds(30));
                                submissions.insert(row);
                                var receipt = new BtyActionLog();
                                receipt.setEntityNo(entity);
                                receipt.setStoreNo(store);
                                receipt.setActionNo("BTSK" + namespace.substring(0, 28));
                                receipt.setAggregateType("BEAUTY_SOURCE_SUBMISSION");
                                receipt.setAggregateNo(number);
                                receipt.setAction("SOURCE_SUBMITTED");
                                receipt.setOperatorRef(actor);
                                receipt.setRequestRef(namespace);
                                receipt.setExpectedRevision(req.expectedRevision());
                                receipt.setResultRevision(locked.getVersion());
                                receipt.setSafeSnapshot(
                                        db.json.writeValueAsString(
                                                new SubmissionAcceptance(
                                                        1,
                                                        namespace,
                                                        payloadHash,
                                                        workOrder,
                                                        number)));
                                db.actions.insert(receipt);
                                return row;
                            });
        } catch (DuplicateKeyException collision) {
            // REQUIRES_NEW has rolled back before reading the winning permanent endpoint receipt.
            accepted = acceptedSubmission(entity, store, namespace, payloadHash, workOrder);
            if (accepted == null) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
        }
        return resume(accepted, command(accepted), actor, req);
    }

    private record SubmissionAcceptance(
            int schemaVersion,
            String namespace,
            String payload,
            String workOrderNo,
            String submissionNo) {}

    private BtyServiceSubmission acceptedSubmission(
            String entity, String store, String namespace, String payload, String workOrder) {
        var receipt = recoveryReceipt(entity, store, "BTSK" + namespace.substring(0, 28));
        if (receipt == null) {
            return null;
        }
        var saved = db.json.readValue(receipt.getSafeSnapshot(), SubmissionAcceptance.class);
        AppointmentPersistence.require(
                "BEAUTY_SOURCE_SUBMISSION".equals(receipt.getAggregateType())
                        && "SOURCE_SUBMITTED".equals(receipt.getAction())
                        && namespace.equals(receipt.getRequestRef())
                        && saved.schemaVersion() == 1
                        && namespace.equals(saved.namespace())
                        && payload.equals(saved.payload())
                        && workOrder.equals(saved.workOrderNo())
                        && receipt.getAggregateNo().equals(saved.submissionNo()));
        var row = query.submission(entity, store, saved.submissionNo());
        AppointmentPersistence.require(
                namespace.equals(row.getRequestKeyHash())
                        && payload.equals(row.getPayloadHash())
                        && workOrder.equals(row.getWorkOrderNo()));
        return row;
    }

    private boolean overlaps(Command original, SubmitServicesReq request) {
        var selected =
                request.tasks().stream().map(SubmitServicesReq.TaskSelection::taskNo).toList();
        return original.tasks().stream().anyMatch(task -> selected.contains(task.taskNo()));
    }

    private String selectedLine(SubmitServicesReq request, String selectionRef) {
        if (!existing(request.source().mode())) {
            return null;
        }
        return request.source().lines().stream()
                .filter(line -> line.selectionRef().equals(selectionRef))
                .map(SubmitServicesReq.ExistingLineSelection::sourceLineNo)
                .findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.BAD_REQUEST));
    }

    private void appendScope(
            String entity,
            String store,
            String actor,
            String workOrder,
            SubmitServicesReq req,
            PreparedWorkOrderScope prepared,
            List<BtyServiceTask> selected,
            Map<String, String> selectionRefs) {
        if (prepared == null) {
            return;
        }
        var root = orders.selectScopedForUpdate(entity, store, workOrder);
        AppointmentPersistence.require(
                orders.advanceScope(
                                entity,
                                store,
                                workOrder,
                                root.getVersion(),
                                root.getScopeRevision(),
                                actor,
                                now())
                        == 1);
        long targetScope = Math.addExact(root.getScopeRevision(), 1);
        int index = 0;
        for (var added : req.additions()) {
            for (int unit = 0; unit < added.quantity(); unit++) {
                var item = prepared.frozen().items().get(index);
                var definition = prepared.frozen().definitions().get(index++);
                AppointmentPersistence.require(
                        added.skuNo().equals(item.skuNo())
                                && added.definitionVersionNo().equals(item.definitionVersionNo()));
                var row = new BtyServiceTask();
                row.setEntityNo(entity);
                row.setTaskNo(BizKey.next("BTST"));
                row.setWorkOrderNo(workOrder);
                row.setScopeRevision(targetScope);
                row.setSkuNo(item.skuNo());
                row.setDefinitionVersionNo(item.definitionVersionNo());
                row.setQty(BigDecimal.ONE);
                row.setDoneQty(BigDecimal.ZERO);
                row.setVoidedQty(BigDecimal.ZERO);
                row.setUom("COUNT");
                row.setStatus("PENDING");
                row.setBindingRevision(0L);
                row.setExecutionSnapshot(
                        db.json.writeValueAsString(
                                new WorkOrderCoordinator.TaskExecutionSnapshot(
                                        1, "UNASSIGNED", definition, List.of(), null, null)));
                tasks.insert(row);
                selected.add(tasks.selectScopedForUpdate(entity, store, row.getTaskNo()));
                selectionRefs.put(row.getTaskNo(), added.clientItemRef());
            }
        }
    }

    @Override
    public ServiceSubmissionVO getSubmission(
            String entity, String store, String actor, String number) {
        access.authorize(entity, store, actor, false, false);
        var row = query.submission(entity, store, number);
        var command = command(row);
        if (command.schemaVersion() == 3) {
            return readManual(row, command, actor);
        }
        verifyBuyer(row, command, null);
        if ("REJECTED".equals(row.getStatus())) {
            return view(row, command, null, null);
        }
        var owner = checkout.getIfAvailable();
        if (owner == null) {
            unavailable(row, command, actor);
            return view(row, command, null, null);
        }
        try {
            var quote = owner.readQuote(scope(entity, store, actor), operation(command));
            verifyQuote(entity, store, command, quote);
            var result = owner.findOperation(scope(entity, store, actor), operation(command));
            verifyResult(command, result);
            var observed =
                    "CREATED".equals(result.phase())
                            ? observations.observe(canonical(row, command, result), actor)
                            : null;
            if (observed == null) {
                unavailable(row, command, actor);
            }
            return view(row, command, quote, observed);
        } catch (UncheckedIOException transport) {
            unavailable(row, command, actor);
            return view(row, command, null, null);
        } catch (BizException failure) {
            if (failure.errorCode() != ErrorCode.NOT_FOUND
                    && failure.errorCode() != ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE) {
                throw failure;
            }
            unavailable(row, command, actor);
            return view(row, command, null, null);
        }
    }

    @Override
    public RecoveryRequestVO requestSubmissionRecovery(
            String entity,
            String store,
            String actor,
            String number,
            RecoverSubmissionReq req,
            String key) {
        var user = access.authorize(entity, store, actor, true, false);
        if (req == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var submission = query.submission(entity, store, number);
        var command = command(submission);
        if (command.schemaVersion() == 3) verifyManualCurrent(submission, command, actor);
        else verifyBuyer(submission, command, null);
        String namespace =
                IdempotencyScope.requestHash(
                        "BEAUTY_RECOVERY_V1",
                        "MAIN",
                        entity,
                        store,
                        user.realm().name(),
                        user.subjectKind().name(),
                        actor,
                        "BAPI-073",
                        IdempotencyScope.requestHash(IdempotencyScope.requireKey(key)));
        String payload = IdempotencyScope.requestHash(number, db.json.writeValueAsString(req));
        String recoveryNo = "BTRQ" + namespace.substring(0, 28);
        var existing = recoveryReceipt(entity, store, recoveryNo);
        if (existing != null) {
            return recoveryReplay(existing, namespace, payload, number);
        }
        try {
            return db.tx(
                    () -> {
                        var row = submissions.selectScopedForUpdate(entity, store, number);
                        var concurrent = recoveryReceipt(entity, store, recoveryNo);
                        if (concurrent != null) {
                            return recoveryReplay(concurrent, namespace, payload, number);
                        }
                        AppointmentPersistence.require(
                                row != null && row.getVersion() == req.expectedRevision());
                        var accepted =
                                new RecoveryRequestVO(
                                        recoveryNo,
                                        number,
                                        number,
                                        row.getVersion(),
                                        "ACCEPTED",
                                        "AUTHENTICATED_ORIGINAL_RECOVERY");
                        var action = new BtyActionLog();
                        action.setEntityNo(entity);
                        action.setStoreNo(store);
                        action.setActionNo(recoveryNo);
                        action.setAggregateType("BEAUTY_SOURCE_SUBMISSION");
                        action.setAggregateNo(number);
                        action.setAction("RECOVERY_REQUESTED");
                        action.setOperatorRef(actor);
                        action.setRequestRef(namespace);
                        action.setExpectedRevision(row.getVersion());
                        action.setResultRevision(row.getVersion());
                        action.setReason(req.reason());
                        action.setSafeSnapshot(
                                db.json.writeValueAsString(
                                        new RecoveryAcceptance(1, namespace, payload, accepted)));
                        db.actions.insert(action);
                        return accepted;
                    });
        } catch (DuplicateKeyException collision) {
            var receipt = recoveryReceipt(entity, store, recoveryNo);
            AppointmentPersistence.require(receipt != null);
            return recoveryReplay(receipt, namespace, payload, number);
        }
    }

    private record RecoveryAcceptance(
            int schemaVersion, String namespace, String payload, RecoveryRequestVO receipt) {}

    private BtyActionLog recoveryReceipt(String entity, String store, String number) {
        return db.read(
                () ->
                        db.actions.selectOne(
                                Wrappers.<BtyActionLog>lambdaQuery()
                                        .eq(BtyActionLog::getTenantNo, "MAIN")
                                        .eq(BtyActionLog::getEntityNo, entity)
                                        .eq(BtyActionLog::getStoreNo, store)
                                        .eq(BtyActionLog::getActionNo, number)));
    }

    private RecoveryRequestVO recoveryReplay(
            BtyActionLog row, String namespace, String payload, String submission) {
        var saved = db.json.readValue(row.getSafeSnapshot(), RecoveryAcceptance.class);
        AppointmentPersistence.require(
                "BEAUTY_SOURCE_SUBMISSION".equals(row.getAggregateType())
                        && "RECOVERY_REQUESTED".equals(row.getAction())
                        && submission.equals(row.getAggregateNo())
                        && namespace.equals(row.getRequestRef())
                        && saved.schemaVersion() == 1
                        && namespace.equals(saved.namespace())
                        && payload.equals(saved.payload())
                        && row.getActionNo().equals(saved.receipt().recoveryNo())
                        && submission.equals(saved.receipt().submissionNo()));
        return saved.receipt();
    }

    @Override
    public ServiceSubmissionVO recoverPending(
            String entity, String store, String actor, String number) {
        access.authorize(entity, store, actor, true, false);
        var row = query.submission(entity, store, number);
        return resume(row, command(row), actor, null);
    }

    private ServiceSubmissionVO resume(
            BtyServiceSubmission intent,
            Command command,
            String actor,
            SubmitServicesReq originalRequest) {
        if (command.schemaVersion() == 3)
            return resumeManual(intent, command, actor, originalRequest);
        verifyBuyer(
                intent,
                command,
                originalRequest == null ? null : originalRequest.source().customerRef());
        if (List.of("BOUND", "REJECTED").contains(intent.getStatus())) {
            return getSubmission(
                    intent.getEntityNo(), intent.getStoreNo(), actor, intent.getSubmissionNo());
        }
        var leased =
                db.tx(
                        () -> {
                            var row =
                                    submissions.selectScopedForUpdate(
                                            intent.getEntityNo(),
                                            intent.getStoreNo(),
                                            intent.getSubmissionNo());
                            if (submissions.claimSourceRecovery(
                                            row.getEntityNo(),
                                            row.getStoreNo(),
                                            row.getSubmissionNo(),
                                            row.getVersion(),
                                            now(),
                                            now().plusSeconds(30),
                                            actor)
                                    != 1) {
                                return null;
                            }
                            return submissions.selectScopedForUpdate(
                                    row.getEntityNo(), row.getStoreNo(), row.getSubmissionNo());
                        });
        if (leased == null) {
            return getSubmission(
                    intent.getEntityNo(), intent.getStoreNo(), actor, intent.getSubmissionNo());
        }
        var owner = checkout.getIfAvailable();
        if (owner == null) {
            unavailable(intent, command, actor);
            return view(pending(leased, actor, "SOURCE_UNAVAILABLE"), command, null, null);
        }
        CheckoutPort.CheckoutQuote quote = null;
        try {
            try {
                quote =
                        owner.readQuote(
                                scope(intent.getEntityNo(), intent.getStoreNo(), actor),
                                operation(command));
            } catch (BizException absent) {
                if (absent.errorCode() != ErrorCode.NOT_FOUND) {
                    throw absent;
                }
                if (observations.invalidateKnown(
                                intent.getEntityNo(),
                                intent.getStoreNo(),
                                command.buyerNo(),
                                command.operationNo(),
                                actor)
                        != null) {
                    return view(pending(leased, actor, "SOURCE_UNKNOWN"), command, null, null);
                }
                if (!"PREPARE".equals(command.sourceMode())
                        || originalRequest == null
                        || !originalActor(command, actor)) {
                    return view(
                            pending(leased, actor, "REQUIRE_ORIGINAL_PREPARE_REQUEST"),
                            command,
                            null,
                            null);
                }
                AppointmentPersistence.require(
                        command.customerRefHash()
                                .equals(
                                        IdempotencyScope.requestHash(
                                                originalRequest.source().customerRef())));
                quote =
                        owner.prepare(
                                scope(intent.getEntityNo(), intent.getStoreNo(), actor),
                                new CheckoutPort.PrepareCheckoutReq(
                                        operation(command),
                                        command.prepareKey(),
                                        originalRequest.source().customerRef(),
                                        command.targetScopeRevision(),
                                        command.originalScopeHash(),
                                        command.lines(),
                                        command.payMode(),
                                        command.couponNo(),
                                        command.activityChoices(),
                                        command.remark()));
            }
            verifyQuote(intent.getEntityNo(), intent.getStoreNo(), command, quote);
            CheckoutPort.CheckoutResult result;
            try {
                result =
                        owner.findOperation(
                                scope(intent.getEntityNo(), intent.getStoreNo(), actor),
                                operation(command));
            } catch (BizException absent) {
                if (absent.errorCode() != ErrorCode.NOT_FOUND) {
                    throw absent;
                }
                unavailable(intent, command, actor);
                return view(pending(leased, actor, "SOURCE_UNKNOWN"), command, quote, null);
            }
            verifyResult(command, result);
            if (List.of("REJECTED", "REQUOTE_REQUIRED").contains(result.phase())) {
                access.authorize(intent.getEntityNo(), intent.getStoreNo(), actor, true, false);
                verifyBuyer(intent, command, null);
                unavailable(intent, command, actor);
                return view(
                        completeLease(leased, actor, "REJECTED", "OWNER_" + result.phase(), null),
                        command,
                        quote,
                        null);
            }
            if (!"CREATED".equals(result.phase())) {
                unavailable(intent, command, actor);
                return view(
                        pending(
                                leased,
                                actor,
                                "PLACING".equals(result.phase())
                                        ? "OWNER_PLACING"
                                        : "AWAITING_CUSTOMER"),
                        command,
                        quote,
                        null);
            }
            var observed = observations.observe(canonical(intent, command, result), actor);
            // Recheck current grants and owner-backed identity after slow external IO and before
            // the local TX.
            access.authorize(intent.getEntityNo(), intent.getStoreNo(), actor, true, false);
            verifyBuyer(intent, command, null);
            var binding = bindingService.bind(leased, command, observed, actor);
            return view(
                    binding.submission() == null
                            ? pending(leased, actor, binding.reason())
                            : binding.submission(),
                    command,
                    quote,
                    observed);
        } catch (UncheckedIOException transport) {
            unavailable(intent, command, actor);
            return view(
                    pending(leased, actor, quote == null ? "PREPARE_UNKNOWN" : "SOURCE_UNKNOWN"),
                    command,
                    quote,
                    null);
        } catch (BizException unavailable) {
            if (unavailable.errorCode() != ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE) {
                throw unavailable;
            }
            unavailable(intent, command, actor);
            return view(
                    pending(leased, actor, quote == null ? "PREPARE_UNKNOWN" : "SOURCE_UNKNOWN"),
                    command,
                    quote,
                    null);
        }
    }

    private void unavailable(BtyServiceSubmission row, Command command, String actor) {
        observations.invalidateKnown(
                row.getEntityNo(),
                row.getStoreNo(),
                command.buyerNo(),
                command.operationNo(),
                actor);
    }

    private BtyServiceSubmission pending(BtyServiceSubmission lease, String actor, String reason) {
        return completeLease(lease, actor, "SOURCE_PENDING", reason, now().plusSeconds(30));
    }

    /** Completes only the current lease; a lost race returns the already committed current row. */
    private BtyServiceSubmission completeLease(
            BtyServiceSubmission lease,
            String actor,
            String status,
            String reason,
            LocalDateTime retryAt) {
        return db.tx(
                () -> {
                    submissions.finishSourceRecovery(
                            lease.getEntityNo(),
                            lease.getStoreNo(),
                            lease.getSubmissionNo(),
                            lease.getVersion(),
                            status,
                            reason,
                            retryAt,
                            now(),
                            actor);
                    return submissions.selectScopedForUpdate(
                            lease.getEntityNo(), lease.getStoreNo(), lease.getSubmissionNo());
                });
    }

    private void verifyBuyer(BtyServiceSubmission row, Command command, String reference) {
        var current =
                access.buyer(
                        query.root(row.getEntityNo(), row.getStoreNo(), row.getWorkOrderNo())
                                .context(),
                        reference);
        AppointmentPersistence.require(
                command.buyerNo().equals(current.userNo())
                        && Objects.equals(command.claimReceiptHash(), current.receiptHash())
                        && Objects.equals(command.claimRevision(), current.claimRevision()));
    }

    private boolean originalActor(Command command, String actor) {
        var current = SecurityUtils.requireUser();
        return actor.equals(command.actorNo())
                && command.realm().equals(current.realm().name())
                && command.subjectKind().equals(current.subjectKind().name());
    }

    private CheckoutPort.OperationRef operation(Command command) {
        return new CheckoutPort.OperationRef("BEAUTY", command.operationNo());
    }

    private void verifyResult(Command command, CheckoutPort.CheckoutResult result) {
        AppointmentPersistence.require(
                result != null && operation(command).equals(result.operation()));
        AppointmentPersistence.require(
                List.of("QUOTED", "CONFIRMED", "PLACING", "CREATED", "REJECTED", "REQUOTE_REQUIRED")
                        .contains(result.phase()));
        if ("CREATED".equals(result.phase())) {
            AppointmentPersistence.require(
                    result.orderNo() != null
                            && result.subOrderNo() != null
                            && (!"EXISTING".equals(command.sourceMode())
                                    || command.expectedSourceNo().equals(result.subOrderNo())));
        }
    }

    private ServiceSourceObservation.Canonical canonical(
            BtyServiceSubmission row, Command command, CheckoutPort.CheckoutResult result) {
        return new ServiceSourceObservation.Canonical(
                row.getEntityNo(),
                row.getStoreNo(),
                command.buyerNo(),
                command.operationNo(),
                result.orderNo(),
                result.subOrderNo());
    }

    Command command(BtyServiceSubmission row) {
        var command = db.json.readValue(row.getFrozenCommand(), Command.class);
        AppointmentPersistence.require(validCommand(command, row));
        return command;
    }

    static boolean existing(String mode) {
        return "EXISTING".equals(mode) || "MANUAL_EXISTING".equals(mode);
    }

    static boolean preparing(String mode) {
        return "PREPARE".equals(mode) || "MANUAL_PREPARE".equals(mode);
    }

    static boolean manual(String mode) {
        return "MANUAL_EXISTING".equals(mode) || "MANUAL_PREPARE".equals(mode);
    }

    /**
     * V3 has an independent namespace and exact typed owner party. Existing V2 validation is
     * preserved.
     */
    public static boolean validCommand(Command c, BtyServiceSubmission row) {
        if (c == null || !Objects.equals(c.namespaceHash(), row.getRequestKeyHash())) return false;
        if (c.schemaVersion() == 2)
            return c.admissionKind() == null
                    && c.party() == null
                    && c.counterQuoteAuthority() == null;
        return c.schemaVersion() == 3
                && manual(c.sourceMode())
                && "MANUAL_OFFLINE".equals(c.admissionKind())
                && c.party() != null
                && Objects.equals(c.buyerNo(), c.party().userNo())
                && ("MERCHANT_RECORDED_IN_PERSON".equals(c.counterQuoteAuthority())
                        || "CONSUMER_SESSION".equals(c.counterQuoteAuthority())
                                && "MANUAL_EXISTING".equals(c.sourceMode())
                                && "USER".equals(c.party().kind()))
                && Objects.equals(
                        c.namespaceHash(),
                        IdempotencyScope.requestHash(
                                "BEAUTY_MANUAL_SUBMISSION_V1",
                                "MAIN",
                                row.getEntityNo(),
                                row.getStoreNo(),
                                c.realm(),
                                c.subjectKind(),
                                c.actorNo(),
                                "BAPI-056",
                                c.keyHash()));
    }

    private ManualServiceAdmissionPort manualOwner() {
        var owner = manual == null ? null : manual.getIfAvailable();
        if (owner == null) throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
        return owner;
    }

    private ServiceSourceObservation.ManualCanonical manualCanonical(
            BtyServiceSubmission row, Command c, ManualSourceFacts facts) {
        AppointmentPersistence.require(
                facts != null
                        && operation(c).equals(facts.operation())
                        && (!"MANUAL_EXISTING".equals(c.sourceMode())
                                || c.expectedSourceNo().equals(facts.subOrderNo())));
        return new ServiceSourceObservation.ManualCanonical(
                row.getEntityNo(),
                row.getStoreNo(),
                c.party(),
                c.operationNo(),
                facts.orderNo(),
                facts.subOrderNo());
    }

    private void verifyManualParty(BtyServiceSubmission row, Command c, ManualSourceFacts facts) {
        var context =
                query.root(row.getEntityNo(), row.getStoreNo(), row.getWorkOrderNo()).context();
        AppointmentPersistence.require(
                c.party().equals(access.manualParty(context, facts))
                        && c.counterQuoteAuthority().equals(facts.purchaseAuthority()));
        var relation = access.manualBuyer(context, facts.party());
        AppointmentPersistence.require(
                Objects.equals(c.claimReceiptHash(), relation.receiptHash())
                        && Objects.equals(c.claimRevision(), relation.claimRevision()));
    }

    private void verifyManualQuote(
            BtyServiceSubmission row, Command c, CounterCheckoutQuote quote) {
        var context =
                query.root(row.getEntityNo(), row.getStoreNo(), row.getWorkOrderNo()).context();
        AppointmentPersistence.require(
                c.party().equals(access.manualParty(context, quote))
                        && operation(c).equals(quote.quote().operation())
                        && c.counterQuoteAuthority().equals(quote.purchaseAuthority()));
        var relation = access.manualBuyer(context, quote.party());
        AppointmentPersistence.require(
                Objects.equals(c.claimReceiptHash(), relation.receiptHash())
                        && Objects.equals(c.claimRevision(), relation.claimRevision()));
        if (!"MANUAL_PREPARE".equals(c.sourceMode())) throw BizException.of(ErrorCode.CONFLICT);
        var actual = quote.quote().lines();
        AppointmentPersistence.require(actual.size() == c.lines().size());
        for (var line : c.lines()) {
            long matches =
                    actual.stream()
                            .filter(
                                    q -> {
                                        return line.clientLineRef().equals(q.clientLineRef())
                                                && line.skuNo().equals(q.skuNo())
                                                && line.quantity() == q.quantity()
                                                && "COUNT".equals(q.uom())
                                                && c.tasks().stream()
                                                        .filter(t -> t.skuNo().equals(q.skuNo()))
                                                        .allMatch(
                                                                t ->
                                                                        t.goodsNo()
                                                                                .equals(
                                                                                        q
                                                                                                .goodsNo()));
                                    })
                            .count();
            AppointmentPersistence.require(matches == 1);
        }
    }

    private ManualSourceFacts manualFacts(BtyServiceSubmission row, Command c, String actor) {
        return manualOwner()
                .source(
                        scope(row.getEntityNo(), row.getStoreNo(), actor),
                        new CheckoutPort.SourceLookup(operation(c), c.expectedSourceNo()));
    }

    private void verifyManualCurrent(BtyServiceSubmission row, Command c, String actor) {
        if ("MANUAL_EXISTING".equals(c.sourceMode())) {
            verifyManualParty(row, c, manualFacts(row, c, actor));
        } else {
            var quote =
                    manualOwner()
                            .readCounterQuote(
                                    scope(row.getEntityNo(), row.getStoreNo(), actor),
                                    operation(c));
            verifyManualQuote(row, c, quote);
            if ("CREATED".equals(quote.quote().phase())) {
                verifyManualParty(row, c, manualFacts(row, c, actor));
            }
        }
        access.authorize(row.getEntityNo(), row.getStoreNo(), actor, false, false);
    }

    private ServiceSubmissionVO readManual(BtyServiceSubmission row, Command c, String actor) {
        CounterCheckoutQuote quote = null;
        ServiceSourceObservation.ManualObserved observed = null;
        var knownBeforeOwner =
                observations.manualKnown(
                        row.getEntityNo(), row.getStoreNo(), c.party(), c.operationNo());
        if (knownBeforeOwner != null
                && "MANUAL_OFFLINE"
                        .equals(observations.manualHistory(knownBeforeOwner).getAdmissionKind()))
            observations.invalidateManual(knownBeforeOwner, actor);
        try {
            if ("MANUAL_PREPARE".equals(c.sourceMode())) {
                quote =
                        manualOwner()
                                .readCounterQuote(
                                        scope(row.getEntityNo(), row.getStoreNo(), actor),
                                        operation(c));
                verifyManualQuote(row, c, quote);
            }
            var known =
                    observations.manualKnown(
                            row.getEntityNo(), row.getStoreNo(), c.party(), c.operationNo());
            if (known != null) {
                observed = observations.observeManual(known, actor);
                if (observed.facts() != null) verifyManualParty(row, c, observed.facts());
                access.authorize(row.getEntityNo(), row.getStoreNo(), actor, false, false);
                return manualView(row, c, quote, observed);
            }
            var facts = manualFacts(row, c, actor);
            if (facts.orderNo() != null) {
                verifyManualParty(row, c, facts);
                observed = observations.observeManual(manualCanonical(row, c, facts), actor);
                if (observed.facts() != null) verifyManualParty(row, c, observed.facts());
            }
        } catch (UncheckedIOException unavailable) {
            // No financial fallback and no stale readiness on a failed owner read.
        } catch (BizException unavailable) {
            if (unavailable.errorCode() != ErrorCode.NOT_FOUND
                    && unavailable.errorCode() != ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE
                    && unavailable.errorCode() != ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE)
                throw unavailable;
        }
        access.authorize(row.getEntityNo(), row.getStoreNo(), actor, false, false);
        return manualView(row, c, quote, observed);
    }

    private ServiceSubmissionVO resumeManual(
            BtyServiceSubmission intent, Command c, String actor, SubmitServicesReq original) {
        if (List.of("BOUND", "REJECTED").contains(intent.getStatus()))
            return readManual(intent, c, actor);
        access.authorize(
                intent.getEntityNo(),
                intent.getStoreNo(),
                actor,
                true,
                "MANUAL_PREPARE".equals(c.sourceMode()));
        var leased =
                db.tx(
                        () -> {
                            var row =
                                    submissions.selectScopedForUpdate(
                                            intent.getEntityNo(),
                                            intent.getStoreNo(),
                                            intent.getSubmissionNo());
                            if (submissions.claimSourceRecovery(
                                            row.getEntityNo(),
                                            row.getStoreNo(),
                                            row.getSubmissionNo(),
                                            row.getVersion(),
                                            now(),
                                            now().plusSeconds(30),
                                            actor)
                                    != 1) return null;
                            return submissions.selectScopedForUpdate(
                                    row.getEntityNo(), row.getStoreNo(), row.getSubmissionNo());
                        });
        if (leased == null) return readManual(intent, c, actor);
        CounterCheckoutQuote quote = null;
        var knownBeforeOwner =
                observations.manualKnown(
                        intent.getEntityNo(), intent.getStoreNo(), c.party(), c.operationNo());
        if (knownBeforeOwner != null
                && "MANUAL_OFFLINE"
                        .equals(observations.manualHistory(knownBeforeOwner).getAdmissionKind()))
            observations.invalidateManual(knownBeforeOwner, actor);
        try {
            if ("MANUAL_PREPARE".equals(c.sourceMode())) {
                try {
                    quote =
                            manualOwner()
                                    .readCounterQuote(
                                            scope(intent.getEntityNo(), intent.getStoreNo(), actor),
                                            operation(c));
                } catch (BizException absent) {
                    if (absent.errorCode() != ErrorCode.NOT_FOUND) throw absent;
                    if (original == null || !originalActor(c, actor))
                        return manualView(
                                pending(leased, actor, "REQUIRE_ORIGINAL_PREPARE_REQUEST"),
                                c,
                                null,
                                null);
                    AppointmentPersistence.require(
                            Objects.equals(
                                    c.customerRefHash(),
                                    IdempotencyScope.requestHash(
                                            db.json.writeValueAsString(
                                                    original.source().counterCustomer()))));
                    quote =
                            manualOwner()
                                    .prepareCounter(
                                            scope(intent.getEntityNo(), intent.getStoreNo(), actor),
                                            new PrepareCounterReq(
                                                    operation(c),
                                                    c.prepareKey(),
                                                    original.source().counterCustomer(),
                                                    c.targetScopeRevision(),
                                                    c.originalScopeHash(),
                                                    c.lines(),
                                                    c.couponNo(),
                                                    c.activityChoices(),
                                                    c.remark()));
                }
                verifyManualQuote(intent, c, quote);
            }
            var facts = manualFacts(intent, c, actor);
            if (facts.orderNo() == null)
                return manualView(pending(leased, actor, "AWAITING_CUSTOMER"), c, quote, null);
            verifyManualParty(intent, c, facts);
            var observed = observations.observeManual(manualCanonical(intent, c, facts), actor);
            access.authorize(intent.getEntityNo(), intent.getStoreNo(), actor, true, false);
            if (observed.facts() != null) verifyManualParty(intent, c, observed.facts());
            var result = bindingService.bindManual(leased, c, observed, actor);
            return manualView(
                    result.submission() == null
                            ? pending(leased, actor, result.reason())
                            : result.submission(),
                    c,
                    quote,
                    observed);
        } catch (UncheckedIOException unavailable) {
            return manualView(pending(leased, actor, "SOURCE_UNAVAILABLE"), c, quote, null);
        } catch (BizException unavailable) {
            if (unavailable.errorCode() != ErrorCode.NOT_FOUND
                    && unavailable.errorCode() != ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE
                    && unavailable.errorCode() != ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE)
                throw unavailable;
            return manualView(pending(leased, actor, "SOURCE_UNKNOWN"), c, quote, null);
        }
    }

    private ServiceSubmissionVO manualView(
            BtyServiceSubmission row,
            Command c,
            CounterCheckoutQuote quote,
            ServiceSourceObservation.ManualObserved observed) {
        var tasksNow =
                query.root(row.getEntityNo(), row.getStoreNo(), row.getWorkOrderNo()).tasks();
        var local =
                db.read(
                        () ->
                                bindings.selectCurrentForWorkOrder(
                                        row.getEntityNo(), row.getStoreNo(), row.getWorkOrderNo()));
        var taskViews =
                c.tasks().stream()
                        .map(
                                t -> {
                                    var current =
                                            tasksNow.stream()
                                                    .filter(x -> x.getTaskNo().equals(t.taskNo()))
                                                    .findFirst()
                                                    .orElseThrow();
                                    var b =
                                            local.stream()
                                                    .filter(x -> x.getTaskNo().equals(t.taskNo()))
                                                    .findFirst()
                                                    .orElse(null);
                                    return new SubmissionTaskVO(
                                            t.taskNo(),
                                            t.skuNo(),
                                            t.definitionVersionNo(),
                                            t.qty(),
                                            t.uom(),
                                            current.getVersion(),
                                            current.getBindingRevision(),
                                            b == null ? t.sourceLineNo() : b.getSourceLineRef(),
                                            b == null
                                                    ? "UNBOUND"
                                                    : observed != null && observed.manualReady()
                                                            ? "BOUND_OBSERVED"
                                                            : "UNKNOWN");
                                })
                        .toList();
        var source = observed == null ? null : observed.row();
        String phase =
                "BOUND".equals(row.getStatus())
                        ? "BOUND"
                        : row.getFailureCode() == null ? "SOURCE_UNKNOWN" : row.getFailureCode();
        return new ServiceSubmissionVO(
                row.getSubmissionNo(),
                row.getWorkOrderNo(),
                row.getAction(),
                row.getVersion(),
                c.originalScopeRevision(),
                c.targetScopeRevision(),
                row.getStatus(),
                phase,
                c.operationNo(),
                null,
                source == null ? null : source.getOrderNo(),
                source == null ? null : source.getSourceNo(),
                source == null
                        ? null
                        : observations.manualProjection(source, observed.manualReady()),
                taskViews,
                "READ_ORIGINAL",
                observed != null && observed.manualReady() && "BOUND".equals(phase)
                        ? List.of()
                        : List.of(phase),
                quote);
    }

    private void verifyQuote(
            String entity, String store, Command command, CheckoutPort.CheckoutQuote quote) {
        AppointmentPersistence.require(
                quote != null
                        && quote.operation()
                                .equals(
                                        new CheckoutPort.OperationRef(
                                                "BEAUTY", command.operationNo()))
                        && entity.equals(quote.entityNo())
                        && store.equals(quote.storeNo())
                        && command.buyerNo().equals(quote.buyerNo())
                        && "STORE_VERIFY".equals(quote.fulfillment())
                        && "SERVICE_EXECUTION".equals(quote.fulfillmentAuthority())
                        && !quote.lines().isEmpty());
        if ("EXISTING".equals(command.sourceMode())) {
            return;
        }
        AppointmentPersistence.require(quote.lines().size() == command.lines().size());
        for (var selected : command.lines()) {
            var matches =
                    quote.lines().stream()
                            .filter(
                                    line ->
                                            line.clientLineRef().equals(selected.clientLineRef())
                                                    && line.skuNo().equals(selected.skuNo())
                                                    && line.quantity() == selected.quantity()
                                                    && "COUNT".equals(line.uom()))
                            .toList();
            AppointmentPersistence.require(
                    matches.size() == 1
                            && command.tasks().stream()
                                    .filter(task -> task.skuNo().equals(selected.skuNo()))
                                    .allMatch(
                                            task ->
                                                    task.goodsNo()
                                                            .equals(matches.getFirst().goodsNo())));
        }
    }

    private ServiceSubmissionVO view(
            BtyServiceSubmission row,
            Command command,
            CheckoutPort.CheckoutQuote quote,
            ServiceSourceObservation.Observed observed) {
        String phase =
                "BOUND".equals(row.getStatus())
                        ? "BOUND"
                        : row.getFailureCode() == null
                                ? "SOURCE_UNAVAILABLE"
                                : row.getFailureCode();
        var source =
                observed == null
                        ? observations.history(
                                row.getEntityNo(),
                                row.getStoreNo(),
                                command.buyerNo(),
                                command.operationNo())
                        : observed.row();
        var projection =
                source == null
                        ? null
                        : new SourceObservationVO(
                                source.getState(),
                                source.getObservationEpoch(),
                                source.getSourceRevision(),
                                source.getSourceHash(),
                                source.getVerifiedRevision(),
                                source.getVerifiedHash(),
                                source.getObservedAt() == null
                                        ? null
                                        : source.getObservedAt().toInstant(ZoneOffset.UTC),
                                source.getFailureCode(),
                                false);
        if (observed == null) {
            projection = observations.unavailableHistory(source);
        }
        var currentTasks =
                query.root(row.getEntityNo(), row.getStoreNo(), row.getWorkOrderNo()).tasks();
        var currentBindings =
                db.read(
                        () ->
                                bindings.selectCurrentForWorkOrder(
                                        row.getEntityNo(), row.getStoreNo(), row.getWorkOrderNo()));
        var taskViews =
                command.tasks().stream()
                        .map(
                                task -> {
                                    var current =
                                            currentTasks.stream()
                                                    .filter(
                                                            candidate ->
                                                                    candidate
                                                                            .getTaskNo()
                                                                            .equals(task.taskNo()))
                                                    .findFirst()
                                                    .orElseThrow(
                                                            () ->
                                                                    BizException.of(
                                                                            ErrorCode.NOT_FOUND));
                                    var binding =
                                            currentBindings.stream()
                                                    .filter(
                                                            candidate ->
                                                                    candidate
                                                                            .getTaskNo()
                                                                            .equals(task.taskNo()))
                                                    .findFirst()
                                                    .orElse(null);
                                    return new SubmissionTaskVO(
                                            task.taskNo(),
                                            task.skuNo(),
                                            task.definitionVersionNo(),
                                            task.qty(),
                                            task.uom(),
                                            current.getVersion(),
                                            current.getBindingRevision(),
                                            binding == null
                                                    ? task.sourceLineNo()
                                                    : binding.getSourceLineRef(),
                                            binding == null
                                                    ? "UNBOUND"
                                                    : observed == null
                                                            ? "UNKNOWN"
                                                            : observed.ready()
                                                                    ? "BOUND_OBSERVED"
                                                                    : "BLOCKED");
                                })
                        .toList();
        return new ServiceSubmissionVO(
                row.getSubmissionNo(),
                row.getWorkOrderNo(),
                row.getAction(),
                row.getVersion(),
                command.originalScopeRevision(),
                command.targetScopeRevision(),
                row.getStatus(),
                phase,
                command.operationNo(),
                quote,
                source == null ? null : source.getOrderNo(),
                source == null ? null : source.getSourceNo(),
                projection,
                taskViews,
                "READ_ORIGINAL",
                "BOUND".equals(phase)
                        ? projection == null || "READY".equals(projection.state())
                                ? List.of()
                                : List.of(
                                        projection.reasonCode() == null
                                                ? "SOURCE_BLOCKED"
                                                : projection.reasonCode())
                        : List.of(phase));
    }

    private void firstBinding(BtyServiceTask task) {
        AppointmentPersistence.require(
                "PENDING".equals(task.getStatus())
                        && task.getStartedAt() == null
                        && task.getDoneQty().signum() == 0
                        && task.getVoidedQty().signum() == 0
                        && task.getBindingRevision() == 0
                        && "COUNT".equals(task.getUom())
                        && task.getQty().intValueExact() > 0
                        && task.getQty().intValueExact() <= 10);
    }

    private CheckoutPort.CheckoutScope scope(String entity, String store, String actor) {
        return new CheckoutPort.CheckoutScope("MAIN", entity, store, actor);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
