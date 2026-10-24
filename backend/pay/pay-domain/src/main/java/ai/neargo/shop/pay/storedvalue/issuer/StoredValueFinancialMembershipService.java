package ai.neargo.shop.pay.storedvalue.issuer;
import ai.neargo.shop.common.*;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueCrossEntityReadiness;
import ai.neargo.shop.spi.storedvalue.StoredValueMembership.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
/** Pay-local saga participant: disable new use before Core switches; never move or copy wallets. */
@Service
public class StoredValueFinancialMembershipService {
 private final IssuerRepository db;private final StoredValueCrossEntityReadiness readiness;
 public StoredValueFinancialMembershipService(IssuerRepository db,StoredValueCrossEntityReadiness readiness){this.db=db;this.readiness=readiness;}
 public List<Target> describe(Describe c){var result=new ArrayList<Target>();for(String brand:c.brandNos().stream().sorted().toList()){
  var issuer=db.one("SELECT i.issuer_no,i.current_issuer_version_no,v.settlement_entity_no,v.currency FROM sv_issuer i JOIN sv_issuer_version v ON CAST(v.tenant_no AS BINARY(256))=CAST(i.tenant_no AS BINARY(256)) AND CAST(v.issuer_no AS BINARY(256))=CAST(i.issuer_no AS BINARY(256)) AND CAST(v.issuer_version_no AS BINARY(256))=CAST(i.current_issuer_version_no AS BINARY(256)) WHERE i.tenant_no=? AND i.scope_type='BRAND' AND i.scope_no=? AND i.deleted=0 AND v.deleted=0",c.tenantNo(),brand);if(issuer==null)continue;
  var access=db.one("SELECT source_binding_no,source_revision,status,operating_entity_no FROM sv_issuer_store_access WHERE tenant_no=? AND issuer_no=? AND store_no=? AND deleted=0",c.tenantNo(),text(issuer,"issuer_no"),c.storeNo());if(access!=null&&!c.operatingEntityNo().equals(text(access,"operating_entity_no")))conflict();
  result.add(new Target(text(issuer,"issuer_no"),text(issuer,"current_issuer_version_no"),brand,text(issuer,"settlement_entity_no"),text(issuer,"currency"),text(access,"source_binding_no"),access==null?-1:number(access,"source_revision"),text(access,"status")));
 }return List.copyOf(result);}
 public Ack prepare(Prepare c){return db.write(()->{
  lock(c);String hash=hash(c);var prior=rows(c);if(!prior.isEmpty()){validate(c,prior,hash);return ack(c,hash,prior.stream().allMatch(r->"ACTIVE".equals(text(r,"status")))?"ACTIVE":"PREPARED");}
  // New promises require the actual financial foundation; resuming an original does not.
  if(!readiness.schemaReady())throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
  for(var s:sorted(c)){
   var issuer=db.one("SELECT * FROM sv_issuer WHERE tenant_no=? AND issuer_no=? AND deleted=0",c.tenantNo(),s.issuerNo());
   if(!"BRAND".equals(text(issuer,"scope_type"))||!s.brandNo().equals(text(issuer,"scope_no"))||!s.issuerVersionNo().equals(text(issuer,"current_issuer_version_no"))||!"ISSUER".equals(text(issuer,"write_mode")))conflict();
   var version=db.one("SELECT settlement_entity_no FROM sv_issuer_version WHERE tenant_no=? AND issuer_no=? AND issuer_version_no=? AND deleted=0",c.tenantNo(),s.issuerNo(),s.issuerVersionNo());
   if(version==null)conflict();if("JOIN".equals(s.direction())&&!c.operatingEntityNo().equals(text(version,"settlement_entity_no")))readiness.requireCross(c.coreProtocol());
   var access=db.one("SELECT * FROM sv_issuer_store_access WHERE tenant_no=? AND issuer_no=? AND store_no=? AND deleted=0 FOR UPDATE",c.tenantNo(),s.issuerNo(),c.storeNo());
   if(s.oldRevision()==-1?access!=null:access==null||!Objects.equals(s.oldBindingNo(),text(access,"source_binding_no"))||s.oldRevision()!=number(access,"source_revision")||!c.operatingEntityNo().equals(text(access,"operating_entity_no")))conflict();
   if(access!=null&&!Set.of("ACTIVE","ENDED").contains(text(access,"status")))conflict();
   var financial=db.one("SELECT COALESCE(SUM(principal_minor+bonus_minor),0) AS balance,COALESCE(SUM(held_principal_minor+held_bonus_minor),0) AS held FROM sv_account WHERE tenant_no=? AND issuer_no=? AND deleted=0",c.tenantNo(),s.issuerNo());
   if(number(financial,"held")!=0)conflict();
   if(db.one("SELECT recharge_no FROM sv_recharge WHERE tenant_no=? AND issuer_no=? AND status NOT IN ('CREDITED','CANCELLED','REJECTED') AND deleted=0 LIMIT 1",c.tenantNo(),s.issuerNo())!=null)conflict();
   if("LEAVE".equals(s.direction())&&number(financial,"balance")>0&&db.one("SELECT access_no FROM sv_issuer_store_access WHERE tenant_no=? AND issuer_no=? AND store_no<>? AND status='ACTIVE' AND can_redeem=1 AND deleted=0 AND effective_from<=CURRENT_TIMESTAMP AND (effective_to IS NULL OR effective_to>CURRENT_TIMESTAMP) LIMIT 1",c.tenantNo(),s.issuerNo(),c.storeNo())==null)conflict();
   var at=Timestamp.from(Instant.now());
   if(access!=null)db.update("UPDATE sv_issuer_store_access SET can_issue=0,can_redeem=0,status='PREPARING',updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND issuer_no=? AND store_no=?",at,c.actorNo(),c.tenantNo(),s.issuerNo(),c.storeNo());
   var row=base(c,at);row.put("change_no",c.changeNo());row.put("issuer_no",s.issuerNo());row.put("store_no",c.storeNo());row.put("operating_entity_no",c.operatingEntityNo());row.put("direction",s.direction());row.put("old_binding_no",s.oldBindingNo());row.put("new_binding_no",s.newBindingNo());row.put("old_revision",s.oldRevision());row.put("new_revision",s.newRevision());row.put("command_hash",hash);row.put("source_snapshot",db.encode(c));row.put("status","PREPARED");db.insert("sv_financial_membership_change",row);
  }return ack(c,hash,"PREPARED");
 });}
 public Ack commit(Commit c){var original=c.original();return db.write(()->{
  lock(original);String hash=hash(original);if(!hash.equals(c.preparedHash()))conflict();var rows=rows(original);validate(original,rows,hash);
  if(rows.stream().allMatch(r->"ACTIVE".equals(text(r,"status"))))return ack(original,hash,"ACTIVE");
  for(var s:sorted(original)){
   var at=Timestamp.from(Instant.now());var access=db.one("SELECT * FROM sv_issuer_store_access WHERE tenant_no=? AND issuer_no=? AND store_no=? AND deleted=0 FOR UPDATE",original.tenantNo(),s.issuerNo(),original.storeNo());
   if(s.oldRevision()==-1?access!=null:access==null||!"PREPARING".equals(text(access,"status"))||s.oldRevision()!=number(access,"source_revision"))conflict();
   boolean join="JOIN".equals(s.direction());
   if(access==null){var row=base(original,at);row.put("access_no",BizKey.next(BizKey.STORED_VALUE_ISSUER_ACCESS));row.put("issuer_no",s.issuerNo());row.put("store_no",original.storeNo());row.put("operating_entity_no",original.operatingEntityNo());row.put("source_binding_no",s.newBindingNo());row.put("source_revision",s.newRevision());row.put("can_issue",1);row.put("can_redeem",1);row.put("status","ACTIVE");row.put("effective_from",at);db.insert("sv_issuer_store_access",row);}
   else db.update("UPDATE sv_issuer_store_access SET source_binding_no=?,source_revision=?,can_issue=?,can_redeem=?,status=?,effective_from=?,effective_to=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND issuer_no=? AND store_no=?",join?s.newBindingNo():s.oldBindingNo(),s.newRevision(),join?1:0,join?1:0,join?"ACTIVE":"ENDED",at,join?null:at,at,original.actorNo(),original.tenantNo(),s.issuerNo(),original.storeNo());
   db.update("UPDATE sv_financial_membership_change SET status='ACTIVE',updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND change_no=? AND issuer_no=? AND status='PREPARED'",at,original.actorNo(),original.tenantNo(),original.changeNo(),s.issuerNo());
  }return ack(original,hash,"ACTIVE");
 });}
 private void lock(Prepare c){for(var s:sorted(c))if(db.one("SELECT issuer_no FROM sv_issuer WHERE tenant_no=? AND issuer_no=? AND deleted=0 FOR UPDATE",c.tenantNo(),s.issuerNo())==null)throw BizException.of(ErrorCode.NOT_FOUND);}
 private List<Map<String,Object>> rows(Prepare c){return db.rows("SELECT * FROM sv_financial_membership_change WHERE tenant_no=? AND change_no=? AND deleted=0 ORDER BY issuer_no",c.tenantNo(),c.changeNo());}
 private void validate(Prepare c,List<Map<String,Object>> rows,String hash){if(rows.size()!=c.sides().size()||rows.stream().anyMatch(r->!hash.equals(text(r,"command_hash"))||!c.storeNo().equals(text(r,"store_no"))))conflict();}
 private String hash(Prepare c){return EntitlementRecords.digest(db.encode(List.of(c.tenantNo(),c.actorNo(),c.managementNo(),c.changeNo(),c.storeNo(),c.operatingEntityNo(),sorted(c),c.coreProtocol())));}
 private List<Side> sorted(Prepare c){return c.sides().stream().sorted(Comparator.comparing(Side::issuerNo)).toList();}
 private Ack ack(Prepare c,String hash,String state){return new Ack(c.changeNo(),hash,state,sorted(c).stream().map(Side::issuerNo).toList());}
 private Map<String,Object> base(Prepare c,Timestamp at){var row=new LinkedHashMap<String,Object>();row.put("tenant_no",c.tenantNo());row.put("version",0);row.put("deleted",0);row.put("created_at",at);row.put("updated_at",at);row.put("created_by",c.actorNo());row.put("updated_by",c.actorNo());return row;}
 private static void conflict(){throw BizException.of(ErrorCode.CONFLICT);}
}
