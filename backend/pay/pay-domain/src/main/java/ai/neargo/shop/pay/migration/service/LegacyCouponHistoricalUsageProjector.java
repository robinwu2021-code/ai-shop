package ai.neargo.shop.pay.migration.service;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.LegacyCouponHistoryFacts;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
/** Pure original consumption projection. No invented use intent, financial entry, payment or per-use balance. */
public class LegacyCouponHistoricalUsageProjector {
    private final ObjectMapper json;
    public LegacyCouponHistoricalUsageProjector(ObjectMapper json) {this.json=json;}
    public record Fact(String usageNo,String sourceUsageNo,String sourceInstanceNo,String instanceNo,String userNo,String entityNo,
                       String storeNo,String operatorNo,String orderNo,long amountMinor,Instant occurredAt,Instant revertedAt,String sourceHash) { }
    public List<Fact> project(String importNo,LegacyCouponTargetPlan plan,JsonNode manifest) {
        ContractChecks.id(importNo);
        if(!manifest.path("applications").isArray()) conflict();
        if(!"PMT".equals(plan.sources().getFirst().sourceSystem()) && !manifest.path("applications").isEmpty()) conflict();
        var assets=new HashMap<String,LegacyCouponTargetPlan.Asset>();plan.assets().forEach(asset->assets.put(asset.sourceInstanceNo(),asset));
        var seen=new HashSet<String>();var output=new ArrayList<Fact>();
        for(var row:manifest.path("applications")) {
            String no=row.path("applyNo").asText(),instance=row.path("promoNo").asText();
            var asset=assets.get(instance);
            if(no.isBlank() || !seen.add(no) || asset==null || !"COUPON".equals(row.path("promoType").asText())
                    || !asset.userNo().equals(row.path("userNo").asText()) || !plan.entityNo().equals(row.path("entityNo").asText())) conflict();
            ContractChecks.id(no);
            long amount=number(row,"amountMinor");if(amount<0) conflict();
            Instant at=time(row,"appliedAt"),reverted=nullable(row,"revertedAt")?null:time(row,"revertedAt");
            output.add(new Fact("EU"+EntitlementRecords.digest(importNo+":PMT:"+no).substring(0,62),no,instance,asset.instanceNo(),asset.userNo(),plan.entityNo(),
                    optionalId(row,"storeNo"),optionalId(row,"operatorNo"),optionalId(row,"orderNo"),amount,at,reverted,LegacyCouponHistoryFacts.hash(json,row)));
        }
        for(var asset:plan.assets()) {
            long consumed=output.stream().filter(f->f.sourceInstanceNo().equals(asset.sourceInstanceNo()) && f.revertedAt()==null).count();
            if(consumed!=asset.totalUnits()-asset.remainingUnits()) conflict();
        }
        return List.copyOf(output);
    }
    private static boolean nullable(JsonNode row,String field) {return row.path(field).isMissingNode() || row.path(field).isNull();}
    private static String optionalId(JsonNode row,String field) {
        if(nullable(row,field) || row.path(field).asText().isBlank()) return null;
        String value=row.path(field).asText();ContractChecks.id(value);return value;
    }
    private static long number(JsonNode row,String field) {
        var value=row.path(field);if(!value.isIntegralNumber() || !value.canConvertToLong()) conflict();return value.asLong();
    }
    private static Instant time(JsonNode row,String field) {
        try {
            Instant value=Instant.ofEpochMilli(number(row,field));
            if(value.isBefore(Instant.parse("1000-01-01T00:00:00Z")) || value.isAfter(Instant.parse("9999-12-31T23:59:59.999Z"))) conflict();
            return value;
        } catch(java.time.DateTimeException invalid) {throw BizException.of(ErrorCode.CONFLICT);}
    }
    private static void conflict() {throw BizException.of(ErrorCode.CONFLICT);}
}
