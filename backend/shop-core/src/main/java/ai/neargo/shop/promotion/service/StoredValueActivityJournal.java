package ai.neargo.shop.promotion.service;

import static ai.neargo.shop.promotion.service.StoredValueActivityModels.*;
import ai.neargo.shop.promotion.service.StoredValueActivityModels.Decision;
import ai.neargo.shop.common.*;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.ActivityView;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Short Core-only transactions; callers never hold these locks while invoking Pay. */
@Service
public class StoredValueActivityJournal {
 private final JdbcTemplate db;private final TransactionTemplate tx;private final ObjectMapper json;
 public StoredValueActivityJournal(@Qualifier("transactionManager")PlatformTransactionManager manager,ObjectMapper json){
  if(!(manager instanceof DataSourceTransactionManager data))throw new IllegalStateException("Core JDBC transaction required");
  this.db=new JdbcTemplate(Objects.requireNonNull(data.getDataSource()));this.tx=new TransactionTemplate(manager);this.json=json;
 }
 public String hash(UnifiedActivityDefinition definition){return ActivityCommandDeduplicator.hash(json.writeValueAsString(definition));}
 public void invalidateOlder(Scope scope,String activity){write(()->{
  var head=one("SELECT version FROM pmt_activity WHERE tenant_no=? AND entity_no=? AND activity_no=? AND deleted=0 FOR UPDATE",scope.policyScope().tenantNo(),scope.entityNo(),activity);if(head==null)throw BizException.of(ErrorCode.NOT_FOUND);
  String revision=Long.toString(number(head,"version"));
  db.update("UPDATE pmt_sv_release_link SET state='STALE',updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND activity_no=? AND draft_revision_no<>? AND state IN ('DRAFT','PENDING_APPROVAL','APPROVED','PREPARING','PAY_READY','BLOCKED_POLICY')",Timestamp.from(Instant.now()),scope.actorNo(),scope.policyScope().tenantNo(),activity,revision);
  db.update("UPDATE pmt_sv_approval SET status='STALE',updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND activity_no=? AND draft_revision_no<>? AND status IN ('PENDING','APPROVED')",Timestamp.from(Instant.now()),scope.actorNo(),scope.policyScope().tenantNo(),activity,revision);
  return null;
 });}
 public Submission register(Scope scope,ActivityView activity,EffectivePolicy policy,String key){
  ContractChecks.id(key);ContractChecks.require(policy.scope().equals(scope.policyScope())&&policy.configured()&&policy.rules().allowIssue());return write(()->{
   current(scope,activity.activityNo(),Long.toString(activity.revision()),hash(activity.definition()));
   var prior=one("SELECT * FROM pmt_sv_release_link WHERE tenant_no=? AND activity_no=? AND draft_revision_no=? FOR UPDATE",scope.policyScope().tenantNo(),activity.activityNo(),Long.toString(activity.revision()));
   if(prior!=null){binding(scope,prior);return submission(prior);}
   String no=BizKey.next(BizKey.STORED_VALUE_RELEASE_LINK);var at=Timestamp.from(Instant.now());
   boolean approval=policy.rules().requireApproval();var row=base(scope,at);row.put("link_no",no);row.put("management_no",scope.policyScope().managementNo());row.put("store_no",scope.policyScope().storeNo());row.put("scope_type",scope.policyScope().scopeType());row.put("scope_no",scope.policyScope().scopeNo());row.put("entity_no",scope.entityNo());row.put("activity_no",activity.activityNo());row.put("draft_revision_no",Long.toString(activity.revision()));row.put("draft_hash",hash(activity.definition()));row.put("publish_command_no",key);row.put("state",approval?"PENDING_APPROVAL":"PREPARING");row.put("submitted_by",scope.actorNo());row.put("submitted_principal",scope.principalNo());row.put("scope_snapshot",json.writeValueAsString(scope));row.put("permission_snapshot",json.writeValueAsString(Map.of("actorNo",scope.actorNo(),"authorityRevision",scope.authorityRevision())));row.put("policy_versions",json.writeValueAsString(policy.sources()));row.put("policy_hash",policy.hash());row.put("approval_required",approval?1:0);insert("pmt_sv_release_link",row);
   if(approval)approval(scope,row,at);
   return submission(row);
  });
 }
 public Submission find(Scope scope,String activity,long revision){var row=one("SELECT * FROM pmt_sv_release_link WHERE tenant_no=? AND activity_no=? AND draft_revision_no=? AND deleted=0",scope.policyScope().tenantNo(),activity,Long.toString(revision));if(row==null)return null;binding(scope,row);return submission(row);}
 public Submission latest(Scope scope,String activity){var rows=db.queryForList("SELECT * FROM pmt_sv_release_link WHERE tenant_no=? AND activity_no=? AND deleted=0 ORDER BY id DESC LIMIT 1",scope.policyScope().tenantNo(),activity);if(rows.isEmpty())return null;binding(scope,rows.getFirst());return submission(rows.getFirst());}
 public CursorPage<ApprovalView> approvalPage(String tenant,String management,String status,String cursor,int size){
  ContractChecks.require(size>0&&size<=100);
  long before=Long.MAX_VALUE;
  if(cursor!=null){ContractChecks.id(cursor);var row=one("SELECT id FROM pmt_sv_approval WHERE tenant_no=? AND management_no=? AND approval_no=? AND deleted=0",tenant,management,cursor);if(row==null)throw BizException.of(ErrorCode.CONFLICT);before=number(row,"id");}
  var records=approvals(tenant,management,status,before,size+1);
  boolean more=records.size()>size;var chosen=records.stream().limit(size).toList();
  return new CursorPage<>(chosen,more?chosen.getLast().approvalNo():null);
 }
 public CursorPage<ApprovalView> approvalPage(String tenant,String management,String status,String cursor,int size,boolean stores,List<String> brands){
  ContractChecks.require(size>0&&size<=100&&brands.size()<=1000);String allowed=(stores?"scope_type='STORE'":"1=0")+(brands.isEmpty()?"":" OR (scope_type='BRAND' AND scope_no IN ("+String.join(",",Collections.nCopies(brands.size(),"?"))+"))");
  var args=new ArrayList<Object>();args.add(tenant);args.add(management);args.addAll(brands);long before=Long.MAX_VALUE;
  if(cursor!=null){ContractChecks.id(cursor);var cursorArgs=new ArrayList<Object>(args);cursorArgs.add(cursor);var row=one("SELECT id FROM pmt_sv_approval WHERE tenant_no=? AND management_no=? AND ("+allowed+") AND approval_no=? AND deleted=0",cursorArgs.toArray());if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);before=number(row,"id");}
  args.add(before);String filter="";if(status!=null){ContractChecks.require(Set.of("PENDING","APPROVED","RETURNED","STALE").contains(status));filter=" AND status=?";args.add(status);}args.add(size+1);
  var rows=db.queryForList("SELECT * FROM pmt_sv_approval WHERE tenant_no=? AND management_no=? AND ("+allowed+") AND id<? AND deleted=0"+filter+" ORDER BY id DESC LIMIT ?",args.toArray());boolean more=rows.size()>size;var chosen=rows.stream().limit(size).map(this::approvalView).toList();return new CursorPage<>(chosen,more?chosen.getLast().approvalNo():null);
 }
 public ai.neargo.shop.spi.storedvalue.StoredValuePolicies.StoreScope activePolicyScope(String tenant,String entity,String store,String version){var row=one("SELECT scope_snapshot FROM pmt_sv_release_link WHERE tenant_no=? AND entity_no=? AND store_no=? AND activity_version_no=? AND state='ACTIVE' AND deleted=0",tenant,entity,store,version);return row==null?null:(ai.neargo.shop.spi.storedvalue.StoredValuePolicies.StoreScope)json.readValue(text(row,"scope_snapshot"),Scope.class).policyScope();}
 public Scope savedScope(String tenant,String management,String link){var row=link(tenant,link,false);if(!management.equals(text(row,"management_no")))throw BizException.of(ErrorCode.NOT_FOUND);return json.readValue(text(row,"scope_snapshot"),Scope.class);}
 public Submission prepared(Scope scope,String no,StoredValueReleases.View release){return write(()->{
  var candidate=link(scope.policyScope().tenantNo(),no,false);binding(scope,candidate);current(scope,text(candidate,"activity_no"),text(candidate,"draft_revision_no"),text(candidate,"draft_hash"));
  var row=link(scope.policyScope().tenantNo(),no,true);binding(scope,row);
  if(Set.of("ACTIVE","STOP_REQUESTED","STOPPED").contains(text(row,"state")))return submission(row);
  if(!Set.of("PREPARING","APPROVED","PAY_READY").contains(text(row,"state")))conflict();
  if(!text(row,"policy_hash").equals(release.policyHash())||release.approvalRequired()!=(number(row,"approval_required")==1)){
   updateState(row,"BLOCKED_POLICY",scope.actorNo());staleApproval(row,"STALE",scope.actorNo());return submission(link(scope.policyScope().tenantNo(),no,false));
  }
  if(text(row,"release_no")!=null&&(!text(row,"release_no").equals(release.releaseNo())||!text(row,"release_hash").equals(release.contentHash())))conflict();
  db.update("UPDATE pmt_sv_release_link SET issuer_no=?,release_no=?,release_hash=?,state='PAY_READY',updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND link_no=?",release.issuerNo(),release.releaseNo(),release.contentHash(),Timestamp.from(Instant.now()),scope.actorNo(),scope.policyScope().tenantNo(),no);
  return submission(link(scope.policyScope().tenantNo(),no,false));
 });}
 public Submission activated(Scope scope,String no,String versionNo,StoredValueReleases.View release){return write(()->{
  var row=link(scope.policyScope().tenantNo(),no,true);binding(scope,row);
  if(!Objects.equals(text(row,"release_no"),release.releaseNo())||!Objects.equals(text(row,"release_hash"),release.contentHash())||!versionNo.equals(release.activityVersionNo()))conflict();
  if(!Set.of("PAY_READY","ACTIVE","STOP_REQUESTED","STOPPED").contains(text(row,"state")))conflict();
  if("STOPPED".equals(text(row,"state")))return submission(row);
  String state="STOP_REQUESTED".equals(text(row,"state"))?"STOP_REQUESTED":"ISSUE_STOPPED".equals(release.state())?"STOPPED":"ACTIVE".equals(release.state())?"ACTIVE":null;
  if(state==null)conflict();
  db.update("UPDATE pmt_sv_release_link SET activity_version_no=?,state=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND link_no=?",versionNo,state,Timestamp.from(Instant.now()),scope.actorNo(),scope.policyScope().tenantNo(),no);return submission(link(scope.policyScope().tenantNo(),no,false));
 });}
 public Submission state(Scope scope,String no,String state){ContractChecks.require(Set.of("STOP_REQUESTED","STOPPED").contains(state));return write(()->{var row=link(scope.policyScope().tenantNo(),no,true);binding(scope,row);if("STOPPED".equals(text(row,"state")))return submission(row);if(text(row,"release_no")==null||!Set.of("ACTIVE","PAY_READY","STOP_REQUESTED").contains(text(row,"state")))conflict();updateState(row,state,scope.actorNo());return submission(link(scope.policyScope().tenantNo(),no,false));});}
 public StoredValueReleases.Approval approval(Scope scope,String no){var row=link(scope.policyScope().tenantNo(),no,false);binding(scope,row);if(number(row,"approval_required")==0)return null;var a=one("SELECT * FROM pmt_sv_approval WHERE tenant_no=? AND link_no=? AND deleted=0",scope.policyScope().tenantNo(),no);if(a==null||!"APPROVED".equals(text(a,"status")))conflict();return new StoredValueReleases.Approval(text(a,"draft_hash"),text(a,"policy_hash"),text(a,"submitted_by"),text(a,"decided_by"));}
 public List<ApprovalView> approvals(String tenant,String management,String status,long before,int size){ContractChecks.id(tenant);ContractChecks.id(management);ContractChecks.require(size>0&&size<=101&&before>0);ContractChecks.require(status==null||Set.of("PENDING","APPROVED","RETURNED","STALE","BLOCKED_POLICY").contains(status));return db.queryForList("SELECT * FROM pmt_sv_approval WHERE tenant_no=? AND management_no=? AND deleted=0 AND id<?"+(status==null?"":" AND status=?")+" ORDER BY id DESC LIMIT ?",status==null?new Object[]{tenant,management,before,size}:new Object[]{tenant,management,before,status,size}).stream().map(this::approvalView).toList();}
 public ApprovalView approval(String tenant,String management,String no){var row=one("SELECT * FROM pmt_sv_approval WHERE tenant_no=? AND management_no=? AND approval_no=? AND deleted=0",tenant,management,no);if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);return approvalView(row);}
 public ApprovalView decide(Scope scope,String approvalNo,String principal,Decision command,String key,EffectivePolicy policy,boolean eligible){ContractChecks.id(key);ContractChecks.id(principal);String digest=ActivityCommandDeduplicator.hash(json.writeValueAsString(List.of(scope.actorNo(),principal,command)));return write(()->{
  var candidate=one("SELECT * FROM pmt_sv_approval WHERE tenant_no=? AND management_no=? AND approval_no=? AND deleted=0",scope.policyScope().tenantNo(),scope.policyScope().managementNo(),approvalNo);if(candidate==null)throw BizException.of(ErrorCode.NOT_FOUND);
  var head=one("SELECT * FROM pmt_activity WHERE tenant_no=? AND activity_no=? AND entity_no=? AND deleted=0 FOR UPDATE",scope.policyScope().tenantNo(),text(candidate,"activity_no"),scope.entityNo());
  var row=one("SELECT * FROM pmt_sv_approval WHERE tenant_no=? AND approval_no=? FOR UPDATE",scope.policyScope().tenantNo(),approvalNo);var l=link(scope.policyScope().tenantNo(),text(row,"link_no"),true);binding(scope,l);
  if(key.equals(text(row,"decision_key"))){if(!digest.equals(text(row,"decision_hash")))conflict();return approvalView(row);}
  if(!"PENDING".equals(text(row,"status"))||command.expectedRevision()!=number(row,"version"))conflict();
  if(principal.equals(text(row,"submitted_principal")))throw BizException.of(ErrorCode.FORBIDDEN);
  String status="RETURN".equals(command.decision())?"RETURNED":"APPROVED";
  if(head==null||!text(row,"draft_revision_no").equals(Long.toString(number(head,"version")))||!text(row,"draft_hash").equals(hash(json.readValue(text(head,"draft_snapshot"),UnifiedActivityDefinition.class)))||!text(row,"policy_hash").equals(policy.hash()))status="STALE";
  else if("APPROVE".equals(command.decision())&&!eligible)status="BLOCKED_POLICY";
  db.update("UPDATE pmt_sv_approval SET status=?,decided_by=?,decided_principal=?,decision_reason=?,decision_key=?,decision_hash=?,decided_at=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND approval_no=?",status,scope.actorNo(),principal,command.reason(),key,digest,Timestamp.from(Instant.now()),Timestamp.from(Instant.now()),scope.actorNo(),scope.policyScope().tenantNo(),approvalNo);
  updateState(l,"RETURNED".equals(status)?"STALE":status,scope.actorNo());return approval(scope.policyScope().tenantNo(),scope.policyScope().managementNo(),approvalNo);
 });}
 public void requirePublish(String tenant,String activity,long revision,String draftHash){var row=one("SELECT state,draft_hash FROM pmt_sv_release_link WHERE tenant_no=? AND activity_no=? AND draft_revision_no=? AND deleted=0",tenant,activity,Long.toString(revision));if(row==null||!"PAY_READY".equals(text(row,"state"))||!draftHash.equals(text(row,"draft_hash")))throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);}
 private void current(Scope scope,String activity,String revision,String hash){var row=one("SELECT version,draft_snapshot,status FROM pmt_activity WHERE tenant_no=? AND entity_no=? AND activity_no=? AND deleted=0 FOR UPDATE",scope.policyScope().tenantNo(),scope.entityNo(),activity);if(row==null||!revision.equals(Long.toString(number(row,"version")))||!"DRAFT".equals(text(row,"status"))||!hash.equals(hash(json.readValue(text(row,"draft_snapshot"),UnifiedActivityDefinition.class))))conflict();}
 private void approval(Scope scope,Map<String,Object> l,Timestamp at){var row=base(scope,at);row.put("approval_no",BizKey.next(BizKey.STORED_VALUE_ACTIVITY_APPROVAL));for(String key:List.of("link_no","activity_no","draft_revision_no","draft_hash","store_no","scope_type","scope_no","management_no","submitted_by","submitted_principal","policy_versions","policy_hash"))row.put(key,l.get(key));row.put("status","PENDING");row.put("draft_snapshot",text(one("SELECT draft_snapshot FROM pmt_activity WHERE tenant_no=? AND activity_no=? AND deleted=0",scope.policyScope().tenantNo(),text(l,"activity_no")),"draft_snapshot"));insert("pmt_sv_approval",row);}
 private void staleApproval(Map<String,Object> row,String status,String actor){db.update("UPDATE pmt_sv_approval SET status=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND link_no=? AND status IN ('PENDING','APPROVED')",status,Timestamp.from(Instant.now()),actor,text(row,"tenant_no"),text(row,"link_no"));}
 private void updateState(Map<String,Object> row,String state,String actor){db.update("UPDATE pmt_sv_release_link SET state=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND link_no=?",state,Timestamp.from(Instant.now()),actor,text(row,"tenant_no"),text(row,"link_no"));}
 private Submission submission(Map<String,Object> row){var a=one("SELECT approval_no FROM pmt_sv_approval WHERE tenant_no=? AND link_no=?",text(row,"tenant_no"),text(row,"link_no"));return new Submission(text(row,"link_no"),text(row,"activity_no"),text(row,"draft_revision_no"),text(row,"draft_hash"),text(row,"state"),text(row,"release_no"),text(row,"release_hash"),text(row,"policy_hash"),number(row,"approval_required")==1,text(a,"approval_no"),text(row,"activity_version_no"));}
 private ApprovalView approvalView(Map<String,Object> r){return new ApprovalView(text(r,"approval_no"),text(r,"link_no"),text(r,"activity_no"),text(r,"draft_revision_no"),text(r,"draft_hash"),text(r,"store_no"),text(r,"management_no"),text(r,"status"),text(r,"policy_hash"),text(r,"submitted_by"),text(r,"decided_by"),number(r,"version"),text(r,"decision_reason"),json.readValue(text(r,"draft_snapshot"),UnifiedActivityDefinition.class),text(r,"scope_type"),text(r,"scope_no"));}
 private void binding(Scope s,Map<String,Object> r){if(!s.policyScope().managementNo().equals(text(r,"management_no"))||!ai.neargo.shop.spi.storedvalue.StoredValuePolicies.sameIssueIdentity(s.policyScope(),json.readValue(text(r,"scope_snapshot"),Scope.class).policyScope())||!s.entityNo().equals(text(r,"entity_no")))throw BizException.of(ErrorCode.NOT_FOUND);}
 private Map<String,Object> link(String tenant,String no,boolean lock){var row=one("SELECT * FROM pmt_sv_release_link WHERE tenant_no=? AND link_no=? AND deleted=0"+(lock?" FOR UPDATE":""),tenant,no);if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);return row;}
 private Map<String,Object> one(String sql,Object...args){var rows=db.queryForList(sql,args);if(rows.size()>1)throw new IllegalStateException("Unique activity relation required");return rows.isEmpty()?null:rows.getFirst();}
 private void insert(String table,Map<String,Object> row){ContractChecks.require(Set.of("pmt_sv_release_link","pmt_sv_approval").contains(table));db.update("INSERT INTO "+table+" ("+String.join(",",row.keySet())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
 private Map<String,Object> base(Scope scope,Timestamp at){var row=new LinkedHashMap<String,Object>();row.put("tenant_no",scope.policyScope().tenantNo());row.put("version",0L);row.put("deleted",0);row.put("created_at",at);row.put("updated_at",at);row.put("created_by",scope.actorNo());row.put("updated_by",scope.actorNo());return row;}
 private <T>T write(Supplier<T> f){return tx.execute(s->f.get());}
 private static String text(Map<String,Object> r,String key){return r==null||r.get(key)==null?null:r.get(key).toString();}
 private static long number(Map<String,Object> r,String key){return ((Number)r.get(key)).longValue();}
 private static void conflict(){throw BizException.of(ErrorCode.CONFLICT);}
}
