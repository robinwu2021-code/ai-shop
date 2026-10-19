package ai.neargo.shop.pay.storedvalue.port;

import ai.neargo.shop.pay.mapper.storedvalue.SvRechargeMapper;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ParticipationPermit;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeState;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;
import ai.neargo.shop.spi.storedvalue.StoredValueRechargeFactsPort;
import tools.jackson.databind.ObjectMapper;

/** Read actual pay rows; absent is UNKNOWN to marketing, never proof of cancellation. */
public class StoredValueRechargeFactsPortImpl implements StoredValueRechargeFactsPort {
    private final SvRechargeMapper recharges;
    private final ObjectMapper json;
    public StoredValueRechargeFactsPortImpl(SvRechargeMapper recharges, ObjectMapper json) {
        this.recharges = recharges; this.json = json;
    }
    @Override public RechargeFact query(EntitlementContext ctx, String entity, String no) {
        var r = recharges.find(ctx.tenantNo(), no);
        if (r == null || !entity.equals(r.getEntityNo())) {
            return new RechargeFact(ctx.tenantNo(), new RechargeView(no, RechargeState.NOT_FOUND, null, entity,
                    null, null, null, null, null, null, 0), null, null, null, null);
        }
        return new RechargeFact(ctx.tenantNo(), StoredValueFacts.view(r),
                json.readValue(r.getSourceSnapshot(), SourceRef.class),
                json.readValue(r.getPermitSnapshot(), ParticipationPermit.class), r.getOperatorNo(),
                r.getReceivedDecisionSnapshot() == null ? null
                        : json.readValue(r.getReceivedDecisionSnapshot(), DecisionRef.class));
    }
}
