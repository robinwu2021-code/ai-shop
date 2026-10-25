package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingFeeEngine;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.PricingUnit;
import ai.neargo.shop.common.pricing.TaxClass;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Allocation;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Application;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Definition;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Kind;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Method;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Plan;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Resolved;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Rule;
import ai.neargo.shop.spi.trade.PricingFeeFacts.ScopeBasis;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Source;
import ai.neargo.shop.spi.trade.PricingFeeScopePort.AdjustmentAuthority;
import ai.neargo.shop.spi.trade.PricingSourceFacts;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.trade.entity.PricingFeeEvidenceRow;
import ai.neargo.shop.trade.mapper.PricingFeeRuleMapper;
import ai.neargo.shop.trade.service.PricingFeeRuleService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class PricingFeeRuleServiceImpl
        implements PricingFeeRuleService {
    private final PricingFeeRuleMapper mapper;
    private final ObjectMapper json;

    public PricingFeeRuleServiceImpl(PricingFeeRuleMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public Rule save(Scope scope, String actor, String no, SaveReq req, String key) {
        PricingSourceFacts.key(actor);
        PricingSourceFacts.key(no);
        if (key == null || key.isBlank() || key.length() > 128) bad();
        if (req == null
                || req.definition() == null
                || req.reason() == null
                || req.reason().isBlank()
                || req.reason().length() > 255
                || !Set.of("ACTIVE", "DISABLED").contains(req.status())) bad();
        validate(req.definition());
        String keyHash = PricingSnapshotHash.of(key),
                hash = PricingSnapshotHash.of(List.of(no, req, actor));
        var old = mapper.command(scope.entityNo(), scope.storeNo(), keyHash);
        if (!old.isEmpty()) {
            if (!hash.equals(old.getFirst().get("request_hash"))
                    || !actor.equals(old.getFirst().get("actor_no"))) conflict();
            return revision(scope, old.getFirst(), req.status());
        }
        var current = mapper.rule(scope.entityNo(), scope.storeNo(), no, true);
        if (current.isEmpty() && req.expectedVersion() != -1
                || !current.isEmpty()
                        && number(current.getFirst(), "version") != req.expectedVersion())
            conflict();
        String revision = BizKey.next("PFEE");
        long version = req.expectedVersion() + 1;
        if (current.isEmpty())
            mapper.insertRule(scope.entityNo(), scope.storeNo(), no, revision, req.status(), actor);
        else if (mapper.updateRule(
                        scope.entityNo(),
                        scope.storeNo(),
                        no,
                        revision,
                        req.status(),
                        req.expectedVersion(),
                        actor)
                != 1) conflict();
        mapper.insertRevision(
                scope.entityNo(),
                scope.storeNo(),
                no,
                revision,
                json.writeValueAsString(req.definition()),
                keyHash,
                hash,
                actor,
                version,
                req.reason());
        return new Rule(scope, no, revision, version, req.status(), req.definition());
    }

    @Override
    public Rule get(Scope scope, String no) {
        var root = one(mapper.rule(scope.entityNo(), scope.storeNo(), no, false));
        return revision(
                scope,
                one(
                        mapper.revision(
                                scope.entityNo(),
                                scope.storeNo(),
                                (String) root.get("revision_no"))),
                (String) root.get("status"));
    }

    @Override
    public Plan resolve(Snapshot source) {
        if (source.fees() == null || source.fees().isEmpty()) return null;
        var result = new ArrayList<Resolved>();
        var scope = source.scope();
        for (var fee :
                source.fees().stream()
                        .sorted(Comparator.comparing(Source::scopeNo).thenComparing(Source::ruleNo))
                        .toList()) {
            Rule rule = get(scope, fee.ruleNo());
            if (!fee.revisionNo().equals(rule.revisionNo())) conflict();
            var rows =
                    mapper.scope(
                            scope.entityNo(), scope.storeNo(), fee.scopeNo(), fee.ruleNo(), false);
            var basis =
                    rows.isEmpty()
                            ? new ScopeBasis(
                                    fee.scopeNo(),
                                    fee.ruleNo(),
                                    source.prices().currency(),
                                    0,
                                    0,
                                    -1)
                            : basis(rows.getFirst());
            var applications =
                    mapper
                            .applications(
                                    scope.entityNo(), scope.storeNo(), fee.scopeNo(), fee.ruleNo())
                            .stream()
                            .map(this::application)
                            .toList();
            result.add(
                    new PricingFeeEngine()
                            .calculate(source.prices(), fee, rule, basis, applications));
        }
        return new Plan(result, PricingSnapshotHash.of(result));
    }

    @Override
    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    public void applyPlan(
            Scope scope, String actor, String order, String sub, String quote, Plan plan) {
        if (plan == null || plan.fees().isEmpty()) return;
        if (!PricingSnapshotHash.of(plan.fees()).equals(plan.planHash())) conflict();
        var existing = mapper.quoteApplications(scope.entityNo(), scope.storeNo(), quote);
        if (!existing.isEmpty()) {
            if (existing.size() != plan.fees().size()
                    || existing.stream()
                            .anyMatch(
                                    a ->
                                            !plan.planHash().equals(a.get("source_hash"))
                                                    || !order.equals(a.get("order_no"))
                                                    || !sub.equals(a.get("sub_order_no"))))
                conflict();
            return;
        }
        for (var fee : plan.fees()) {
            if (!scope.equals(fee.rule().scope())
                    || !fee.source()
                            .revisionNo()
                            .equals(get(scope, fee.source().ruleNo()).revisionNo())) conflict();
            mapper.createScope(
                    scope.entityNo(),
                    scope.storeNo(),
                    fee.source().scopeNo(),
                    fee.source().ruleNo(),
                    fee.rule().definition().currency());
            var current =
                    basis(
                            one(
                                    mapper.scope(
                                            scope.entityNo(),
                                            scope.storeNo(),
                                            fee.source().scopeNo(),
                                            fee.source().ruleNo(),
                                            true)));
            long expected = fee.previous().version() < 0 ? 0 : fee.previous().version();
            if (current.version() != expected
                    || current.goodsBasisMinor() != fee.previous().goodsBasisMinor()
                    || current.feeMinor() != fee.previous().feeMinor()) conflict();
            String app = BizKey.next("PFFA");
            mapper.application(
                    app,
                    scope.entityNo(),
                    scope.storeNo(),
                    order,
                    sub,
                    quote,
                    fee.rule().ruleNo(),
                    fee.rule().revisionNo(),
                    fee.source().scopeNo(),
                    fee.rule().definition().currency(),
                    fee.additionalFeeMinor(),
                    plan.planHash());
            mapper.initializeContribution(
                    app, fee.currentGoodsBasisMinor(), fee.source().quantity());
            if (mapper.updateScope(
                            scope.entityNo(),
                            scope.storeNo(),
                            fee.source().scopeNo(),
                            fee.source().ruleNo(),
                            current.version(),
                            fee.currentGoodsBasisMinor(),
                            fee.additionalFeeMinor())
                    != 1) conflict();
            mapper.supersedeAdvice(
                    scope.entityNo(),
                    scope.storeNo(),
                    fee.source().scopeNo(),
                    fee.source().ruleNo());
            for (var correction : fee.corrections())
                mapper.advice(
                        scope.entityNo(),
                        scope.storeNo(),
                        correction.applicationNo(),
                        quote,
                        correction.currentFeeMinor(),
                        correction.targetFeeMinor(),
                        correction.sourceHash());
        }
    }

    @Override
    public IndustryFulfillmentPort.Reference originalReference(Scope scope, String app) {
        var row = one(mapper.originalReference(scope.entityNo(), scope.storeNo(), app));
        return new IndustryFulfillmentPort.Reference(
                scope,
                new PricingSourceRef(
                        (String) row.get("source_system"),
                        (String) row.get("source_type"),
                        (String) row.get("source_no"),
                        number(row, "source_version"),
                        null,
                        null),
                (String) row.get("order_no"),
                (String) row.get("sub_order_no"));
    }

    @Override
    public List<AdjustmentAuthority> pendingAdjustments(Scope scope, String scopeNo) {
        PricingSourceFacts.key(scopeNo);
        return mapper.pendingAdjustments(scope.entityNo(), scope.storeNo(), scopeNo).stream()
                .map(
                        r ->
                                new AdjustmentAuthority(
                                        (String) r.get("application_no"),
                                        (String) r.get("order_no"),
                                        (String) r.get("sub_order_no"),
                                        (String) r.get("currency"),
                                        number(r, "current_fee_minor"),
                                        number(r, "target_fee_minor"),
                                        (String) r.get("advice_hash")))
                .toList();
    }

    @Override
    public AdjustmentAuthority adjustment(Scope scope, String scopeNo, String app, String hash) {
        var row = one(mapper.adjustment(scope.entityNo(), scope.storeNo(), app, hash, false));
        if (!scopeNo.equals(row.get("scope_no"))
                || !Set.of("OPEN", "APPLIED").contains(row.get("advice_status"))) conflict();
        // An applied immutable advice stays readable for original-key HTTP replay.
        // New mutations still require OPEN in authorizeAdjustment, inside the CORE transaction.
        return new AdjustmentAuthority(
                app,
                (String) row.get("order_no"),
                (String) row.get("sub_order_no"),
                (String) row.get("currency"),
                number(row, "current_fee_minor"),
                number(row, "target_fee_minor"),
                hash);
    }

    @Override
    public AdjustmentAuthority authorizeAdjustment(
            Scope scope, String app, long target, String hash) {
        var row = one(mapper.adjustment(scope.entityNo(), scope.storeNo(), app, hash, false));
        if (number(row, "target_fee_minor") != target || !"OPEN".equals(row.get("advice_status")))
            conflict();
        return new AdjustmentAuthority(
                app,
                (String) row.get("order_no"),
                (String) row.get("sub_order_no"),
                (String) row.get("currency"),
                number(row, "current_fee_minor"),
                target,
                hash);
    }

    @Override
    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    public void completeAdjustment(
            Scope scope, String app, long target, String hash, String actor, String key) {
        var row = one(mapper.adjustment(scope.entityNo(), scope.storeNo(), app, hash, true));
        if (number(row, "target_fee_minor") != target) conflict();
        if ("APPLIED".equals(row.get("advice_status"))) return;
        long current = number(row, "current_fee_minor");
        if (mapper.adjustApplication(scope.entityNo(), scope.storeNo(), app, current, target) != 1
                || mapper.reduceScope(
                                scope.entityNo(),
                                scope.storeNo(),
                                (String) row.get("scope_no"),
                                (String) row.get("rule_no"),
                                current - target)
                        != 1
                || mapper.completeAdvice(scope.entityNo(), scope.storeNo(), app, hash) != 1)
            conflict();
    }

    @Override
    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    public void reconcileOnAmendment(
            IndustryFulfillmentPort.Reference ref, String amendment, String sourceHash) {
        var evidence = one(mapper.amendmentSource(ref, amendment, sourceHash));
        List<Allocation> allocations =
                ai.neargo.shop.trade.policy.IndustryOrderAllocationPolicy.read(json, (String) evidence.get("allocations_json"));
        var reductions = mapper.confirmedReductions(ref);
        for (var application : mapper.orderApplications(ref)) {
            String app = (String) application.get("application_no");
            var replay = mapper.reconciliation(ref.scope().entityNo(), app, amendment);
            if (!replay.isEmpty()) {
                if (!sourceHash.equals(replay.getFirst().get("source_hash"))) conflict();
                continue;
            }
            String scopeNo = (String) application.get("scope_no"),
                    ruleNo = (String) application.get("rule_no");
            var locked =
                    basis(
                            one(
                                    mapper.scope(
                                            ref.scope().entityNo(),
                                            ref.scope().storeNo(),
                                            scopeNo,
                                            ruleNo,
                                            true)));
            var result = json.readTree((String) application.get("result_snapshot"));
            var plan = json.treeToValue(result.get("feePlan"), Plan.class);
            if (plan == null || !PricingSnapshotHash.of(plan.fees()).equals(plan.planHash()))
                conflict();
            var frozen =
                    plan.fees().stream()
                            .filter(
                                    f ->
                                            f.source().scopeNo().equals(scopeNo)
                                                    && f.rule().ruleNo().equals(ruleNo))
                            .findFirst()
                            .orElseThrow(() -> BizException.of(ErrorCode.CONFLICT));
            long reduced = 0;
            BigDecimal sourceQuantity = BigDecimal.ZERO, reducedQuantity = BigDecimal.ZERO;
            for (var line : result.get("lines")) {
                var unit = json.treeToValue(line.get("source"), PricingUnit.class);
                if (!frozen.source().basisSourceRefs().contains(unit.sourceLineNo())) continue;
                var allocation =
                        allocations.stream()
                                .filter(
                                        v ->
                                                v.sourceLineNo().equals(unit.sourceLineNo())
                                                        && Objects.equals(
                                                                v.componentRef(),
                                                                unit.componentRef()))
                                .findFirst()
                                .orElseThrow(() -> BizException.of(ErrorCode.CONFLICT));
                BigDecimal quantity = BigDecimal.ZERO;
                for (var reduction : reductions)
                    if (number(reduction, "order_item_id") == allocation.orderItemId()
                            && Objects.toString(reduction.get("source_component_ref"), "")
                                    .equals(Objects.toString(allocation.componentRef(), "")))
                        quantity = quantity.add((BigDecimal) reduction.get("quantity"));
                if (quantity.signum() < 0 || quantity.compareTo(unit.quantity()) > 0) conflict();
                sourceQuantity = sourceQuantity.add(unit.quantity());
                reducedQuantity = reducedQuantity.add(quantity);
                reduced =
                        Math.addExact(
                                reduced,
                                BigDecimal.valueOf(unit.baseAmount().minor())
                                        .multiply(quantity)
                                        .divide(unit.quantity(), 0, RoundingMode.HALF_UP)
                                        .longValueExact());
            }
            long remaining = Math.subtractExact(frozen.currentGoodsBasisMinor(), reduced);
            var contribution = one(mapper.contribution(app));
            long previous = number(contribution, "current_goods_basis_minor");
            BigDecimal previousQuantity = (BigDecimal) contribution.get("current_quantity");
            BigDecimal remainingQuantity = frozen.source().quantity();
            if (frozen.source().kind() == Kind.HOTPOT) {
                if (sourceQuantity.signum() <= 0) conflict();
                remainingQuantity =
                        frozen.source()
                                .quantity()
                                .multiply(sourceQuantity.subtract(reducedQuantity))
                                .divide(sourceQuantity, 6, RoundingMode.UNNECESSARY)
                                .stripTrailingZeros();
                if (remainingQuantity.signum() < 0
                        || remainingQuantity.compareTo(previousQuantity) > 0) conflict();
            }
            if (remaining < 0 || remaining > previous) conflict();
            long change = Math.subtractExact(remaining, previous);
            if (change == 0 && remainingQuantity.compareTo(previousQuantity) == 0) {
                mapper.recordReconciliation(
                        ref.scope().entityNo(),
                        ref.scope().storeNo(),
                        app,
                        amendment,
                        sourceHash,
                        previous,
                        remaining);
                continue;
            }
            mapper.supersedeAdvice(ref.scope().entityNo(), ref.scope().storeNo(), scopeNo, ruleNo);

            if (mapper.changeGoodsBasis(
                                    ref.scope().entityNo(),
                                    ref.scope().storeNo(),
                                    scopeNo,
                                    ruleNo,
                                    change)
                            != 1
                    || mapper.setContribution(app, remaining) != 1) conflict();
            if (mapper.quantityContribution(app, remainingQuantity) != 1) conflict();
            long basis = Math.addExact(locked.goodsBasisMinor(), change);
            var definition = frozen.rule().definition();
            long target = locked.feeMinor();
            if (frozen.source().waiverEvidence() != null) target = 0;
            else if (definition.method() == Method.PER_QUANTITY
                    && frozen.source().kind() == Kind.HOTPOT)
                target =
                        BigDecimal.valueOf(definition.unitAmountMinor())
                                .multiply(remainingQuantity)
                                .setScale(0, RoundingMode.HALF_UP)
                                .longValueExact();
            else if (definition.method() == Method.MINIMUM)
                target = Math.max(0, Math.subtractExact(definition.unitAmountMinor(), basis));
            else if (definition.method() == Method.PERCENT)
                target =
                        BigDecimal.valueOf(basis)
                                .multiply(BigDecimal.valueOf(definition.rateBp()))
                                .divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP)
                                .longValueExact();
            long delta = Math.subtractExact(target, locked.feeMinor());
            if (delta != 0) {
                var candidates =
                        mapper.applications(
                                ref.scope().entityNo(), ref.scope().storeNo(), scopeNo, ruleNo);
                for (var candidate : candidates) {
                    if (delta == 0) break;
                    long current = number(candidate, "adjusted_fee_minor");
                    long next =
                            delta > 0
                                    ? Math.addExact(current, delta)
                                    : Math.max(0, Math.addExact(current, delta));
                    if (next == current) continue;
                    String hash =
                            PricingSnapshotHash.of(
                                    List.of(
                                            "FEE_AMENDMENT_ADVICE_V1",
                                            amendment,
                                            sourceHash,
                                            candidate.get("application_no"),
                                            current,
                                            next));
                    mapper.advice(
                            ref.scope().entityNo(),
                            ref.scope().storeNo(),
                            (String) candidate.get("application_no"),
                            (String) candidate.get("quote_no"),
                            current,
                            next,
                            hash);
                    delta = Math.subtractExact(delta, Math.subtractExact(next, current));
                }
                if (delta != 0) conflict();
            }
            mapper.recordReconciliation(
                    ref.scope().entityNo(),
                    ref.scope().storeNo(),
                    app,
                    amendment,
                    sourceHash,
                    previous,
                    remaining);
        }
    }

    private Rule revision(Scope scope, PricingFeeEvidenceRow row, String status) {
        return new Rule(
                scope,
                (String) row.get("rule_no"),
                (String) row.get("revision_no"),
                number(row, "version"),
                status,
                json.readValue((String) row.get("definition_snapshot"), Definition.class));
    }

    private ScopeBasis basis(PricingFeeEvidenceRow row) {
        return new ScopeBasis(
                (String) row.get("scope_no"),
                (String) row.get("rule_no"),
                (String) row.get("currency"),
                number(row, "goods_basis_minor"),
                number(row, "fee_minor"),
                number(row, "version"));
    }

    private Application application(PricingFeeEvidenceRow r) {
        return new Application(
                (String) r.get("application_no"),
                (String) r.get("order_no"),
                (String) r.get("sub_order_no"),
                (String) r.get("quote_no"),
                (String) r.get("rule_no"),
                (String) r.get("revision_no"),
                (String) r.get("scope_no"),
                (String) r.get("currency"),
                number(r, "fee_minor"),
                number(r, "adjusted_fee_minor"),
                (String) r.get("source_hash"));
    }

    private static void validate(Definition d) {
        boolean method =
                d.kind() == Kind.TEA || d.kind() == Kind.HOTPOT || d.kind() == Kind.PACKAGING
                        ? d.method() == Method.PER_QUANTITY
                        : d.kind() == Kind.SERVICE
                                ? d.method() == Method.FIXED || d.method() == Method.PERCENT
                                : d.method() == Method.MINIMUM;
        if (!method) bad();
        if (d.kind() == null
                || d.method() == null
                || d.taxClass() == null
                || d.taxClass() == TaxClass.UNKNOWN
                || d.currency() == null
                || !d.currency().matches("[A-Z]{3}")
                || d.unit() == null
                || d.unit().isBlank()
                || d.unitAmountMinor() < 0
                || d.unitAmountMinor() > 9007199254740991L
                || d.method() == Method.PERCENT
                        && (d.rateBp() == null || d.rateBp() < 0 || d.rateBp() > 10000)
                || d.method() != Method.PERCENT && d.rateBp() != null
                || d.kind() == Kind.MINIMUM && d.method() != Method.MINIMUM) bad();
    }

    private static long number(PricingFeeEvidenceRow r, String k) {
        return ((Number) r.get(k)).longValue();
    }

    private static PricingFeeEvidenceRow one(List<PricingFeeEvidenceRow> r) {
        if (r.size() != 1) throw BizException.of(ErrorCode.NOT_FOUND);
        return r.getFirst();
    }

    private static void bad() {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static void conflict() {
        throw BizException.of(ErrorCode.CONFLICT);
    }
}
