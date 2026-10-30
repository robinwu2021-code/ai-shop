package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.dto.BusinessTraceVO;
import ai.neargo.shop.beauty.dto.CurrencyBillVO;
import ai.neargo.shop.beauty.dto.ManualReceiptSummaryVO;
import ai.neargo.shop.beauty.dto.ServiceSubmissionVO;
import ai.neargo.shop.beauty.dto.SourceBillVO;
import ai.neargo.shop.beauty.dto.SourceObservationVO;
import ai.neargo.shop.beauty.dto.SubmissionTaskVO;
import ai.neargo.shop.beauty.dto.WorkOrderBillVO;
import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.beauty.entity.BtyServiceTask;
import ai.neargo.shop.beauty.entity.BtyTradeBinding;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.ServiceSourceFacts;
import ai.neargo.shop.beauty.policy.ServiceSubmissionAccess;
import ai.neargo.shop.beauty.service.WorkOrderBillService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Reads a local RR manifest, then one current owner response per source outside all local locks.
 */
public final class WorkOrderBillServiceImpl implements WorkOrderBillService {
    private final AppointmentPersistence db;
    private final ServiceSubmissionAccess access;
    private final ServiceSubmissionQuery query;
    private final BeautyMappers.WorkOrderMapper orders;
    private final BeautyMappers.ServiceTaskMapper tasks;
    private final BeautyMappers.ServiceSubmissionMapper submissions;
    private final BeautyMappers.TradeBindingMapper bindings;
    private final ObjectProvider<CheckoutPort> checkout;
    private final ServiceSourceObservation observations;
    private final TransactionTemplate reads;
    private ObjectProvider<ManualServiceAdmissionPort> manual;
    private final Clock clock;

    public WorkOrderBillServiceImpl(
            AppointmentPersistence db,
            ServiceSubmissionAccess access,
            ServiceSubmissionQuery query,
            BeautyMappers.WorkOrderMapper orders,
            BeautyMappers.ServiceTaskMapper tasks,
            BeautyMappers.ServiceSubmissionMapper submissions,
            BeautyMappers.TradeBindingMapper bindings,
            ObjectProvider<CheckoutPort> checkout,
            ServiceSourceObservation observations,
            PlatformTransactionManager manager,
            Clock clock) {
        this.db = db;
        this.access = access;
        this.query = query;
        this.orders = orders;
        this.tasks = tasks;
        this.submissions = submissions;
        this.bindings = bindings;
        this.checkout = checkout;
        this.observations = observations;
        this.clock = clock;
        reads = new TransactionTemplate(manager);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setReadOnly(true);
    }

    public WorkOrderBillServiceImpl(
            AppointmentPersistence db,
            ServiceSubmissionAccess access,
            ServiceSubmissionQuery query,
            BeautyMappers.WorkOrderMapper orders,
            BeautyMappers.ServiceTaskMapper tasks,
            BeautyMappers.ServiceSubmissionMapper submissions,
            BeautyMappers.TradeBindingMapper bindings,
            ObjectProvider<CheckoutPort> checkout,
            ServiceSourceObservation observations,
            PlatformTransactionManager manager,
            Clock clock,
            ObjectProvider<ManualServiceAdmissionPort> manual) {
        this(
                db,
                access,
                query,
                orders,
                tasks,
                submissions,
                bindings,
                checkout,
                observations,
                manager,
                clock);
        this.manual = manual;
    }

    private record Manifest(
            ServiceSubmissionQuery.Root root,
            List<BtyServiceSubmission> submissions,
            List<BtyTradeBinding> bindings,
            String hash) {}

    private record ReadSource(SourceBillVO view, List<String> receiptKeys) {}

    @Override
    public WorkOrderBillVO getBill(String entity, String store, String actor, String workOrder) {
        access.authorize(entity, store, actor, false, false);
        for (int attempt = 0; ; attempt++) {
            var manifest = snapshot(entity, store, workOrder);

            var groups = new TreeMap<String, List<BtyServiceSubmission>>();
            for (var submission : manifest.submissions()) {
                var command = command(submission);
                if (command.schemaVersion() == 2) {
                    var buyer = access.buyer(manifest.root().context(), null);
                    AppointmentPersistence.require(
                            command.buyerNo().equals(buyer.userNo())
                                    && Objects.equals(
                                            command.claimReceiptHash(), buyer.receiptHash())
                                    && Objects.equals(
                                            command.claimRevision(), buyer.claimRevision()));
                }
                groups.computeIfAbsent(command.operationNo(), ignored -> new ArrayList<>())
                        .add(submission);
            }
            var sourceReads =
                    groups.values().stream()
                            .map(rows -> source(entity, store, actor, manifest, rows))
                            .toList();
            var sources = new ArrayList<>(sourceReads.stream().map(ReadSource::view).toList());
            var receipts = new HashMap<String, Integer>();
            for (int index = 0; index < sourceReads.size(); index++) {
                for (String receipt : sourceReads.get(index).receiptKeys()) {
                    Integer previous = receipts.putIfAbsent(receipt, index);
                    if (previous != null
                            && !Objects.equals(
                                    sources.get(previous).orderNo(),
                                    sources.get(index).orderNo())) {
                        sources.set(
                                previous,
                                unavailable(sources.get(previous), "SOURCE_RECEIPT_CROSS_ORDER"));
                        sources.set(
                                index,
                                unavailable(sources.get(index), "SOURCE_RECEIPT_CROSS_ORDER"));
                    }
                }
            }
            boolean unchanged = manifest.hash().equals(snapshot(entity, store, workOrder).hash());
            if (!unchanged && attempt == 0) {
                continue;
            }
            var blockers = new ArrayList<String>();
            if (!unchanged) {
                blockers.add("LOCAL_SNAPSHOT_CHANGED");
            }
            if (manifest.root().tasks().stream().anyMatch(task -> task.getBindingRevision() == 0)) {
                blockers.add("UNBOUND_TASKS");
            }
            if (sources.isEmpty()) {
                blockers.add("NO_SOURCE");
            }
            boolean complete =
                    unchanged
                            && blockers.isEmpty()
                            && sources.stream().allMatch(SourceBillVO::complete);
            if (sources.stream().anyMatch(source -> !source.complete())) {
                blockers.add("SOURCE_INCOMPLETE");
            }
            var totals = currencies(sources, unchanged && !blockers.contains("UNBOUND_TASKS"));
            if (totals.stream().anyMatch(total -> !total.complete())) {
                complete = false;
                blockers.add("CURRENCY_AGGREGATE_INCOMPLETE");
            }
            return new WorkOrderBillVO(
                    workOrder,
                    manifest.root().order().getScopeRevision(),
                    manifest.hash(),
                    clock.instant(),
                    sources,
                    totals,
                    complete,
                    blockers);
        }
    }

    private Manifest snapshot(String entity, String store, String workOrder) {
        return db.read(
                () ->
                        reads.execute(
                                status -> {
                                    var root = query.root(entity, store, workOrder);
                                    long max = submissions.sourceMaximum(entity, store, workOrder);
                                    long cursor = 0;
                                    var rows = new ArrayList<BtyServiceSubmission>();
                                    while (cursor < max) {
                                        var page =
                                                submissions.selectWorkOrderPage(
                                                        entity, store, workOrder, cursor, max);
                                        if (page.isEmpty()) {
                                            break;
                                        }
                                        rows.addAll(page);
                                        cursor = page.getLast().getId();
                                    }
                                    var currentBindings =
                                            bindings.selectCurrentForWorkOrder(
                                                    entity, store, workOrder);
                                    String hash =
                                            IdempotencyScope.requestHash(
                                                    "BEAUTY_BILL_MANIFEST_V1",
                                                    workOrder,
                                                    root.order().getVersion().toString(),
                                                    root.order().getScopeRevision().toString(),
                                                    taskWatermark(root.tasks()),
                                                    db.json.writeValueAsString(
                                                            rows.stream()
                                                                    .map(
                                                                            row ->
                                                                                    List.of(
                                                                                            row
                                                                                                    .getSubmissionNo(),
                                                                                            row
                                                                                                    .getVersion(),
                                                                                            row
                                                                                                    .getPayloadHash()))
                                                                    .toList()),
                                                    db.json.writeValueAsString(currentBindings));
                                    return new Manifest(
                                            root,
                                            List.copyOf(rows),
                                            List.copyOf(currentBindings),
                                            hash);
                                }));
    }

    private String taskWatermark(List<BtyServiceTask> rows) {
        return db.json.writeValueAsString(
                rows.stream()
                        .map(
                                task ->
                                        List.of(
                                                task.getTaskNo(),
                                                task.getVersion(),
                                                task.getBindingRevision()))
                        .toList());
    }

    private ReadSource source(
            String entity,
            String store,
            String actor,
            Manifest manifest,
            List<BtyServiceSubmission> rows) {
        // A failed historical V2 intent remains history after the same original gains a valid V3
        // intent.
        // Revalidate only the V3 owner party in the manual domain; never reinterpret old financial
        // hashes.
        var manualRows = rows.stream().filter(row -> command(row).schemaVersion() == 3).toList();
        var command = command(manualRows.isEmpty() ? rows.getFirst() : manualRows.getFirst());
        var linkedTasks =
                rows.stream()
                        .flatMap(row -> command(row).tasks().stream())
                        .map(ServiceSubmissionServiceImpl.FrozenTask::taskNo)
                        .distinct()
                        .toList();
        var projected =
                manifest.root().tasks().stream()
                        .filter(task -> linkedTasks.contains(task.getTaskNo()))
                        .map(task -> taskView(task, manifest.bindings()))
                        .toList();
        if (command.schemaVersion() == 3)
            return manualSource(entity, store, actor, manifest, manualRows, projected);
        var owner = checkout.getIfAvailable();
        String currency = null;
        var history = observations.history(entity, store, command.buyerNo(), command.operationNo());
        String order = history == null ? null : history.getOrderNo();
        String sub = history == null ? null : history.getSourceNo();
        SourceObservationVO projection = observations.unavailableHistory(history);
        try {
            if (owner == null) {
                projection =
                        observations.unavailableHistory(
                                observations.invalidateKnown(
                                        entity,
                                        store,
                                        command.buyerNo(),
                                        command.operationNo(),
                                        actor));
                return pending(
                        command.operationNo(),
                        order,
                        sub,
                        null,
                        projected,
                        projection,
                        "SOURCE_UNAVAILABLE");
            }
            var scope = new CheckoutPort.CheckoutScope("MAIN", entity, store, actor);
            var operation = new CheckoutPort.OperationRef("BEAUTY", command.operationNo());
            var quote = owner.readQuote(scope, operation);
            AppointmentPersistence.require(
                    quote != null
                            && operation.equals(quote.operation())
                            && entity.equals(quote.entityNo())
                            && store.equals(quote.storeNo())
                            && command.buyerNo().equals(quote.buyerNo())
                            && "STORE_VERIFY".equals(quote.fulfillment())
                            && "SERVICE_EXECUTION".equals(quote.fulfillmentAuthority()));
            currency =
                    quote.currency() != null && quote.currency().matches("[A-Z]{3}")
                            ? quote.currency()
                            : null;
            var result = owner.findOperation(scope, operation);
            AppointmentPersistence.require(result != null && operation.equals(result.operation()));
            if (!"CREATED".equals(result.phase())) {
                projection =
                        observations.unavailableHistory(
                                observations.invalidateKnown(
                                        entity,
                                        store,
                                        command.buyerNo(),
                                        command.operationNo(),
                                        actor));
                return pending(
                        command.operationNo(),
                        order,
                        sub,
                        currency,
                        projected,
                        projection,
                        history == null ? "AWAITING_CUSTOMER" : "SOURCE_UNKNOWN");
            }
            order = result.orderNo();
            sub = result.subOrderNo();
            for (var row : rows) {
                var selected = command(row);
                AppointmentPersistence.require(
                        !"EXISTING".equals(selected.sourceMode())
                                || selected.expectedSourceNo().equals(sub));
            }
            var observed =
                    observations.observe(
                            new ServiceSourceObservation.Canonical(
                                    entity,
                                    store,
                                    command.buyerNo(),
                                    command.operationNo(),
                                    order,
                                    sub),
                            actor);
            projection = projection(observed);
            var facts = observed.facts();
            var fence = observed.row();
            boolean current =
                    facts != null
                            && fence.getObservationEpoch() == observed.ticket()
                            && Objects.equals(fence.getVerifiedRevision(), facts.sourceRevision())
                            && Objects.equals(fence.getVerifiedHash(), facts.sourceHash())
                            && Objects.equals(fence.getSourceRevision(), facts.sourceRevision())
                            && Objects.equals(fence.getSourceHash(), facts.sourceHash());
            if (!current) {
                return pending(
                        command.operationNo(),
                        order,
                        sub,
                        currency,
                        projected,
                        projection,
                        "SOURCE_UNKNOWN");
            }
            var money = ServiceSourceFacts.money(facts);
            if (!money.complete() || !Objects.equals(currency, money.currency())) {
                return pending(
                        command.operationNo(),
                        order,
                        sub,
                        currency,
                        projected,
                        projection,
                        "SOURCE_MONEY_INVALID");
            }
            final String source = sub;
            var allocated = new TreeMap<String, BigDecimal>();
            for (var binding : manifest.bindings()) {
                if (source.equals(binding.getSourceNo())
                        && "STORE_ORDER".equals(binding.getSourceKind())) {
                    allocated.merge(binding.getSourceLineRef(), binding.getQty(), BigDecimal::add);
                }
            }
            boolean attributable =
                    allocated.size() == facts.lines().size()
                            && facts.lines().stream()
                                    .allMatch(
                                            line ->
                                                    BigDecimal.valueOf(line.orderedQuantity())
                                                                    .compareTo(
                                                                            allocated.getOrDefault(
                                                                                    line
                                                                                            .sourceLineNo(),
                                                                                    BigDecimal
                                                                                            .ZERO))
                                                            == 0);
            var reasons = new ArrayList<String>();
            if (!attributable) {
                reasons.add("SOURCE_AMOUNT_NOT_ATTRIBUTABLE");
            }
            if (!observed.ready()) {
                reasons.add("SOURCE_OWNER_BLOCKED");
            }
            var view =
                    new SourceBillVO(
                            command.operationNo(),
                            order,
                            sub,
                            money.currency(),
                            money.due(),
                            money.received(),
                            money.refunded(),
                            money.netReceived(),
                            money.outstanding(),
                            "WHOLE_SOURCE",
                            money.receipts(),
                            projected,
                            projection,
                            attributable,
                            reasons);
            return new ReadSource(
                    view,
                    facts.payments().stream()
                            .map(
                                    payment ->
                                            ServiceSourceFacts.receiptIdentity(
                                                    facts.orderNo(), payment))
                            .distinct()
                            .toList());
        } catch (UncheckedIOException transport) {
            projection =
                    observations.unavailableHistory(
                            observations.invalidateKnown(
                                    entity,
                                    store,
                                    command.buyerNo(),
                                    command.operationNo(),
                                    actor));
            return pending(
                    command.operationNo(),
                    order,
                    sub,
                    currency,
                    projected,
                    projection,
                    "SOURCE_UNAVAILABLE");
        } catch (BizException invalid) {
            if (invalid.errorCode() != ErrorCode.NOT_FOUND
                    && invalid.errorCode() != ErrorCode.CONFLICT
                    && invalid.errorCode() != ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE) {
                throw invalid;
            }
            projection =
                    observations.unavailableHistory(
                            observations.invalidateKnown(
                                    entity,
                                    store,
                                    command.buyerNo(),
                                    command.operationNo(),
                                    actor));
            return pending(
                    command.operationNo(),
                    order,
                    sub,
                    currency,
                    projected,
                    projection,
                    invalid.errorCode() == ErrorCode.CONFLICT
                            ? "SOURCE_IDENTITY_MISMATCH"
                            : "SOURCE_UNAVAILABLE");
        }
    }

    /** Cash declarations are a separate display subtotal; no Pay or funded quote is read here. */
    private ReadSource manualSource(
            String entity,
            String store,
            String actor,
            Manifest manifest,
            List<BtyServiceSubmission> rows,
            List<SubmissionTaskVO> projected) {
        var c = command(rows.getFirst());
        String order = null, sub = c.expectedSourceNo(), currency = null;
        SourceObservationVO projection = null;
        var known = observations.manualKnown(entity, store, c.party(), c.operationNo());
        if (known != null
                && "MANUAL_OFFLINE".equals(observations.manualHistory(known).getAdmissionKind()))
            observations.invalidateManual(known, actor);
        try {
            var owner = manual == null ? null : manual.getIfAvailable();
            if (owner == null) throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
            var facts =
                    owner.source(
                            new CheckoutPort.CheckoutScope("MAIN", entity, store, actor),
                            new CheckoutPort.SourceLookup(
                                    new CheckoutPort.OperationRef("BEAUTY", c.operationNo()), sub));
            if (facts == null || facts.orderNo() == null)
                return manualPending(c.operationNo(), order, sub, currency, projected, projection);
            for (var row : rows) {
                var selected = command(row);
                var party = access.manualParty(manifest.root().context(), facts);
                var relation = access.manualBuyer(manifest.root().context(), party);
                AppointmentPersistence.require(
                        selected.party().equals(party)
                                && selected.counterQuoteAuthority()
                                        .equals(facts.purchaseAuthority())
                                && Objects.equals(
                                        selected.claimReceiptHash(), relation.receiptHash())
                                && Objects.equals(
                                        selected.claimRevision(), relation.claimRevision())
                                && new CheckoutPort.OperationRef("BEAUTY", selected.operationNo())
                                        .equals(facts.operation())
                                && (!"MANUAL_EXISTING".equals(selected.sourceMode())
                                        || selected.expectedSourceNo().equals(facts.subOrderNo())));
            }
            order = facts.orderNo();
            sub = facts.subOrderNo();
            currency = facts.currency();
            var observed =
                    observations.observeManual(
                            new ServiceSourceObservation.ManualCanonical(
                                    entity, store, c.party(), c.operationNo(), order, sub),
                            actor);
            projection = observations.manualProjection(observed.row(), observed.manualReady());
            access.authorize(entity, store, actor, false, false);
            if (!observed.manualReady() || observed.facts() == null)
                return manualPending(c.operationNo(), order, sub, currency, projected, projection);
            facts = observed.facts();
            // The second owner read is independently identity checked, including current claim
            // linkage.
            AppointmentPersistence.require(
                    c.party().equals(access.manualParty(manifest.root().context(), facts))
                            && c.counterQuoteAuthority().equals(facts.purchaseAuthority()));
            var relation = access.manualBuyer(manifest.root().context(), facts.party());
            AppointmentPersistence.require(
                    Objects.equals(c.claimReceiptHash(), relation.receiptHash())
                            && Objects.equals(c.claimRevision(), relation.claimRevision()));
            var allocated = new TreeMap<String, BigDecimal>();
            for (var binding : manifest.bindings())
                if (facts.subOrderNo().equals(binding.getSourceNo())
                        && "MANUAL_OFFLINE".equals(binding.getAdmissionKind())
                        && Objects.equals(c.party().kind(), binding.getCustomerKind())
                        && Objects.equals(c.party().userNo(), binding.getBuyerNo())
                        && Objects.equals(c.party().guestNo(), binding.getGuestNo())
                        && Objects.equals(
                                c.party().identityRevision(), binding.getIdentityRevision()))
                    allocated.merge(binding.getSourceLineRef(), binding.getQty(), BigDecimal::add);
            boolean attributable =
                    allocated.size() == facts.lines().size()
                            && facts.lines().stream()
                                    .allMatch(
                                            l ->
                                                    BigDecimal.valueOf(l.orderedQuantity())
                                                                    .compareTo(
                                                                            allocated.getOrDefault(
                                                                                    l
                                                                                            .sourceLineNo(),
                                                                                    BigDecimal
                                                                                            .ZERO))
                                                            == 0);
            var declaration = facts.declaration();
            boolean complete =
                    attributable
                            && declaration != null
                            && declaration.declaredMinor() != null
                            && facts.dueMinor() != null
                            && facts.currency() != null
                            && "ADMITTED".equals(declaration.admissionState())
                            && "MANUAL_DECLARATION".equals(declaration.receiptProvenance())
                            && facts.dueMinor().equals(declaration.declaredMinor());
            ManualReceiptSummaryVO receipt =
                    !complete
                            ? null
                            : new ManualReceiptSummaryVO(
                                    declaration.manualDeclarationNo(),
                                    declaration.method().name(),
                                    declaration.declaredMinor(),
                                    declaration.currency(),
                                    declaration.receivedAt(),
                                    declaration.recordedAt(),
                                    declaration.receiptProvenance(),
                                    "NOT_APPLICABLE_MANUAL");
            return new ReadSource(
                    new SourceBillVO(
                            c.operationNo(),
                            order,
                            sub,
                            facts.currency(),
                            facts.dueMinor(),
                            null,
                            null,
                            null,
                            null,
                            "WHOLE_SOURCE",
                            List.of(),
                            projected,
                            projection,
                            complete,
                            complete ? List.of() : List.of("MANUAL_SOURCE_INCOMPLETE"),
                            "MANUAL_OFFLINE",
                            "NOT_APPLICABLE_MANUAL",
                            receipt,
                            complete ? declaration.declaredMinor() : null),
                    List.of());
        } catch (UncheckedIOException unavailable) {
            return manualPending(c.operationNo(), order, sub, currency, projected, projection);
        } catch (BizException unavailable) {
            if (unavailable.errorCode() != ErrorCode.NOT_FOUND
                    && unavailable.errorCode() != ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE
                    && unavailable.errorCode() != ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE)
                throw unavailable;
            return manualPending(c.operationNo(), order, sub, currency, projected, projection);
        }
    }

    private ReadSource manualPending(
            String operation,
            String order,
            String sub,
            String currency,
            List<SubmissionTaskVO> tasks,
            SourceObservationVO projection) {
        return new ReadSource(
                new SourceBillVO(
                        operation,
                        order,
                        sub,
                        currency,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "WHOLE_SOURCE",
                        List.of(),
                        tasks,
                        projection,
                        false,
                        List.of("SOURCE_UNKNOWN"),
                        "MANUAL_OFFLINE",
                        "NOT_APPLICABLE_MANUAL",
                        null,
                        null),
                List.of());
    }

    private ReadSource pending(
            String operation,
            String order,
            String source,
            String currency,
            List<SubmissionTaskVO> tasks,
            SourceObservationVO observation,
            String reason) {
        return new ReadSource(
                new SourceBillVO(
                        operation,
                        order,
                        source,
                        currency,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "WHOLE_SOURCE",
                        List.of(),
                        tasks,
                        observation,
                        false,
                        List.of(reason)),
                List.of());
    }

    private SourceBillVO unavailable(SourceBillVO row, String reason) {
        return pending(
                        row.checkoutOperationNo(),
                        row.orderNo(),
                        row.sourceNo(),
                        row.currency(),
                        row.tasks(),
                        row.observation(),
                        reason)
                .view();
    }

    private SourceObservationVO projection(ServiceSourceObservation.Observed observed) {
        var row = observed.row();
        return new SourceObservationVO(
                row.getState(),
                row.getObservationEpoch(),
                row.getSourceRevision(),
                row.getSourceHash(),
                row.getVerifiedRevision(),
                row.getVerifiedHash(),
                row.getObservedAt() == null ? null : row.getObservedAt().toInstant(ZoneOffset.UTC),
                row.getFailureCode(),
                false);
    }

    private List<CurrencyBillVO> currencies(List<SourceBillVO> sources, boolean localComplete) {
        var groups = new TreeMap<String, List<SourceBillVO>>();
        for (var source : sources)
            if (source.currency() != null)
                groups.computeIfAbsent(source.currency(), ignored -> new ArrayList<>()).add(source);
        return groups.entrySet().stream()
                .map(
                        entry -> {
                            long due = 0,
                                    received = 0,
                                    refunded = 0,
                                    net = 0,
                                    outstanding = 0,
                                    declared = 0;
                            boolean complete =
                                    localComplete
                                            && entry.getValue().stream()
                                                    .allMatch(SourceBillVO::complete);
                            boolean hasManual =
                                    entry.getValue().stream()
                                            .anyMatch(
                                                    x ->
                                                            "MANUAL_OFFLINE"
                                                                    .equals(x.admissionKind()));
                            boolean hasFunded =
                                    entry.getValue().stream()
                                            .anyMatch(
                                                    x -> "LEGACY_FUNDED".equals(x.admissionKind()));
                            if (complete)
                                try {
                                    for (var source : entry.getValue()) {
                                        if ("MANUAL_OFFLINE".equals(source.admissionKind()))
                                            declared =
                                                    Math.addExact(
                                                            declared, source.manualDeclaredMinor());
                                        else {
                                            due = Math.addExact(due, source.dueMinor());
                                            received =
                                                    Math.addExact(received, source.receivedMinor());
                                            refunded =
                                                    Math.addExact(refunded, source.refundedMinor());
                                            net = Math.addExact(net, source.netReceivedMinor());
                                            outstanding =
                                                    Math.addExact(
                                                            outstanding, source.outstandingMinor());
                                        }
                                    }
                                } catch (ArithmeticException overflow) {
                                    complete = false;
                                }
                            return new CurrencyBillVO(
                                    entry.getKey(),
                                    complete && hasFunded ? due : null,
                                    complete && hasFunded ? received : null,
                                    complete && hasFunded ? refunded : null,
                                    complete && hasFunded ? net : null,
                                    complete && hasFunded ? outstanding : null,
                                    complete,
                                    complete && hasManual ? declared : null,
                                    hasManual ? "NOT_APPLICABLE_MANUAL" : "VERIFIED_PAYMENT");
                        })
                .toList();
    }

    private SubmissionTaskVO taskView(BtyServiceTask task, List<BtyTradeBinding> bindings) {
        var binding =
                bindings.stream()
                        .filter(row -> row.getTaskNo().equals(task.getTaskNo()))
                        .findFirst()
                        .orElse(null);
        return new SubmissionTaskVO(
                task.getTaskNo(),
                task.getSkuNo(),
                task.getDefinitionVersionNo(),
                task.getQty(),
                task.getUom(),
                task.getVersion(),
                task.getBindingRevision(),
                binding == null ? null : binding.getSourceLineRef(),
                binding == null ? "UNBOUND" : "BOUND_OBSERVED");
    }

    private ServiceSubmissionServiceImpl.Command command(BtyServiceSubmission row) {
        var command =
                db.json.readValue(
                        row.getFrozenCommand(), ServiceSubmissionServiceImpl.Command.class);
        AppointmentPersistence.require(ServiceSubmissionServiceImpl.validCommand(command, row));
        return command;
    }

    @Override
    public BusinessTraceVO getTrace(String entity, String store, String actor, String businessNo) {
        access.authorize(entity, store, actor, false, false);
        String workOrder =
                db.read(
                        () -> {
                            var root = orders.selectScoped(entity, store, businessNo);
                            if (root != null) {
                                return root.getWorkOrderNo();
                            }
                            var submission =
                                    submissions.selectSubmission(entity, store, businessNo);
                            if (submission != null
                                    && List.of("SUBMIT_SOURCE", "ADD_SOURCE")
                                            .contains(submission.getAction())) {
                                return submission.getWorkOrderNo();
                            }
                            var task =
                                    tasks.selectOne(
                                            Wrappers.<BtyServiceTask>lambdaQuery()
                                                    .eq(BtyServiceTask::getTenantNo, "MAIN")
                                                    .eq(BtyServiceTask::getEntityNo, entity)
                                                    .eq(BtyServiceTask::getTaskNo, businessNo));
                            if (task != null
                                    && orders.selectScoped(entity, store, task.getWorkOrderNo())
                                            != null) {
                                return task.getWorkOrderNo();
                            }
                            throw BizException.of(ErrorCode.NOT_FOUND);
                        });
        var bill = getBill(entity, store, actor, workOrder);
        var manifest = snapshot(entity, store, workOrder);
        boolean complete = bill.complete() && bill.localWatermark().equals(manifest.hash());
        var views =
                manifest.submissions().stream()
                        .map(
                                row -> {
                                    var command = command(row);
                                    var source =
                                            bill.sources().stream()
                                                    .filter(
                                                            candidate ->
                                                                    candidate
                                                                            .checkoutOperationNo()
                                                                            .equals(
                                                                                    command
                                                                                            .operationNo()))
                                                    .findFirst()
                                                    .orElse(null);
                                    return new ServiceSubmissionVO(
                                            row.getSubmissionNo(),
                                            workOrder,
                                            row.getAction(),
                                            row.getVersion(),
                                            command.originalScopeRevision(),
                                            command.targetScopeRevision(),
                                            row.getStatus(),
                                            "BOUND".equals(row.getStatus())
                                                    ? "BOUND"
                                                    : row.getFailureCode(),
                                            command.operationNo(),
                                            null,
                                            source == null ? null : source.orderNo(),
                                            source == null ? null : source.sourceNo(),
                                            source == null ? null : source.observation(),
                                            source == null ? List.of() : source.tasks(),
                                            "READ_ORIGINAL",
                                            source == null
                                                    ? List.of("SOURCE_UNAVAILABLE")
                                                    : source.blockingReasons());
                                })
                        .toList();
        return new BusinessTraceVO(
                businessNo,
                workOrder,
                manifest.root().order().getAppointmentNo(),
                bill.scopeRevision(),
                views,
                bill,
                complete,
                complete ? List.of() : List.of("SOURCE_INCOMPLETE"));
    }
}
