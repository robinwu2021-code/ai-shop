package ai.neargo.shop.beauty.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.beauty.dto.SourceObservationVO;
import ai.neargo.shop.beauty.entity.BtySourceObservation;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.ServiceSourceFacts;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualSourceFacts;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Observes original owner facts; readiness only permits local allocation, never execution. */
public final class ServiceSourceObservation {
    private final BeautyMappers.SourceObservationMapper mapper;
    private final ObjectProvider<CheckoutPort> checkout;
    private ObjectProvider<ManualServiceAdmissionPort> manual;
    private final TransactionTemplate writes;
    private final ObjectMapper json;
    private final Clock clock;
    private final ServiceExecutionFenceCoordinator executionFences;

    public ServiceSourceObservation(
            BeautyMappers.SourceObservationMapper mapper,
            ObjectProvider<CheckoutPort> checkout,
            PlatformTransactionManager manager,
            ObjectMapper json,
            Clock clock,
            ServiceExecutionFenceCoordinator executionFences) {
        this.mapper = mapper;
        this.checkout = checkout;
        this.json = json;
        this.clock = clock;
        this.executionFences = Objects.requireNonNull(executionFences);
        writes = new TransactionTemplate(manager);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * Both direct construction paths require the local coordinator; production wiring supplies the actual manual
     * owner.
     */
    public ServiceSourceObservation(
            BeautyMappers.SourceObservationMapper mapper,
            ObjectProvider<CheckoutPort> checkout,
            ObjectProvider<ManualServiceAdmissionPort> manual,
            PlatformTransactionManager manager,
            ObjectMapper json,
            Clock clock,
            ServiceExecutionFenceCoordinator executionFences) {
        this(mapper, checkout, manager, json, clock, executionFences);
        this.manual = manual;
    }

    /**
     * Immutable canonical identity established from original owner quote/result, never caller
     * money.
     */
    public record Canonical(
            String entityNo,
            String storeNo,
            String buyerNo,
            String operationNo,
            String orderNo,
            String sourceNo) {
        public Canonical {
            for (String value :
                    new String[] {entityNo, storeNo, buyerNo, operationNo, orderNo, sourceNo}) {
                if (value == null
                        || value.isBlank()
                        || value.length() > 64
                        || !value.equals(value.strip())) {
                    throw BizException.of(ErrorCode.BAD_REQUEST);
                }
            }
        }
    }

    /** Keeps the observation ticket separate from historical owner evidence. */
    public record Observed(
            BtySourceObservation row, CheckoutPort.CheckoutSourceFacts facts, long ticket) {
        public boolean ready() {
            return row != null
                    && "READY".equals(row.getState())
                    && row.getObservationEpoch() == ticket;
        }
    }

    /** Claims UNKNOWN before owner IO; completion is a separate local transaction. */
    public Observed observe(Canonical source, String actorNo) {
        outsideTransaction();
        long ticket;
        try {
            ticket = tx(() -> claim(source, actorNo));
        } catch (DuplicateKeyException collision) {
            ticket = tx(() -> claim(source, actorNo));
        }
        if (ticket < 0) {
            return new Observed(read(source), null, ticket);
        }
        CheckoutPort owner = checkout.getIfAvailable();
        if (owner == null) {
            return new Observed(read(source), null, ticket);
        }
        var facts =
                owner.source(
                        new CheckoutPort.CheckoutScope(
                                "MAIN", source.entityNo(), source.storeNo(), actorNo),
                        new CheckoutPort.SourceLookup(
                                new CheckoutPort.OperationRef("BEAUTY", source.operationNo()),
                                source.sourceNo()));
        long issued = ticket;
        return tx(() -> complete(source, actorNo, issued, facts));
    }

    /**
     * Local projection only; public callers authorize the canonical source before reaching this
     * helper.
     */
    public BtySourceObservation read(Canonical source) {
        return DataScopeContext.executeWithoutScope(
                () ->
                        mapper.selectCanonical(
                                source.entityNo(), source.storeNo(), source.sourceNo()));
    }

    /**
     * Reads only previously verified canonical history after the caller has authorized its frozen
     * buyer.
     */
    public BtySourceObservation history(
            String entity, String store, String buyer, String operation) {
        return DataScopeContext.executeWithoutScope(
                () ->
                        mapper.selectOne(
                                Wrappers.<BtySourceObservation>lambdaQuery()
                                        .eq(BtySourceObservation::getTenantNo, "MAIN")
                                        .eq(BtySourceObservation::getEntityNo, entity)
                                        .eq(BtySourceObservation::getStoreNo, store)
                                        .eq(BtySourceObservation::getBuyerNo, buyer)
                                        .eq(BtySourceObservation::getCheckoutCaller, "BEAUTY")
                                        .eq(BtySourceObservation::getCheckoutOperationNo, operation)
                                        .eq(BtySourceObservation::getSourceKind, "STORE_ORDER")
                                        .eq(
                                                BtySourceObservation::getAdmissionKind,
                                                "LEGACY_FUNDED")));
    }

    /** Fences every in-flight ticket when an authorized original operation becomes unavailable. */
    public BtySourceObservation invalidateKnown(
            String entity, String store, String buyer, String operation, String actor) {
        outsideTransaction();
        var known = history(entity, store, buyer, operation);
        if (known == null) {
            return null;
        }
        var canonical =
                new Canonical(
                        entity, store, buyer, operation, known.getOrderNo(), known.getSourceNo());
        return tx(
                () -> {
                    var row = mapper.selectCanonicalForUpdate(entity, known.getSourceNo());
                    identity(canonical, row);
                    if (!"LEGACY_FUNDED".equals(row.getAdmissionKind())) {
                        return row;
                    }
                    row.setObservationEpoch(Math.addExact(row.getObservationEpoch(), 1));
                    row.setState("UNKNOWN");
                    row.setFailureCode("SOURCE_UNAVAILABLE");
                    row.setObservedAt(null);
                    persist(row, actor);
                    return row;
                });
    }

    /** A read failure preserves historical pairs while explicitly withdrawing current readiness. */
    public SourceObservationVO unavailableHistory(BtySourceObservation row) {
        if (row == null) {
            return null;
        }
        return new SourceObservationVO(
                "UNKNOWN",
                row.getObservationEpoch(),
                row.getSourceRevision(),
                row.getSourceHash(),
                row.getVerifiedRevision(),
                row.getVerifiedHash(),
                null,
                "SOURCE_UNAVAILABLE",
                false);
    }

    private long claim(Canonical source, String actorNo) {
        var row = mapper.selectCanonicalForUpdate(source.entityNo(), source.sourceNo());
        if (row == null) {
            row = new BtySourceObservation();
            row.setTenantNo("MAIN");
            row.setEntityNo(source.entityNo());
            row.setStoreNo(source.storeNo());
            row.setSourceKind("STORE_ORDER");
            row.setSourceNo(source.sourceNo());
            row.setCheckoutCaller("BEAUTY");
            row.setCheckoutOperationNo(source.operationNo());
            row.setOrderNo(source.orderNo());
            row.setBuyerNo(source.buyerNo());
            row.setObservationEpoch(0L);
            row.setState("UNKNOWN");
            row.setFailureCode("SOURCE_OBSERVATION_IN_FLIGHT");
            row.setVersion(0L);
            row.setDeleted(0);
            row.setCreatedAt(now());
            row.setUpdatedAt(now());
            row.setCreatedBy(actorNo);
            row.setUpdatedBy(actorNo);
            mapper.insert(row);
        }
        identity(source, row);
        if (!"LEGACY_FUNDED".equals(row.getAdmissionKind())) {
            return -1;
        }
        row.setObservationEpoch(Math.addExact(row.getObservationEpoch(), 1));
        row.setState("UNKNOWN");
        row.setFailureCode("SOURCE_OBSERVATION_IN_FLIGHT");
        row.setObservedAt(null);
        persist(row, actorNo);
        return row.getObservationEpoch();
    }

    private Observed complete(
            Canonical source, String actorNo, long ticket, CheckoutPort.CheckoutSourceFacts facts) {
        var row = mapper.selectCanonicalForUpdate(source.entityNo(), source.sourceNo());
        identity(source, row);
        if (!"LEGACY_FUNDED".equals(row.getAdmissionKind())) {
            return new Observed(row, null, ticket);
        }
        Kind kind = classify(source, row, facts);
        boolean current = row.getObservationEpoch() == ticket;
        if (kind == Kind.INVALID) {
            if (current) {
                block(row, "SOURCE_CONTRACT_INVALID");
                row.setObservedAt(now());
                persist(row, actorNo);
            }
            return new Observed(row, null, ticket);
        }
        Long highest = row.getSourceRevision();
        boolean higher =
                facts.sourceRevision() > 0 && (highest == null || facts.sourceRevision() > highest);
        boolean conflict =
                highest != null
                        && facts.sourceRevision() == highest
                        && !row.getSourceHash().equals(facts.sourceHash());
        if (!current) {
            if (kind == Kind.UNKNOWN) {
                if (higher) {
                    highest(row, facts);
                }
                row.setObservationEpoch(Math.addExact(row.getObservationEpoch(), 1));
                row.setObservedAt(null);
                row.setState(conflict ? "BLOCKED" : "UNKNOWN");
                row.setFailureCode(
                        conflict ? "SOURCE_HASH_CONFLICT" : "SOURCE_SUPERSEDED_OWNER_UNKNOWN");
                persist(row, actorNo);
            } else if (higher || conflict) {
                if (higher) {
                    highest(row, facts);
                }
                block(
                        row,
                        conflict ? "SOURCE_HASH_CONFLICT" : "SOURCE_NEWER_SUPERSEDED_OBSERVATION");
                persist(row, actorNo);
            }
            return new Observed(row, null, ticket);
        }
        // This monotonicity defense is independent of the query ticket and is an explicit ablation
        // target.
        if (highest != null && facts.sourceRevision() > 0 && facts.sourceRevision() < highest) {
            block(row, "SOURCE_REVISION_REGRESSION");
            row.setObservedAt(now());
            persist(row, actorNo);
            return new Observed(row, null, ticket);
        }
        if (conflict) {
            block(row, "SOURCE_HASH_CONFLICT");
            row.setObservedAt(now());
            persist(row, actorNo);
            return new Observed(row, null, ticket);
        }
        if (kind == Kind.UNKNOWN) {
            if (higher) {
                highest(row, facts);
            }
            row.setState("UNKNOWN");
            row.setFailureCode("SOURCE_OWNER_UNKNOWN");
            row.setObservedAt(now());
            persist(row, actorNo);
            return new Observed(row, null, ticket);
        }
        highest(row, facts);
        row.setVerifiedRevision(facts.sourceRevision());
        row.setVerifiedHash(facts.sourceHash());
        row.setVerifiedLines(json.writeValueAsString(quantityLines(facts)));
        row.setVerifiedAt(LocalDateTime.ofInstant(facts.observedAt(), ZoneOffset.UTC));
        row.setObservedAt(now());
        row.setState("BLOCKED".equals(facts.executionState()) ? "BLOCKED" : "READY");
        row.setFailureCode("READY".equals(row.getState()) ? null : "SOURCE_OWNER_BLOCKED");
        persist(row, actorNo);
        return new Observed(row, facts, ticket);
    }

    /**
     * Only stable line identity and quantities persist; financial fields belong to a fresh owner
     * response.
     */
    public record QuantityLine(
            String sourceLineNo,
            String clientLineRef,
            String goodsNo,
            String skuNo,
            int orderedQuantity,
            int refundableQuantity,
            String uom) {}

    public static List<QuantityLine> quantityLines(CheckoutPort.CheckoutSourceFacts facts) {
        return facts.lines().stream()
                .map(
                        line ->
                                new QuantityLine(
                                        line.sourceLineNo(),
                                        line.clientLineRef(),
                                        line.goodsNo(),
                                        line.skuNo(),
                                        line.orderedQuantity(),
                                        line.refundableQuantity(),
                                        line.uom()))
                .sorted(Comparator.comparing(QuantityLine::sourceLineNo))
                .toList();
    }

    private enum Kind {
        COMPLETE,
        UNKNOWN,
        INVALID
    }

    private Kind classify(
            Canonical source, BtySourceObservation row, CheckoutPort.CheckoutSourceFacts facts) {
        boolean same =
                facts != null
                        && new CheckoutPort.OperationRef("BEAUTY", source.operationNo())
                                .equals(facts.operation())
                        && source.orderNo().equals(facts.orderNo())
                        && source.sourceNo().equals(facts.subOrderNo())
                        && source.buyerNo().equals(facts.buyerNo())
                        && source.entityNo().equals(facts.entityNo())
                        && source.storeNo().equals(facts.storeNo());
        if (!same || facts.observedAt() == null) {
            return Kind.INVALID;
        }
        boolean pair =
                facts.sourceRevision() > 0
                        && facts.sourceHash() != null
                        && facts.sourceHash().matches("[0-9a-f]{64}");
        if ("UNKNOWN".equals(facts.financialState())) {
            return (pair || facts.sourceRevision() == 0 && facts.sourceHash() == null)
                            && "BLOCKED".equals(facts.executionState())
                            && facts.lines().isEmpty()
                            && facts.payments().isEmpty()
                            && facts.receivedMinor() == null
                            && facts.outstandingMinor() == null
                    ? Kind.UNKNOWN
                    : Kind.INVALID;
        }
        if (!pair
                || facts.lines().isEmpty()
                || facts.lines().size() > 20
                || facts.dueMinor() < 0
                || facts.receivedMinor() == null
                || facts.receivedMinor() < 0
                || facts.outstandingMinor() == null
                || facts.outstandingMinor() < 0
                || facts.currency() == null
                || !facts.currency().matches("[A-Z]{3}")
                || !Set.of("PAID", "PARTIALLY_REFUNDED", "REFUNDED")
                        .contains(facts.financialState())
                || !Set.of("NOT_STARTED", "BLOCKED").contains(facts.executionState())) {
            return Kind.INVALID;
        }
        if (!ServiceSourceFacts.money(facts).complete()) {
            return Kind.INVALID;
        }
        var lines = new HashMap<String, CheckoutPort.SourceLine>();
        for (var line : facts.lines()) {
            if (line == null
                    || !reference(line.sourceLineNo())
                    || !reference(line.clientLineRef())
                    || !reference(line.goodsNo())
                    || !reference(line.skuNo())
                    || !"COUNT".equals(line.uom())
                    || line.orderedQuantity() <= 0
                    || line.refundableQuantity() < 0
                    || line.refundableQuantity() > line.orderedQuantity()
                    || line.dueMinor() < 0
                    || lines.putIfAbsent(line.sourceLineNo(), line) != null) {
                return Kind.INVALID;
            }
        }
        if (row.getVerifiedLines() != null) {
            var previous = json.readValue(row.getVerifiedLines(), QuantityLine[].class);
            if (previous.length != lines.size()) {
                return Kind.INVALID;
            }
            for (var before : previous) {
                var after = lines.get(before.sourceLineNo());
                if (after == null
                        || !before.clientLineRef().equals(after.clientLineRef())
                        || !before.goodsNo().equals(after.goodsNo())
                        || !before.skuNo().equals(after.skuNo())
                        || !before.uom().equals(after.uom())
                        || before.orderedQuantity() != after.orderedQuantity()) {
                    return Kind.INVALID;
                }
            }
        }
        return Kind.COMPLETE;
    }

    private boolean reference(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= 64
                && value.equals(value.strip());
    }

    private void highest(BtySourceObservation row, CheckoutPort.CheckoutSourceFacts facts) {
        row.setSourceRevision(facts.sourceRevision());
        row.setSourceHash(facts.sourceHash());
    }

    private void block(BtySourceObservation row, String reason) {
        row.setState("BLOCKED");
        row.setFailureCode(reason);
    }

    private void identity(Canonical source, BtySourceObservation row) {
        if (row == null
                || !"MAIN".equals(row.getTenantNo())
                || !"STORE_ORDER".equals(row.getSourceKind())
                || !"BEAUTY".equals(row.getCheckoutCaller())
                || !source.entityNo().equals(row.getEntityNo())
                || !source.storeNo().equals(row.getStoreNo())
                || !source.buyerNo().equals(row.getBuyerNo())
                || !source.operationNo().equals(row.getCheckoutOperationNo())
                || !source.orderNo().equals(row.getOrderNo())
                || !source.sourceNo().equals(row.getSourceNo())
                || !Objects.equals(row.getDeleted(), 0)) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private void persist(BtySourceObservation row, String actorNo) {
        row.setUpdatedAt(now());
        row.setUpdatedBy(actorNo);
        long version = row.getVersion();
        if (mapper.completeObservation(row, version) != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        row.setVersion(Math.addExact(version, 1));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private <T> T tx(Supplier<T> body) {
        return DataScopeContext.executeWithoutScope(() -> writes.execute(status -> body.get()));
    }

    /** Shared owner calls reject a caller transaction; do not suspend one and hide its locks. */
    public static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Source owner IO requires no caller transaction");
        }
    }

    /** Manual provenance is exact owner identity; a claimed reader never rewrites this party. */
    public record ManualCanonical(
            String entityNo,
            String storeNo,
            CustomerParty party,
            String operationNo,
            String orderNo,
            String sourceNo) {
        public ManualCanonical {
            Objects.requireNonNull(party);
            new Canonical(
                    entityNo,
                    storeNo,
                    party.userNo() == null ? party.guestNo() : party.userNo(),
                    operationNo,
                    orderNo,
                    sourceNo);
        }
    }

    public record ManualObserved(BtySourceObservation row, ManualSourceFacts facts, long ticket) {
        public boolean manualReady() {
            return row != null
                    && facts != null
                    && "MANUAL_OFFLINE".equals(row.getAdmissionKind())
                    && "READY".equals(row.getManualState())
                    && Objects.equals(row.getManualEpoch(), ticket)
                    && Objects.equals(row.getManualRevision(), row.getManualVerifiedRevision())
                    && Objects.equals(row.getManualHash(), row.getManualVerifiedHash())
                    && Objects.equals(row.getManualRevision(), facts.manualObservationRevision())
                    && Objects.equals(row.getManualHash(), facts.manualObservationHash())
                    && "ADMITTED".equals(facts.admissionState());
        }
    }

    public record PlaceholderTicket(long financialEpoch, long rowVersion) {}

    public record ManualQuantityLine(
            String sourceLineNo,
            String clientLineRef,
            String goodsNo,
            String skuNo,
            int orderedQuantity,
            int availableQuantity,
            String uom) {}

    public static List<ManualQuantityLine> manualQuantityLines(ManualSourceFacts facts) {
        return facts.lines().stream()
                .map(
                        line ->
                                new ManualQuantityLine(
                                        line.sourceLineNo(),
                                        line.clientLineRef(),
                                        line.goodsNo(),
                                        line.skuNo(),
                                        line.orderedQuantity(),
                                        line.availableQuantity(),
                                        line.uom()))
                .sorted(Comparator.comparing(ManualQuantityLine::sourceLineNo))
                .toList();
    }

    public static CustomerParty party(BtySourceObservation row) {
        return new CustomerParty(
                row.getCustomerKind(),
                row.getBuyerNo(),
                row.getGuestNo(),
                row.getIdentityRevision());
    }

    /**
     * Unknown is committed before owner IO; original financial history is never reused as manual
     * evidence.
     */
    public ManualObserved observeManual(ManualCanonical source, String actor) {
        outsideTransaction();
        var before =
                tx(() -> mapper.selectCanonicalForUpdate(source.entityNo(), source.sourceNo()));
        PlaceholderTicket placeholder;
        try {
            placeholder =
                    before != null && "LEGACY_FUNDED".equals(before.getAdmissionKind())
                            ? claimManualPlaceholder(source, actor)
                            : null;
        } catch (BizException race) {
            if (race.errorCode() != ErrorCode.CONFLICT) throw race;
            var winner = manualHistory(source);
            if (winner == null || !"MANUAL_OFFLINE".equals(winner.getAdmissionKind())) throw race;
            // Another adopter won between the initial read and claim; no placeholder ticket was
            // issued.
            return observeManual(source, actor);
        }
        long ticket = placeholder == null ? claimManual(source, actor) : -1;
        var owner = manual == null ? null : manual.getIfAvailable();
        if (owner == null) {
            return new ManualObserved(manualHistory(source), null, ticket);
        }
        ManualSourceFacts facts;
        try {
            facts =
                    owner.source(
                            new CheckoutPort.CheckoutScope(
                                    "MAIN", source.entityNo(), source.storeNo(), actor),
                            new CheckoutPort.SourceLookup(
                                    new CheckoutPort.OperationRef("BEAUTY", source.operationNo()),
                                    source.sourceNo()));
        } catch (RuntimeException unavailable) {
            return new ManualObserved(manualHistory(source), null, ticket);
        }
        if (placeholder != null) {
            try {
                var adopted = adoptManualPlaceholder(source, facts, placeholder, actor);
                ticket = adopted.getManualEpoch();
            } catch (BizException lostAdoption) {
                if (lostAdoption.errorCode() != ErrorCode.CONFLICT) throw lostAdoption;
                var winner = manualHistory(source);
                if (winner == null || !"MANUAL_OFFLINE".equals(winner.getAdmissionKind()))
                    throw lostAdoption;
                // Reload the same winner, issue a fresh manual ticket and make a fresh owner read.
                return observeManual(source, actor);
            }
        }
        long issued = ticket;
        return tx(() -> completeManual(source, facts, issued, actor));
    }

    /**
     * Scoped operation history is only a canonical reference; it grants no readiness or identity
     * authority.
     */
    public ManualCanonical manualKnown(
            String entity, String store, CustomerParty party, String operation) {
        outsideTransaction();
        return DataScopeContext.executeWithoutScope(
                () -> {
                    var rows =
                            mapper.selectList(
                                    Wrappers.<BtySourceObservation>lambdaQuery()
                                            .eq(BtySourceObservation::getTenantNo, "MAIN")
                                            .eq(BtySourceObservation::getEntityNo, entity)
                                            .eq(BtySourceObservation::getStoreNo, store)
                                            .eq(BtySourceObservation::getCheckoutCaller, "BEAUTY")
                                            .eq(
                                                    BtySourceObservation::getCheckoutOperationNo,
                                                    operation));
                    if (rows.isEmpty()) return null;
                    if (rows.size() != 1) throw BizException.of(ErrorCode.CONFLICT);
                    var row = rows.getFirst();
                    var canonical =
                            new ManualCanonical(
                                    entity,
                                    store,
                                    party,
                                    operation,
                                    row.getOrderNo(),
                                    row.getSourceNo());
                    manualIdentity(canonical, row);
                    return canonical;
                });
    }

    public BtySourceObservation manualHistory(ManualCanonical source) {
        return DataScopeContext.executeWithoutScope(
                () -> {
                    var row =
                            mapper.selectCanonical(
                                    source.entityNo(), source.storeNo(), source.sourceNo());
                    if (row != null) {
                        manualIdentity(source, row);
                    }
                    return row;
                });
    }

    public BtySourceObservation invalidateManual(ManualCanonical source, String actor) {
        outsideTransaction();
        return tx(() -> {
            long ticket = claimManualLocked(source, actor);
            var row = mapper.selectCanonicalForUpdate(source.entityNo(), source.sourceNo());
            manualIdentity(source, row);
            if (!Objects.equals(row.getManualEpoch(), ticket)) throw BizException.of(ErrorCode.CONFLICT);
            // Freshness only: no durable contradiction or coordinator hook is minted by a read claim.
            return row;
        });
    }

    private long claimManual(ManualCanonical source, String actor) {
        try {
            return tx(() -> claimManualLocked(source, actor));
        } catch (DuplicateKeyException collision) {
            return tx(() -> claimManualLocked(source, actor));
        }
    }

    private long claimManualLocked(ManualCanonical source, String actor) {
        var row = mapper.selectCanonicalForUpdate(source.entityNo(), source.sourceNo());
        if (row == null) {
            row = new BtySourceObservation();
            row.setTenantNo("MAIN");
            row.setEntityNo(source.entityNo());
            row.setStoreNo(source.storeNo());
            row.setSourceKind("STORE_ORDER");
            row.setSourceNo(source.sourceNo());
            row.setCheckoutCaller("BEAUTY");
            row.setCheckoutOperationNo(source.operationNo());
            row.setOrderNo(source.orderNo());
            row.setBuyerNo(source.party().userNo());
            row.setCustomerKind(source.party().kind());
            row.setGuestNo(source.party().guestNo());
            row.setIdentityRevision(source.party().identityRevision());
            row.setAdmissionKind("MANUAL_OFFLINE");
            row.setObservationEpoch(0L);
            row.setState("UNKNOWN");
            row.setFailureCode("MANUAL_OBSERVATION");
            row.setManualEpoch(1L);
            row.setManualState("UNKNOWN");
            row.setManualFailureCode("SOURCE_OBSERVATION_IN_FLIGHT");
            row.setVersion(0L);
            row.setDeleted(0);
            row.setCreatedAt(now());
            row.setUpdatedAt(now());
            row.setCreatedBy(actor);
            row.setUpdatedBy(actor);
            mapper.insert(row);
            return 1;
        }
        manualIdentity(source, row);
        if (!"MANUAL_OFFLINE".equals(row.getAdmissionKind())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        row.setUpdatedAt(now());
        row.setUpdatedBy(actor);
        if (mapper.claimManualObservation(row, row.getVersion()) != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return Math.addExact(row.getManualEpoch(), 1);
    }

    public PlaceholderTicket claimManualPlaceholder(ManualCanonical source, String actor) {
        outsideTransaction();
        return tx(
                () -> {
                    var row = mapper.selectCanonicalForUpdate(source.entityNo(), source.sourceNo());
                    manualIdentity(source, row);
                    eligible(row);
                    row.setObservationEpoch(Math.addExact(row.getObservationEpoch(), 1));
                    row.setObservedAt(null);
                    row.setFailureCode("SOURCE_OBSERVATION_IN_FLIGHT");
                    persist(row, actor);
                    return new PlaceholderTicket(row.getObservationEpoch(), row.getVersion());
                });
    }

    public BtySourceObservation adoptManualPlaceholder(
            ManualCanonical source,
            ManualSourceFacts facts,
            PlaceholderTicket ticket,
            String actor) {
        outsideTransaction();
        validManual(source, facts);
        return tx(
                () -> {
                    var row = mapper.selectCanonicalForUpdate(source.entityNo(), source.sourceNo());
                    manualIdentity(source, row);
                    if ("MANUAL_OFFLINE".equals(row.getAdmissionKind())) {
                        throw BizException.of(ErrorCode.CONFLICT);
                    }
                    eligible(row);
                    if (row.getVersion() != ticket.rowVersion()
                            || row.getObservationEpoch() != ticket.financialEpoch()) {
                        throw BizException.of(ErrorCode.CONFLICT);
                    }
                    row.setCustomerKind(facts.party().kind());
                    row.setGuestNo(facts.party().guestNo());
                    row.setIdentityRevision(facts.party().identityRevision());
                    row.setUpdatedAt(now());
                    row.setUpdatedBy(actor);
                    if (mapper.adoptManualPlaceholder(
                                    row, ticket.rowVersion(), ticket.financialEpoch())
                            != 1) {
                        throw BizException.of(ErrorCode.CONFLICT);
                    }
                    return mapper.selectCanonicalForUpdate(source.entityNo(), source.sourceNo());
                });
    }

    private void eligible(BtySourceObservation row) {
        boolean empty =
                row != null
                        && "LEGACY_FUNDED".equals(row.getAdmissionKind())
                        && "UNKNOWN".equals(row.getState())
                        && row.getSourceRevision() == null
                        && row.getSourceHash() == null
                        && row.getVerifiedRevision() == null
                        && row.getVerifiedHash() == null
                        && row.getVerifiedLines() == null
                        && row.getVerifiedAt() == null
                        && row.getCustomerKind() == null
                        && row.getGuestNo() == null
                        && row.getIdentityRevision() == null
                        && row.getManualEpoch() == null
                        && row.getManualState() == null
                        && row.getManualRevision() == null
                        && row.getManualHash() == null
                        && row.getManualVerifiedRevision() == null
                        && row.getManualVerifiedHash() == null
                        && row.getManualVerifiedLines() == null
                        && row.getManualVerifiedAt() == null
                        && row.getManualObservedAt() == null
                        && row.getManualFailureCode() == null
                        && row.getManualAdmissionRevision() == null
                        && row.getManualAdmissionState() == null;
        if (!empty || mapper.hasAllocations(row.getEntityNo(), row.getSourceNo())) {
            throw new SourceDomainConflict();
        }
    }

    /** Internal source diagnostic; the existing HTTP CONFLICT response remains unchanged. */
    public static final class SourceDomainConflict extends BizException {
        private SourceDomainConflict() {
            super(ErrorCode.CONFLICT);
        }

        /**
         * Identifies the rejected provenance internally without extending the HTTP error contract.
         */
        public String reasonCode() {
            return "SOURCE_DOMAIN_CONFLICT";
        }
    }

    private void manualIdentity(ManualCanonical source, BtySourceObservation row) {
        if (row == null
                || !"MAIN".equals(row.getTenantNo())
                || !source.entityNo().equals(row.getEntityNo())
                || !source.storeNo().equals(row.getStoreNo())
                || !source.sourceNo().equals(row.getSourceNo())
                || !"STORE_ORDER".equals(row.getSourceKind())
                || !"BEAUTY".equals(row.getCheckoutCaller())
                || !source.operationNo().equals(row.getCheckoutOperationNo())
                || !source.orderNo().equals(row.getOrderNo())
                || !Objects.equals(row.getDeleted(), 0)) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if ("LEGACY_FUNDED".equals(row.getAdmissionKind())) {
            if (!"USER".equals(source.party().kind())
                    || !source.party().userNo().equals(row.getBuyerNo())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
        } else if (!source.party().equals(party(row))) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private void validManual(ManualCanonical source, ManualSourceFacts facts) {
        if (facts == null
                || !new CheckoutPort.OperationRef("BEAUTY", source.operationNo())
                        .equals(facts.operation())
                || !source.entityNo().equals(facts.entityNo())
                || !source.storeNo().equals(facts.storeNo())
                || !source.orderNo().equals(facts.orderNo())
                || !source.sourceNo().equals(facts.subOrderNo())
                || !source.party().equals(facts.party())
                || !"MANUAL_OFFLINE".equals(facts.admissionKind())
                || facts.purchaseAuthority() == null
                || !Set.of("CONSUMER_SESSION", "MERCHANT_RECORDED_IN_PERSON")
                        .contains(facts.purchaseAuthority())
                || "CONSUMER_SESSION".equals(facts.purchaseAuthority())
                        && !"USER".equals(facts.party().kind())
                || facts.observedAt() == null
                || facts.lines().size() > 20) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private ManualObserved completeManual(
            ManualCanonical source, ManualSourceFacts facts, long ticket, String actor) {
        var row = mapper.selectCanonicalForUpdate(source.entityNo(), source.sourceNo());
        manualIdentity(source, row);
        if (!"MANUAL_OFFLINE".equals(row.getAdmissionKind())
                || !Objects.equals(row.getManualEpoch(), ticket)) {
            return new ManualObserved(row, null, ticket);
        }
        if (facts == null || "UNKNOWN".equals(facts.admissionState())) {
            // UNKNOWN may omit canonical detail or a current revision; neither is a contrary owner receipt.
            row.setManualAdmissionState("UNKNOWN");
            row.setManualState("UNKNOWN");
            row.setManualFailureCode("SOURCE_OWNER_UNKNOWN");
            row.setManualObservedAt(now());
            persistManual(row, actor, facts);
            return new ManualObserved(row, null, ticket);
        }
        try {
            validManual(source, facts);
        } catch (RuntimeException invalid) {
            row.setManualState("BLOCKED");
            row.setManualFailureCode("SOURCE_CONTRACT_INVALID");
            row.setManualObservedAt(now());
            persistManual(row, actor, facts);
            return new ManualObserved(row, null, ticket);
        }
        Long highest = row.getManualRevision();
        long revision = facts.manualObservationRevision();
        boolean conflict =
                highest != null
                        && revision == highest
                        && !Objects.equals(row.getManualHash(), facts.manualObservationHash());
        if (highest != null && revision < highest || conflict) {
            row.setManualState("BLOCKED");
            row.setManualFailureCode(
                    conflict ? "SOURCE_HASH_CONFLICT" : "SOURCE_REVISION_REGRESSION");
            row.setManualObservedAt(now());
            persistManual(row, actor, facts);
            return new ManualObserved(row, null, ticket);
        }
        if (revision > 0) {
            row.setManualRevision(revision);
            row.setManualHash(facts.manualObservationHash());
        }
        row.setManualAdmissionRevision(facts.admissionRevision());
        row.setManualAdmissionState(facts.admissionState());
        row.setManualObservedAt(now());
        if (!"ADMITTED".equals(facts.admissionState())) {
            row.setManualState("UNKNOWN");
            row.setManualFailureCode("SOURCE_OWNER_" + facts.admissionState());
            persistManual(row, actor, facts);
            return new ManualObserved(row, facts, ticket);
        }
        var quantities = manualQuantityLines(facts);
        if (row.getManualVerifiedLines() != null) {
            var old = json.readValue(row.getManualVerifiedLines(), ManualQuantityLine[].class);
            boolean same =
                    old.length == quantities.size()
                            && Arrays.stream(old)
                                    .allMatch(
                                            before -> {
                                                return quantities.stream()
                                                        .anyMatch(
                                                                after -> {
                                                                    return before.sourceLineNo()
                                                                                    .equals(
                                                                                            after
                                                                                                    .sourceLineNo())
                                                                            && before.clientLineRef()
                                                                                    .equals(
                                                                                            after
                                                                                                    .clientLineRef())
                                                                            && before.goodsNo()
                                                                                    .equals(
                                                                                            after
                                                                                                    .goodsNo())
                                                                            && before.skuNo()
                                                                                    .equals(
                                                                                            after
                                                                                                    .skuNo())
                                                                            && before.uom()
                                                                                    .equals(
                                                                                            after
                                                                                                    .uom())
                                                                            && before
                                                                                            .orderedQuantity()
                                                                                    == after
                                                                                            .orderedQuantity();
                                                                });
                                            });
            if (!same) {
                row.setManualState("BLOCKED");
                row.setManualFailureCode("SOURCE_LINE_MISMATCH");
                persistManual(row, actor, facts);
                return new ManualObserved(row, null, ticket);
            }
        }
        row.setManualVerifiedRevision(revision);
        row.setManualVerifiedHash(facts.manualObservationHash());
        row.setManualVerifiedLines(json.writeValueAsString(quantities));
        row.setManualVerifiedAt(LocalDateTime.ofInstant(facts.observedAt(), ZoneOffset.UTC));
        row.setManualState("READY");
        row.setManualFailureCode(null);
        persistManual(row, actor, facts);
        return new ManualObserved(row, facts, ticket);
    }

    private void persistManual(BtySourceObservation row, String actor, ManualSourceFacts facts) {
        row.setUpdatedAt(now());
        row.setUpdatedBy(actor);
        long version = row.getVersion();
        if (mapper.completeManualObservation(row, version) != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        row.setVersion(Math.addExact(version, 1));
        executionFences.onManualCompletionLocked(row, facts, actor);
    }

    public SourceObservationVO manualProjection(BtySourceObservation row, boolean current) {
        if (row == null
                || !"MANUAL_OFFLINE".equals(row.getAdmissionKind())
                || row.getManualEpoch() == null) {
            return null;
        }
        return new SourceObservationVO(
                row.getState(),
                row.getManualEpoch(),
                row.getSourceRevision(),
                row.getSourceHash(),
                row.getVerifiedRevision(),
                row.getVerifiedHash(),
                row.getManualObservedAt() == null
                        ? null
                        : row.getManualObservedAt().toInstant(ZoneOffset.UTC),
                current ? row.getManualFailureCode() : "SOURCE_UNAVAILABLE",
                false,
                row.getAdmissionKind(),
                current ? row.getManualState() : "UNKNOWN",
                row.getManualRevision(),
                row.getManualHash());
    }
}
