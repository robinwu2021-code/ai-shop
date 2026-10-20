package ai.neargo.shop.pay.entitlement.service;

import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.audit;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.changed;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.conflict;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.digest;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.now;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.PreparedGrant;
import ai.neargo.shop.pay.entitlement.entity.EntSaleReservation;
import ai.neargo.shop.pay.mapper.entitlement.EntSaleReservationMapper;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort.ReserveSale;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort.SaleView;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort.State;
import ai.neargo.shop.spi.entitlement.SourceRef;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Only pay resources are locked here. Source facts must be resolved before entering this writer. */
@Component
public class EntitlementSaleReservationWriter {
    private final EntSaleReservationMapper reservations;
    private final EntitlementIssuePolicyService policies;
    private final ObjectMapper json;

    public EntitlementSaleReservationWriter(EntSaleReservationMapper reservations,
            EntitlementIssuePolicyService policies, ObjectMapper json) {
        this.reservations = reservations; this.policies = policies; this.json = json;
    }

    @Transactional("payTxManager")
    public SaleView reserve(EntitlementContext ctx, ReserveSale c, String currency, String template,
                            EntitlementDefinition definition) {
        var candidate = candidate(ctx, c, currency);
        candidate.setTemplateNo(template); candidate.setDefinitionSnapshot(json.writeValueAsString(definition));
        reservations.claim(candidate);
        var row = reservations.lock(ctx.tenantNo(), c.reservationNo());
        match(c, row);
        if (!"RESERVING".equals(row.getStatus())) return view(row);
        policies.reserve(scope(ctx, c.entityNo()), template, c.userNo(), 1, 0);
        row.setStatus("RESERVED"); update(ctx, row);
        return view(row);
    }

    @Transactional("payTxManager")
    public SaleView release(EntitlementContext ctx, ReserveSale c, String currency, DecisionRef decision) {
        reservations.claim(candidate(ctx, c, currency));
        var row = reservations.lock(ctx.tenantNo(), c.reservationNo());
        match(c, row);
        if (!currency.equals(row.getCurrency())) conflict();
        if ("RELEASED".equals(row.getStatus())) {
            if (!decision.decisionNo().equals(row.getSourceDecisionNo())) conflict();
            return view(row);
        }
        // The grant owns this hold now. Only its commit/abort transaction can settle it.
        if ("TRANSFERRED".equals(row.getStatus())) return view(row);
        if ("RESERVED".equals(row.getStatus())) {
            policies.settle(scope(ctx, c.entityNo()), row.getTemplateNo(), c.userNo(), 1, 0, false);
        } else if (!"RESERVING".equals(row.getStatus())) conflict();
        row.setStatus("RELEASED"); row.setSourceDecisionNo(decision.decisionNo()); update(ctx, row);
        return view(row);
    }

    @Transactional(value = "payTxManager", propagation = Propagation.MANDATORY)
    public String transfer(EntitlementContext ctx, PreparedGrant prepared) {
        var grant = prepared.command();
        var c = new ReserveSale(ReserveSale.number(grant.sourceRef()), grant.sourceRef(), grant.templateVersionNo(),
                grant.recipientUserNo(), grant.entityNo(), grant.benefitSnapshotHash());
        var row = reservations.lock(ctx.tenantNo(), c.reservationNo());
        match(c, row);
        if (grant.quantity() != 1 || grant.permit() != null
                || !prepared.templateNo().equals(row.getTemplateNo())
                || !prepared.definition().equals(json.readValue(row.getDefinitionSnapshot(), EntitlementDefinition.class))) conflict();
        if ("TRANSFERRED".equals(row.getStatus())) {
            if (!grant.grantNo().equals(row.getGrantNo())) conflict();
        } else {
            if (!"RESERVED".equals(row.getStatus())) conflict();
            row.setStatus("TRANSFERRED"); row.setGrantNo(grant.grantNo()); update(ctx, row);
        }
        return row.getReservationNo();
    }

    public void match(ReserveSale c, EntSaleReservation row) {
        if (row == null || !digest(json.writeValueAsString(c)).equals(row.getPayloadHash())
                || !c.equals(json.readValue(row.getCommandSnapshot(), ReserveSale.class))) conflict();
    }

    public SaleView view(EntSaleReservation row) {
        var state = "RESERVING".equals(row.getStatus()) ? State.PROCESSING : State.valueOf(row.getStatus());
        return new SaleView(row.getReservationNo(), json.readValue(row.getSourceSnapshot(), SourceRef.class),
                state, row.getGrantNo());
    }

    private EntSaleReservation candidate(EntitlementContext ctx, ReserveSale c, String currency) {
        var row = new EntSaleReservation(); audit(row, ctx);
        row.setReservationNo(c.reservationNo()); row.setSourceKey(c.sourceRef().grantKey());
        row.setSourceSnapshot(json.writeValueAsString(c.sourceRef())); row.setCommandSnapshot(json.writeValueAsString(c));
        row.setPayloadHash(digest(row.getCommandSnapshot())); row.setUserNo(c.userNo()); row.setEntityNo(c.entityNo());
        row.setCurrency(currency);
        return row;
    }
    private void update(EntitlementContext ctx, EntSaleReservation row) {
        row.setUpdatedAt(now()); row.setUpdatedBy(ctx.actorNo()); changed(reservations.updateById(row));
    }
    private static CatalogScope scope(EntitlementContext ctx, String entity) {
        return new CatalogScope(ctx.tenantNo(), entity, ctx.actorNo(), false);
    }
}
