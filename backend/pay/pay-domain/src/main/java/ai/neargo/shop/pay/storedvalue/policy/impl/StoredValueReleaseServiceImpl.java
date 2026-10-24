package ai.neargo.shop.pay.storedvalue.policy.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueIssuePolicyEvaluator;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueIssuePolicyService;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueReleasePlanProjectionService;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueReleaseService;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.IssueRequest;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.PolicyVersion;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.StoreScope;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Activate;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Check;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Prepare;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Stop;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.View;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.number;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.text;

/** Pay owns immutable amounts; these metadata operations never collect, credit or transfer money. */
@Service("storedValueReleaseService")
public class StoredValueReleaseServiceImpl implements StoredValueReleaseService {
 private final StoredValueReleasePlanProjectionService projections;
 private final IssuerRepository db; private final StoredValueIssuePolicyService policies; private final StoredValueIssuePolicyEvaluator evaluator;
 
 public StoredValueReleaseServiceImpl(IssuerRepository db,StoredValueIssuePolicyService policies,StoredValueIssuePolicyEvaluator evaluator,StoredValueReleasePlanProjectionService projections){this.db=db;this.policies=policies;this.evaluator=evaluator;this.projections=projections;}
 public Check check(Prepare c){
  var policy=policies.evaluate(c.scope());var blockers=new LinkedHashSet<String>();boolean approval=false;
  if(!c.endsAt().isAfter(Instant.now()))blockers.add("ACTIVITY_EXPIRED");
  int days=days(c.startsAt(),c.endsAt(),c.zone());
  for(var tier:c.tiers()){var decision=evaluator.check(policy,new IssueRequest(tier.paidMinor(),tier.bonusMinor(),days,0,0));blockers.addAll(decision.blockers());approval|=decision.approvalRequired();}
  return new Check(policy,blockers.isEmpty(),blockers.isEmpty()&&approval,List.copyOf(blockers));
 }
 public View prepare(Prepare c){return db.write(()->{
  var policy=policies.evaluateLocked(c.scope());
  var issuer=issuer(c.scope(),c.entityNo());
  var previous=db.one("SELECT * FROM sv_release WHERE tenant_no=? AND activity_no=? AND draft_revision_no=? AND deleted=0 FOR UPDATE",c.scope().tenantNo(),c.activityNo(),c.draftRevisionNo());
  String contentHash=hash(db.encode(List.of(Arrays.asList(c.scope().tenantNo(),c.scope().managementNo(),c.scope().storeNo(),c.scope().brandNo(),c.scope().currency()),c.entityNo(),c.submittedBy(),c.name(),c.activityNo(),c.draftRevisionNo(),c.draftHash(),c.startsAt(),c.endsAt(),c.zone(),c.tiers())));
  if(previous!=null){if(!contentHash.equals(text(previous,"content_hash")))conflict();return view(previous);}
  if(!"ACTIVE".equals(text(issuer,"status"))||!c.endsAt().isAfter(Instant.now()))conflict();
  policies.requireStoreAccess(c.scope(),c.entityNo(),text(issuer,"issuer_no"));
  int days=days(c.startsAt(),c.endsAt(),c.zone());
  boolean approval=false;
  for(var tier:c.tiers()){
   var decision=evaluator.check(policy,new IssueRequest(tier.paidMinor(),tier.bonusMinor(),days,0,0));
   if(!decision.allowed())conflict();approval|=decision.approvalRequired();
  }
  var at=Timestamp.from(Instant.now());var row=base(c.scope().tenantNo(),c.actorNo(),at);String no=BizKey.next(BizKey.STORED_VALUE_RELEASE);
  var evidence=new Evidence(c.scope(),c.entityNo(),c.submittedBy(),c.draftHash(),policy.hash(),approval,policy.sources().stream().map(PolicyVersion::versionNo).toList(),c);
  row.put("release_no",no);row.put("issuer_no",text(issuer,"issuer_no"));row.put("issuer_version_no",text(issuer,"current_issuer_version_no"));row.put("activity_no",c.activityNo());row.put("draft_revision_no",c.draftRevisionNo());
  row.put("release_index",Math.addExact(number(db.one("SELECT COALESCE(MAX(release_index),0) AS n FROM sv_release WHERE tenant_no=? AND issuer_no=? AND activity_no=?",c.scope().tenantNo(),text(issuer,"issuer_no"),c.activityNo()),"n"),1));
  row.put("source_kind","V2_ACTIVITY");row.put("currency",c.scope().currency());row.put("starts_at",Timestamp.from(c.startsAt()));row.put("ends_at",Timestamp.from(c.endsAt()));row.put("state","PREPARED");row.put("redemption_scope_mode","STORE_ONLY");row.put("scope_snapshot",db.encode(Map.of("mode","STORES","refs",List.of(c.scope().storeNo()))));row.put("policy_versions",db.encode(evidence));row.put("content_hash",contentHash);db.insert("sv_release",row);
  int ordinal=0;for(var tier:c.tiers()){
   var t=base(c.scope().tenantNo(),c.actorNo(),at);t.put("tier_no",BizKey.next(BizKey.STORED_VALUE_RELEASE_TIER));t.put("release_no",no);t.put("ordinal",++ordinal);t.put("paid_amount_minor",tier.paidMinor());t.put("bonus_amount_minor",tier.bonusMinor());t.put("legacy_tier_key",tier.sourceTierNo());db.insert("sv_release_tier",t);
  }
  return view(row);
 });}
 public View activate(Activate c){return db.write(()->{
  var policy=policies.evaluateLocked(c.scope());var issuer=issuer(c.scope(),c.entityNo());
  var row=release(c.scope(),c.releaseNo(),true);var evidence=evidence(row);
  if(!StoredValuePolicies.sameStoreIdentity(evidence.scope(),c.scope())||!evidence.entityNo().equals(c.entityNo())||!c.activityNo().equals(text(row,"activity_no"))||!c.draftRevisionNo().equals(text(row,"draft_revision_no"))||!c.draftHash().equals(evidence.draftHash())||!c.releaseHash().equals(text(row,"content_hash"))||!text(issuer,"issuer_no").equals(text(row,"issuer_no")))conflict();
  String bound=text(row,"activity_version_no");
  if(bound!=null){if(!bound.equals(c.activityVersionNo())||!c.activityVersionHash().equals(text(row,"activity_version_hash"))||!Set.of("ACTIVE","ISSUE_STOPPED").contains(text(row,"state")))conflict();return view(row);}
  policies.requireStoreAccess(c.scope(),c.entityNo(),text(issuer,"issuer_no"));
  if(!"PREPARED".equals(text(row,"state"))||!policy.hash().equals(evidence.policyHash())||!"ACTIVE".equals(text(issuer,"status")))conflict();
  if(evidence.approvalRequired()){
   var approval=c.approval();if(approval==null||!approval.submittedBy().equals(evidence.submittedBy())||!approval.draftHash().equals(evidence.draftHash())||!approval.policyHash().equals(policy.hash()))conflict();
  }
  var original=evidence.original();if(!original.endsAt().isAfter(Instant.now()))conflict();
  for(var tier:original.tiers())if(!evaluator.check(policy,new IssueRequest(tier.paidMinor(),tier.bonusMinor(),days(original.startsAt(),original.endsAt(),original.zone()),0,0)).allowed())conflict();
  db.update("UPDATE sv_release SET state='ACTIVE',activity_version_no=?,activity_version_hash=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND release_no=?",c.activityVersionNo(),c.activityVersionHash(),Timestamp.from(Instant.now()),c.actorNo(),c.scope().tenantNo(),c.releaseNo());
  var activated=release(c.scope(),c.releaseNo(),false);projections.activate(activated,c.actorNo());return view(activated);
 });}
 public View stop(Stop c){return db.write(()->{
  // No policy eligibility check: a tightened policy cannot prevent stopping new issuance.
  issuer(c.scope(),c.entityNo());var row=release(c.scope(),c.releaseNo(),true);var evidence=evidence(row);
  if(!StoredValuePolicies.sameStoreIdentity(evidence.scope(),c.scope())||!evidence.entityNo().equals(c.entityNo()))conflict();
  if("ISSUE_STOPPED".equals(text(row,"state")))return view(row);
  if(!Set.of("PREPARED","ACTIVE").contains(text(row,"state")))conflict();
  projections.stop(row,c.actorNo());
  db.update("UPDATE sv_release SET state='ISSUE_STOPPED',updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND release_no=?",Timestamp.from(Instant.now()),c.actorNo(),c.scope().tenantNo(),c.releaseNo());return view(release(c.scope(),c.releaseNo(),false));
 });}
 private Map<String,Object> issuer(StoreScope scope,String entity){
  var row=db.one("SELECT i.*,v.settlement_entity_no,v.currency FROM sv_issuer i JOIN sv_issuer_version v ON CAST(v.tenant_no AS BINARY(256))=CAST(i.tenant_no AS BINARY(256)) AND CAST(v.issuer_no AS BINARY(256))=CAST(i.issuer_no AS BINARY(256)) AND CAST(v.issuer_version_no AS BINARY(256))=CAST(i.current_issuer_version_no AS BINARY(256)) WHERE i.tenant_no=? AND i.scope_type='STORE' AND i.scope_no=? AND i.deleted=0 AND v.deleted=0 FOR UPDATE",scope.tenantNo(),scope.storeNo());
  if(row==null||!entity.equals(text(row,"settlement_entity_no"))||!scope.currency().equals(text(row,"currency"))||!"ISSUER".equals(text(row,"write_mode"))||!Set.of("ACTIVE","ISSUE_STOPPED").contains(text(row,"status")))conflict();return row;
 }
 private Map<String,Object> release(StoreScope scope,String no,boolean lock){var row=db.one("SELECT * FROM sv_release WHERE tenant_no=? AND release_no=? AND deleted=0"+(lock?" FOR UPDATE":""),scope.tenantNo(),no);if(row==null||!"V2_ACTIVITY".equals(text(row,"source_kind")))throw BizException.of(ErrorCode.NOT_FOUND);return row;}
 private Evidence evidence(Map<String,Object> row){return db.decode(text(row,"policy_versions"),Evidence.class);}
 private View view(Map<String,Object> row){var e=evidence(row);return new View(text(row,"release_no"),text(row,"issuer_no"),text(row,"state"),text(row,"content_hash"),e.policyHash(),e.approvalRequired(),text(row,"activity_version_no"),e.original().tiers());}
 private int days(Instant start,Instant end,String zone){return Math.toIntExact(Math.addExact(ChronoUnit.DAYS.between(start.atZone(ZoneId.of(zone)).toLocalDate(),end.minusNanos(1).atZone(ZoneId.of(zone)).toLocalDate()),1));}
 private Map<String,Object> base(String tenant,String actor,Timestamp at){var row=new LinkedHashMap<String,Object>();row.put("tenant_no",tenant);row.put("version",0L);row.put("deleted",0);row.put("created_at",at);row.put("updated_at",at);row.put("created_by",actor);row.put("updated_by",actor);return row;}
 private String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private static void conflict(){throw BizException.of(ErrorCode.CONFLICT);}
}
