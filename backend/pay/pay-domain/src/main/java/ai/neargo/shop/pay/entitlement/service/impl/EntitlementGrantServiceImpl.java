package ai.neargo.shop.pay.entitlement.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.PreparedGrant;
import ai.neargo.shop.pay.entitlement.entity.EntGrant;
import ai.neargo.shop.pay.entitlement.policy.EntitlementRuleValidator;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.entitlement.service.EntitlementCatalogService;
import ai.neargo.shop.pay.entitlement.service.EntitlementGrantService;
import ai.neargo.shop.pay.entitlement.service.EntitlementGrantWriter;
import ai.neargo.shop.pay.entitlement.service.EntitlementSourceFence;
import ai.neargo.shop.pay.mapper.entitlement.EntGrantMapper;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.AbortGrant;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantCommand;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantDecision;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantView;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.State;
import ai.neargo.shop.spi.entitlement.EntitlementGrantSourcePort;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** External owner lookups precede grant locks; replay and finishing promises do not re-open issuance gates. */
@Service
public class EntitlementGrantServiceImpl implements EntitlementGrantService {
    private final EntGrantMapper grants;
    private final EntitlementSourceFence fences;
    private final EntitlementCatalogService catalog;
    private final EntitlementGrantWriter writer;
    private final PayEntitlementFeatureGate features;
    private final List<EntitlementGrantSourcePort> sources;
    private final ObjectProvider<SourceDecisionPort> decisions;
    private final ObjectMapper json;
    public EntitlementGrantServiceImpl(EntGrantMapper grants, EntitlementCatalogService catalog,
            EntitlementGrantWriter writer, PayEntitlementFeatureGate features, List<EntitlementGrantSourcePort> sources,
            ObjectProvider<SourceDecisionPort> decisions, EntitlementSourceFence fences, ObjectMapper json) {
        this.fences = fences;
        this.grants = grants; this.catalog = catalog; this.writer = writer; this.features = features;
        this.sources = sources; this.decisions = decisions; this.json = json;
    }
    @Override public GrantView prepare(EntitlementContext context, GrantCommand c) {
        supported(context);
        var prior = grants.find(context.tenantNo(), c.grantNo());
        if (prior != null) {
            if (!prior.getPayloadHash().equals(EntitlementGrantWriter.digest(json.writeValueAsString(c)))) conflict();
            return writer.view(prior);
        }
        var fence = fences.find(context, "GRANT", c.grantNo());
        if (fences.cancelled(fence)) {
            fences.requireSource(fence, c.sourceRef());
            if (!c.recipientUserNo().equals(fence.getUserNo()) || !c.entityNo().equals(fence.getEntityNo())) conflict();
            return writer.cancelled(fence);
        }
        features.requireIssuance();
        var scope = new CatalogScope(context.tenantNo(), c.entityNo(), context.actorNo(), false);
        var version = catalog.getVersion(scope, c.templateVersionNo()); var d = version.definition();
        var head = catalog.get(scope, version.templateNo());
        if (!"ACTIVE".equals(head.status()) || !"MERCHANT".equals(d.funder())) conflict();
        new EntitlementRuleValidator().validatePublicationTime(d, Instant.now());
        String mode = "RECHARGE".equals(c.sourceRef().sourceType()) ? "ACTIVITY" : c.sourceRef().sourceType();
        if (!d.allowedIssueModes().contains(mode)
                || ("PAID_CARD".equals(d.kind()) != "PAID_ORDER".equals(mode))
                || (("ACTIVITY".equals(mode)) != (c.permit() != null))) conflict();
        var owners = sources.stream().filter(p -> p.supports(c.sourceRef())).toList();
        if (owners.size() != 1) unavailable();
        var fact = owners.getFirst().resolve(context, c);
        if (fact == null || !context.tenantNo().equals(fact.tenantNo()) || !c.equals(fact.binding())
                || ("PAID_ORDER".equals(mode) && (fact.paidAt() == null || c.paidFactRef() == null))
                || (!"PAID_ORDER".equals(mode) && (fact.paidAt() != null || c.paidFactRef() != null))) conflict();
        return writer.prepare(context, new PreparedGrant(c, version.templateNo(), d, fact.paidAt(), exposure(d, c.quantity())));
    }
    @Override public GrantView commit(EntitlementContext context, GrantDecision c) {
        return finish(context, c.grantNo(), c.sourceRef(), c.decisionRef(), true);
    }
    @Override public GrantView abort(EntitlementContext context, AbortGrant c) {
        return finish(context, c.grantNo(), c.sourceRef(), c.decisionRef(), false);
    }
    private GrantView finish(EntitlementContext context, String no, SourceRef source, DecisionRef ref, boolean issue) {
        supported(context);
        var row = grants.find(context.tenantNo(), no);
        if (row == null && !issue) {
            var fence = fences.find(context, "GRANT", no);
            if (fences.cancelled(fence)) {
                fences.requireSource(fence, source);
                fences.requireDecision(fence, ref);
                return writer.cancelled(fence);
            }
            var truth = sourceDecision(context, source);
            if (truth.status() == SourceDecisionPort.State.UNKNOWN || truth.status() == SourceDecisionPort.State.PENDING) {
                return new GrantView(no, State.PROCESSING, List.of(), source);
            }
            if (truth.status() != SourceDecisionPort.State.CANCELLED || !source.equals(truth.source())
                    || !ref.equals(truth.decision())) conflict();
            if (writer.abortAbsent(context, no, source, truth)) {
                return writer.cancelled(fences.find(context, "GRANT", no));
            }
            row = grants.find(context.tenantNo(), no);
        }
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var frozen = json.readValue(row.getPreparedSnapshot(), PreparedGrant.class); var c = frozen.command();
        if (!source.equals(c.sourceRef())) conflict();
        if ((issue ? "COMMITTED" : "ABORTED").equals(row.getStatus())) {
            if (!ref.decisionNo().equals(row.getSourceDecisionNo())) conflict();
            return writer.view(row);
        }
        if (!"PREPARED".equals(row.getStatus())) conflict();
        var truth = sourceDecision(context, source);
        if (truth.status() == SourceDecisionPort.State.UNKNOWN || truth.status() == SourceDecisionPort.State.PENDING) {
            return new GrantView(no, State.PROCESSING, List.of(), source);
        }
        if (!source.equals(truth.source()) || !ref.equals(truth.decision())
                || !Objects.equals(truth.userNo(), c.recipientUserNo()) || !Objects.equals(truth.entityNo(), c.entityNo())
                || !Objects.equals(truth.currency(), frozen.definition().currency())
                || !Objects.equals(truth.snapshotHash(), c.benefitSnapshotHash())
                || truth.status() != (issue ? SourceDecisionPort.State.COMMITTED : SourceDecisionPort.State.CANCELLED)) conflict();
        return writer.finish(context, no, source, ref, issue, truth);
    }
    @Override public GrantView query(EntitlementContext context, String entity, String no) {
        var row = grants.find(context.tenantNo(), no);
        if (row == null) {
            var fence = fences.find(context, "GRANT", no);
            if (fences.cancelled(fence) && entity.equals(fence.getEntityNo())) return writer.cancelled(fence);
        }
        if (row == null || !entity.equals(row.getEntityNo())) return new GrantView(no, State.NOT_FOUND, List.of(), null);
        return writer.view(row);
    }
    private SourceDecisionPort.SourceDecision sourceDecision(EntitlementContext context, SourceRef source) {
        var local = sources.stream().filter(p -> p.supports(source)).toList();
        if (local.size() > 1) unavailable();
        var truth = local.isEmpty() ? null : local.getFirst().decision(context, source);
        if (truth == null) {
            var owner = decisions.getIfAvailable(); if (owner == null) unavailable();
            truth = owner.query(context, source);
        }
        if (truth == null) unavailable();
        return truth;
    }
    private static long exposure(EntitlementDefinition d, int quantity) {
        Long per = switch (d.benefitMode()) {
            // CASH follows the existing coupon algorithm: its face value is not reduced by a percent-only cap.
            case "CASH" -> d.benefitValue();
            case "PERCENT", "FREE_SHIP" -> d.benefitCapMinor();
            case "GIFT", "SERVICE_UNITS" -> 0L;
            default -> null;
        };
        if (per == null || (("PERCENT".equals(d.benefitMode()) || "FREE_SHIP".equals(d.benefitMode())) && per <= 0)) {
            unavailable();
        }
        try {
            long uses = "ORDER_COUPON".equals(d.kind()) ? d.totalUnits() : 1;
            return Math.multiplyExact(Math.multiplyExact(per, uses), quantity);
        }
        catch (ArithmeticException overflow) { throw BizException.of(ErrorCode.BAD_REQUEST); }
    }
    private static void supported(EntitlementContext context) {
        if (!"MAIN".equals(context.tenantNo())) unavailable();
    }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
    private static void unavailable() { throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE); }
}
