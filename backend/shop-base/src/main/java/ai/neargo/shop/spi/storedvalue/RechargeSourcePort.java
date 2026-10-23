package ai.neargo.shop.spi.storedvalue;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ParticipationPermit;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ReserveActivity;
import java.time.Instant;

/** Core's immutable recharge intent; neither a collection receipt nor a pay credit decision. */
public interface RechargeSourcePort {
    SourceFact resolve(EntitlementContext context, SourceRef source);
    SourceFact byRechargeNo(EntitlementContext context, String rechargeNo);

    record SourceFact(EntitlementContext context, SourceRef source, String rechargeNo,
                      ReserveActivity participation, ParticipationPermit permit, String storeName,
                      Instant createdAt, Instant expiresAt,String customerNo) {
        public SourceFact(EntitlementContext context,SourceRef source,String rechargeNo,ReserveActivity participation,ParticipationPermit permit,String storeName,Instant createdAt,Instant expiresAt){this(context,source,rechargeNo,participation,permit,storeName,createdAt,expiresAt,null);}
        public SourceFact {
            if(customerNo!=null)ContractChecks.id(customerNo);
            ContractChecks.present(context); ContractChecks.present(source); ContractChecks.id(rechargeNo);
            ContractChecks.present(participation); ContractChecks.present(permit);
            ContractChecks.require("CORE".equals(source.sourceSystem()) && "RECHARGE".equals(source.sourceType())
                    && source.sourceRevision() == 0 && source.sourceLineNo() == null && source.unitIndex() == null
                    && source.entitlementIndex() == null && source.equals(participation.sourceRef())
                    && source.equals(permit.sourceRef()));
            ContractChecks.present(createdAt); ContractChecks.present(expiresAt);
        }
    }
}
