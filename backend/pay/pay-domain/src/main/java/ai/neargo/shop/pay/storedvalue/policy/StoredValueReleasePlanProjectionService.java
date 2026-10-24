package ai.neargo.shop.pay.storedvalue.policy;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
/** Derived compatibility offers, never editable independently from the approved immutable release. */
@Service
public class StoredValueReleasePlanProjectionService {
 private final IssuerRepository db;
 public StoredValueReleasePlanProjectionService(IssuerRepository db){this.db=db;}
 public void activate(Map<String,Object> release,String actor){
  if(!db.payTransactionActive())throw new IllegalStateException("Projection requires Pay transaction");
  var e=db.decode(text(release,"policy_versions"),StoredValueReleaseService.Evidence.class);String tenant=e.scope().tenantNo();
  for(var tier:db.rows("SELECT * FROM sv_release_tier WHERE tenant_no=? AND release_no=? AND deleted=0 ORDER BY ordinal FOR UPDATE",tenant,text(release,"release_no"))){
   if(text(tier,"projected_plan_version_no")!=null)continue;
   var plan=db.one("SELECT * FROM sv_recharge_plan WHERE tenant_no=? AND governed_activity_no=? AND governed_tier_key=? AND deleted=0 FOR UPDATE",tenant,text(release,"activity_no"),text(tier,"legacy_tier_key"));
   String planNo=plan==null?BizKey.next(BizKey.STORED_VALUE_PLAN):text(plan,"plan_no");String versionNo=BizKey.next(BizKey.STORED_VALUE_PLAN_VERSION);var at=Timestamp.from(Instant.now());
   long sequence=plan==null?1:Math.addExact(number(db.one("SELECT COALESCE(MAX(version_index),0) AS n FROM sv_recharge_plan_version WHERE tenant_no=? AND plan_no=?",tenant,planNo),"n"),1);
   var v=base(tenant,actor,at);v.put("version_no",versionNo);v.put("plan_no",planNo);v.put("version_index",sequence);v.put("paid_amount_minor",number(tier,"paid_amount_minor"));v.put("bonus_amount_minor",number(tier,"bonus_amount_minor"));v.put("scope_snapshot",db.encode(new ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope("STORES",List.of(e.scope().storeNo()))));v.put("schema_version",1);v.put("snapshot_hash",ai.neargo.shop.pay.entitlement.service.EntitlementRecords.digest(text(release,"content_hash")+":"+text(tier,"tier_no")));db.insert("sv_recharge_plan_version",v);
   if(plan==null){var p=base(tenant,actor,at);p.put("plan_no",planNo);p.put("entity_no",e.entityNo());p.put("store_no",e.scope().storeNo());p.put("name",e.original().name());p.put("currency",e.scope().currency());p.put("status","ACTIVE");p.put("current_version_no",versionNo);p.put("issuer_no",text(release,"issuer_no"));p.put("migrated_release_no",text(release,"release_no"));p.put("governed_activity_no",text(release,"activity_no"));p.put("governed_tier_key",text(tier,"legacy_tier_key"));db.insert("sv_recharge_plan",p);}
   else db.update("UPDATE sv_recharge_plan SET name=?,current_version_no=?,migrated_release_no=?,status='ACTIVE',updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND plan_no=?",e.original().name(),versionNo,text(release,"release_no"),at,actor,tenant,planNo);
   db.update("UPDATE sv_release_tier SET projected_plan_version_no=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND tier_no=? AND projected_plan_version_no IS NULL",versionNo,at,actor,tenant,text(tier,"tier_no"));
  }
 }
 public void stop(Map<String,Object> release,String actor){db.update("UPDATE sv_recharge_plan SET status='INACTIVE',updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND migrated_release_no=? AND governed_activity_no=? AND status='ACTIVE'",Timestamp.from(Instant.now()),actor,text(release,"tenant_no"),text(release,"release_no"),text(release,"activity_no"));}
 private Map<String,Object> base(String tenant,String actor,Timestamp at){var r=new LinkedHashMap<String,Object>();r.put("tenant_no",tenant);r.put("version",0L);r.put("deleted",0);r.put("created_at",at);r.put("updated_at",at);r.put("created_by",actor);r.put("updated_by",actor);return r;}
}
