package ai.neargo.shop.pay.entitlement.service.impl;

import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.conflict;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.unavailable;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.PreparedGrant;
import ai.neargo.shop.pay.entitlement.policy.EntitlementRuleValidator;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.entitlement.service.EntitlementCatalogService;
import ai.neargo.shop.pay.entitlement.service.EntitlementSaleReservationService;
import ai.neargo.shop.pay.entitlement.service.EntitlementSaleReservationWriter;
import ai.neargo.shop.pay.mapper.entitlement.EntSaleReservationMapper;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantCommand;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort.ReleaseSale;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort.ReserveSale;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort.SaleView;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort.State;
import ai.neargo.shop.spi.entitlement.EntitlementSaleSourcePort;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Immutable prepayment promises survive later publication and issuance gate changes. */
@Service
@Transactional(value = "payTxManager", propagation = Propagation.NOT_SUPPORTED)
public class EntitlementSaleReservationServiceImpl implements EntitlementSaleReservationService {
    private final EntSaleReservationMapper reservations;
    private final EntitlementSaleReservationWriter writer;
    private final EntitlementCatalogService catalog;
    private final PayEntitlementFeatureGate features;
    private final List<EntitlementSaleSourcePort> sources;
    private final ObjectMapper json;

    public EntitlementSaleReservationServiceImpl(EntSaleReservationMapper reservations,
            EntitlementSaleReservationWriter writer, EntitlementCatalogService catalog,
            PayEntitlementFeatureGate features, List<EntitlementSaleSourcePort> sources, ObjectMapper json) {
        this.reservations = reservations; this.writer = writer; this.catalog = catalog;
        this.features = features; this.sources = sources; this.json = json;
    }

    @Override public SaleView reserve(EntitlementContext ctx, ReserveSale c) {
        supported(ctx);
        var prior = reservations.find(ctx.tenantNo(), c.reservationNo());
        if (prior != null) { writer.match(c, prior); return writer.view(prior); }
        features.requirePaidCard();
        var fact = fact(ctx, c);
        if (!fact.canReserve() || fact.decision().status() != SourceDecisionPort.State.PENDING) conflict();
        var scope = new CatalogScope(ctx.tenantNo(), c.entityNo(), ctx.actorNo(), false);
        var version = catalog.getVersion(scope, c.templateVersionNo()); var d = version.definition();
        var head = catalog.get(scope, version.templateNo());
        if (!"ACTIVE".equals(head.status()) || !"PAID_CARD".equals(d.kind()) || !"MERCHANT".equals(d.funder())
                || !"SERVICE_UNITS".equals(d.benefitMode()) || !d.allowedIssueModes().contains("PAID_ORDER")
                || !d.currency().equals(fact.currency())
                || !d.equals(json.readValue(fact.ruleSnapshot(), EntitlementDefinition.class))) conflict();
        new EntitlementRuleValidator().validatePublicationTime(d, Instant.now());
        return writer.reserve(ctx, c, fact.currency(), version.templateNo(), d);
    }

    @Override public SaleView release(EntitlementContext ctx, ReleaseSale command) {
        supported(ctx);
        var c = command.reservation(); var fact = fact(ctx, c); var truth = fact.decision();
        if (truth.status() == SourceDecisionPort.State.UNKNOWN || truth.status() == SourceDecisionPort.State.PENDING) {
            return new SaleView(c.reservationNo(), c.sourceRef(), State.PROCESSING, null);
        }
        if (truth.status() != SourceDecisionPort.State.CANCELLED || !command.decisionRef().equals(truth.decision())
                || !Objects.equals(c.userNo(), truth.userNo()) || !Objects.equals(c.entityNo(), truth.entityNo())
                || !Objects.equals(c.benefitSnapshotHash(), truth.snapshotHash())
                || !Objects.equals(fact.currency(), truth.currency())) conflict();
        return writer.release(ctx, c, fact.currency(), truth.decision());
    }

    @Override public SaleView query(EntitlementContext ctx, String entity, String no) {
        supported(ctx);
        var row = reservations.find(ctx.tenantNo(), no);
        if (row == null || !entity.equals(row.getEntityNo())) return new SaleView(no, null, State.NOT_FOUND, null);
        return writer.view(row);
    }

    @Override public PreparedGrant promise(EntitlementContext ctx, GrantCommand grant) {
        supported(ctx);
        var c = new ReserveSale(ReserveSale.number(grant.sourceRef()), grant.sourceRef(), grant.templateVersionNo(),
                grant.recipientUserNo(), grant.entityNo(), grant.benefitSnapshotHash());
        var row = reservations.findSource(ctx.tenantNo(), c.sourceRef().grantKey());
        writer.match(c, row);
        if (grant.quantity() != 1 || grant.permit() != null
                || !("RESERVED".equals(row.getStatus()) || "TRANSFERRED".equals(row.getStatus()))
                || ("TRANSFERRED".equals(row.getStatus()) && !grant.grantNo().equals(row.getGrantNo()))) conflict();
        return new PreparedGrant(grant, row.getTemplateNo(),
                json.readValue(row.getDefinitionSnapshot(), EntitlementDefinition.class), null, 0);
    }

    private EntitlementSaleSourcePort.SaleSourceFact fact(EntitlementContext ctx, ReserveSale c) {
        var owners = sources.stream().filter(p -> p.supports(c.sourceRef())).toList();
        if (owners.size() != 1) unavailable();
        var fact = owners.getFirst().saleSource(ctx, c);
        if (fact == null || !c.equals(fact.binding())) conflict();
        return fact;
    }
    private static void supported(EntitlementContext ctx) {
        if (!"MAIN".equals(ctx.tenantNo())) unavailable();
    }
}
