package ai.neargo.shop.beauty.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.beauty.dto.SourceObservationVO;
import ai.neargo.shop.beauty.entity.BtySourceObservation;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.ServiceSourceFacts;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.CheckoutPort;

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
    private final TransactionTemplate writes;
    private final ObjectMapper json;
    private final Clock clock;

    public ServiceSourceObservation(
            BeautyMappers.SourceObservationMapper mapper,
            ObjectProvider<CheckoutPort> checkout,
            PlatformTransactionManager manager,
            ObjectMapper json,
            Clock clock) {
        this.mapper = mapper;
        this.checkout = checkout;
        this.json = json;
        this.clock = clock;
        writes = new TransactionTemplate(manager);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
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
                                        .eq(BtySourceObservation::getSourceKind, "STORE_ORDER")));
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
}
