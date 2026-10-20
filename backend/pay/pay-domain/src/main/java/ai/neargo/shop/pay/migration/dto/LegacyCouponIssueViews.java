package ai.neargo.shop.pay.migration.dto;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.member.MemberQueryPort.AudienceItem;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.BatchSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
public final class LegacyCouponIssueViews {
    private LegacyCouponIssueViews() { }
    public record Command(LegacyCouponTemplateViews.Reference reference,String mode,String segmentNo,List<AudienceItem> audiences,String userKey,int count,String targetDesc) {
        public Command {ContractChecks.present(reference);audiences=audiences==null?List.of():List.copyOf(audiences);
            if(count==0 && userKey==null) count=1;
            new LegacyCouponAudiencePort.Query(reference.source(),reference.batchNo(),reference.planHash(),"CHECK",mode,segmentNo,audiences,userKey,count,userKey!=null);
            ContractChecks.require(targetDesc==null || targetDesc.length()<=255);}
        public Command(LegacyCouponTemplateViews.Reference reference,String mode,String segmentNo,List<AudienceItem> audiences) {
            this(reference,mode,segmentNo,audiences,null,1,null);
        }
    }
    public record Frozen(Command command,LegacyCouponAudiencePort.Fact source,BatchSnapshot batch) { }
    public record Item(String userNo,String grantNo,String instanceNo,String status,String reasonCode,Instant grantedAt) { }
    public record View(String issueNo,String batchNo,String entityNo,String sourceSystem,String sourceTemplateNo,String targetTemplateNo,
                       String templateVersionNo,String migrationBatchNo,String planHash,String actorNo,String mode,String segmentNo,
                       List<AudienceItem> audiences,String status,int planned,int issued,int skipped,int pending,
                       Map<String,Integer> skipReasons,long amountMinor,Instant occurredAt,List<Item> results,int usedCount,long usedAmountMinor,String targetDesc) {
        public View {audiences=List.copyOf(audiences);skipReasons=Map.copyOf(skipReasons);results=List.copyOf(results);}
        public View(String issueNo,String batchNo,String entityNo,String sourceSystem,String sourceTemplateNo,String targetTemplateNo,
                    String templateVersionNo,String migrationBatchNo,String planHash,String actorNo,String mode,String segmentNo,
                    List<AudienceItem> audiences,String status,int planned,int issued,int skipped,int pending,
                    Map<String,Integer> skipReasons,long amountMinor,Instant occurredAt,List<Item> results) {
            this(issueNo,batchNo,entityNo,sourceSystem,sourceTemplateNo,targetTemplateNo,templateVersionNo,migrationBatchNo,planHash,actorNo,mode,segmentNo,
                    audiences,status,planned,issued,skipped,pending,skipReasons,amountMinor,occurredAt,results,0,0,null);
        }
        public View withTargetDesc(String description) {
            return new View(issueNo,batchNo,entityNo,sourceSystem,sourceTemplateNo,targetTemplateNo,templateVersionNo,migrationBatchNo,planHash,actorNo,mode,segmentNo,
                    audiences,status,planned,issued,skipped,pending,skipReasons,amountMinor,occurredAt,results,usedCount,usedAmountMinor,description);
        }
        public View withUsage(int count,long amount) {
            return new View(issueNo,batchNo,entityNo,sourceSystem,sourceTemplateNo,targetTemplateNo,templateVersionNo,migrationBatchNo,planHash,actorNo,mode,segmentNo,
                    audiences,status,planned,issued,skipped,pending,skipReasons,amountMinor,occurredAt,results,count,amount,targetDesc);
        }
    }
}
