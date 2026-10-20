package ai.neargo.shop.spi.entitlement;
import java.time.Instant;
import java.util.List;
/** Core compatibility identity projection accepts only actual completed pay issuance facts. */
public interface LegacyCouponIssuedProjectionPort {
    void record(EntitlementContext context,List<Right> rights);
    List<Right> owned(EntitlementContext context,String userNo);
    record Right(String instanceNo,String userNo,String entityNo,String sourceSystem,String sourceTemplateNo,String targetTemplateNo,
                 String templateVersionNo,String migrationBatchNo,String planHash,String issueNo,String grantNo,String actorNo,Instant grantedAt) {
        public Right {
            for(String id:new String[]{instanceNo,userNo,entityNo,sourceTemplateNo,targetTemplateNo,templateVersionNo,migrationBatchNo,issueNo,grantNo,actorNo}) ContractChecks.id(id);
            ContractChecks.require("PMT".equals(sourceSystem) || "MKT_LEGACY".equals(sourceSystem));
            ContractChecks.hash(planHash);ContractChecks.present(grantedAt);
        }
    }
}
