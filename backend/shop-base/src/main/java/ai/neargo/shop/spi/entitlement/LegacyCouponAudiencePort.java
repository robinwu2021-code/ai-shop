package ai.neargo.shop.spi.entitlement;
import ai.neargo.shop.spi.member.MemberQueryPort.AudienceItem;
import ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort.Recipient;
import java.util.List;
/** Core resolves selectors or the authenticated claimant; it never accepts a client recipient list. */
public interface LegacyCouponAudiencePort {
    Fact resolve(EntitlementContext context,Query query);
    record Query(EntitlementOwnerRegistryPort.SourceIdentity source,String batchNo,String planHash,String entityNo,
                 String mode,String segmentNo,List<AudienceItem> audiences,String userKey,int count,boolean platformActor) {
        public Query {
            ContractChecks.present(source);ContractChecks.id(batchNo);ContractChecks.hash(planHash);ContractChecks.id(entityNo);
            ContractChecks.require(source.sourceKind()==EntitlementOwnerRegistryPort.SourceKind.TEMPLATE
                    && source.sourceSystem()!=EntitlementOwnerRegistryPort.SourceSystem.PAY);
            ContractChecks.require("TARGETED".equals(mode) || "CENTER".equals(mode));
            ContractChecks.optionalId(segmentNo);audiences=audiences==null?List.of():List.copyOf(audiences);
            if(count==0 && userKey==null) count=1;
            ContractChecks.optionalId(userKey);
            ContractChecks.require(audiences.size()<=100 && count>=1 && count<=1000
                    && (userKey!=null?platformActor && "TARGETED".equals(mode) && segmentNo==null && audiences.isEmpty():count==1)
                    && (!"CENTER".equals(mode) || (segmentNo==null && audiences.isEmpty() && userKey==null)));
        }
        public Query(EntitlementOwnerRegistryPort.SourceIdentity source,String batchNo,String planHash,String entityNo,
                     String mode,String segmentNo,List<AudienceItem> audiences) {
            this(source,batchNo,planHash,entityNo,mode,segmentNo,audiences,null,1,false);
        }
    }
    record Fact(String tenantNo,Query query,String actorNo,int matchedCount,int unreachableCount,List<Recipient> recipients) {
        public Fact {
            ContractChecks.id(tenantNo);ContractChecks.present(query);ContractChecks.id(actorNo);
            recipients=List.copyOf(recipients);
            ContractChecks.require(recipients.size()<=1000 && unreachableCount>=0 && matchedCount==unreachableCount+recipients.size()
                    && recipients.stream().map(Recipient::userNo).distinct().count()==recipients.size());
            for(var r:recipients) {ContractChecks.id(r.userNo());ContractChecks.require(r.eligible() || r.reasonCode()!=null);}
        }
    }
}
