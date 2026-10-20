package ai.neargo.shop.pay.migration.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Internal encrypted payload. Contains recipients; never an API or outbox DTO. */
public record LegacyCouponTargetPlan(int protocolVersion,String planNo,String entityNo,String templateNo,
        Instant assessedAt,List<SourceRef> sources,List<Version> versions,List<Asset> assets,
        LegacyCouponIssuanceAssessor.Assessment issuance,List<String> notices) {
    public LegacyCouponTargetPlan {
        sources=List.copyOf(sources);versions=List.copyOf(versions);assets=List.copyOf(assets);notices=List.copyOf(notices);
    }
    public record SourceRef(String stageNo,String sourceSystem,String sourceTemplateNo,String batchNo,
                            long frozenRevision,String watermark,String contentHash) { }
    public record Version(String versionNo,int versionIndex,EntitlementDefinition definition) { }
    public record Asset(String sourceInstanceNo,String userNo,boolean archivalOnly,String sourceStatus,
                        String instanceNo,String grantNo,String versionNo,int totalUnits,int remainingUnits,
                        Instant effectiveFrom,Instant effectiveUntil) { }

    public static LegacyCouponTargetPlan build(ObjectMapper json,String planNo,String entityNo,Instant at,
            List<SourceRef> sources,JsonNode manifest,LegacyCouponMigrationNormalizer.Report report) {
        ContractChecks.id(planNo);ContractChecks.id(entityNo);ContractChecks.present(at);
        if(!"NORMALIZED".equals(report.status()) || !report.blockers().isEmpty() || report.definition()==null
                || report.issuance()==null || !report.issuance().blockers().isEmpty() || sources.isEmpty()
                || !report.stageNo().equals(sources.getFirst().stageNo())
                || !entityNo.equals(manifest.path("template").path("entityNo").asText())) conflict();
        var recipients=new HashMap<String,String>();
        for(var item:manifest.path("instances")) {
            String no=item.path("userCouponNo").asText(),user=item.path("userNo").asText();
            ContractChecks.id(no);ContractChecks.id(user);
            if(recipients.put(no,user)!=null) conflict();
        }
        var instanceKeys=new java.util.HashSet<String>();
        for(var item:report.instances()) if(!instanceKeys.add(item.sourceInstanceNo())) conflict();
        if(!instanceKeys.equals(recipients.keySet())) conflict();
        var rules=new TreeMap<String,EntitlementDefinition>();
        String rootHash=EntitlementRecords.digest(json.writeValueAsString(report.definition()));
        for(var item:report.instances()) {
            if(!recipients.containsKey(item.sourceInstanceNo()) || item.historicalDefinition()==null
                    || item.remainingUnits()<0 || item.remainingUnits()>item.historicalDefinition().totalUnits()) conflict();
            if(!item.archivalOnly()) rules.put(EntitlementRecords.digest(json.writeValueAsString(item.historicalDefinition())),item.historicalDefinition());
        }
        String templateNo=id("ET",planNo,entityNo);
        var versions=new ArrayList<Version>();var byHash=new HashMap<String,String>();
        String rootVersion=id("EV",planNo,rootHash);versions.add(new Version(rootVersion,1,report.definition()));
        byHash.put(rootHash,rootVersion);
        for(var entry:rules.entrySet()) if(!byHash.containsKey(entry.getKey())) {
            String version=id("EV",planNo,entry.getKey());byHash.put(entry.getKey(),version);
            versions.add(new Version(version,versions.size()+1,entry.getValue()));
        }
        var assets=new ArrayList<Asset>();
        for(var item:report.instances().stream().sorted(java.util.Comparator.comparing(LegacyCouponMigrationNormalizer.InstancePlan::sourceInstanceNo)).toList()) {
            String hash=EntitlementRecords.digest(json.writeValueAsString(item.historicalDefinition()));
            assets.add(new Asset(item.sourceInstanceNo(),recipients.get(item.sourceInstanceNo()),item.archivalOnly(),item.sourceStatus(),
                    item.archivalOnly()?null:id("EI",planNo,item.sourceInstanceNo()),
                    item.archivalOnly()?null:id("MG",planNo,item.sourceInstanceNo()),
                    item.archivalOnly()?null:byHash.get(hash),item.historicalDefinition().totalUnits(),Math.toIntExact(item.remainingUnits()),
                    item.effectiveFrom(),item.effectiveUntil()));
        }
        return new LegacyCouponTargetPlan(1,planNo,entityNo,templateNo,at,sources,versions,assets,report.issuance(),report.notices());
    }
    private static String id(String prefix,String plan,String source) {
        return prefix+EntitlementRecords.digest(plan+":"+source).substring(0,62);
    }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
}
