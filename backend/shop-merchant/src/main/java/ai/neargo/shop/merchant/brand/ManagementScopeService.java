package ai.neargo.shop.merchant.brand;

import static ai.neargo.shop.merchant.brand.ManagementRepository.*;
import static ai.neargo.shop.merchant.brand.ManagementModels.*;
import ai.neargo.shop.common.*;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Explicit management authorization, independent of the currently selected store. */
@Service
public class ManagementScopeService {
 final ManagementRepository db;
 private static final Map<String,Set<String>> PERMISSIONS=Map.of(
  "SPACE",Set.of("management.enter","management.resource.read","management.grant.manage","brand.manage","brand.change.read","brand.change.decide"),
  "BRAND",Set.of("brand.read","brand.manage","brand.audit.read","brand.change.read","brand.change.decide"),
  "STORE",Set.of("store.read","store.brand.change"),
  "ENTITY",Set.of("entity.read","payment.merchant.read"),
  "PAYMENT_MERCHANT",Set.of("payment.merchant.read"));
 public ManagementScopeService(ManagementRepository db){this.db=db;}
 Map<String,Object> space(Actor a,String m){return db.one("SELECT * FROM mch_management_space WHERE tenant_no=? AND management_no=? AND deleted=0",a.tenantNo(),m);}
 public Map<String,Object> resolve(Actor a,String m){
  var space=space(a,m);if(space==null||!"ACTIVE".equals(str(space,"status"))||!can(a,m,"SPACE",m,"management.enter"))throw BizException.of(ErrorCode.NOT_FOUND);
  return db.view(space);
 }
 void lock(Actor a,String m){
  var row=db.one("SELECT * FROM mch_management_space WHERE tenant_no=? AND management_no=? AND deleted=0 FOR UPDATE",a.tenantNo(),m);
  if(row==null||!"ACTIVE".equals(str(row,"status")))throw BizException.of(ErrorCode.NOT_FOUND);
 }
 public boolean can(Actor a,String m,String type,String no,String permission){
  var s=space(a,m);if(s==null||!"ACTIVE".equals(str(s,"status"))||!PERMISSIONS.getOrDefault(type,Set.of()).contains(permission))return false;
  if(a.actorKey().equals(str(s,"owner_actor_no"))){
   if("SPACE".equals(type)&&m.equals(no))return true;
   if("BRAND".equals(type)&&db.one("SELECT brand_no FROM mch_brand WHERE tenant_no=? AND management_no=? AND brand_no=? AND deleted=0",a.tenantNo(),m,no)!=null)return true;
  }
  for(var g:db.rows("SELECT * FROM mch_management_grant WHERE tenant_no=? AND management_no=? AND actor_no=? AND resource_type=? AND resource_no=? AND permission_code=? AND deleted=0",a.tenantNo(),m,a.actorKey(),type,no,permission))
   if(valid(a,g,new HashSet<>()))return true;
  return false;
 }
 boolean valid(Actor a,Map<String,Object> g,Set<String> seen){
  if(g==null||!"ACTIVE".equals(str(g,"status"))||seen.size()>=8||!seen.add(str(g,"grant_no")))return false;
  String recipient=str(g,"actor_no");
  if(recipient==null||db.one("SELECT mch_account_no FROM mch_account WHERE tenant_no=? AND "+(recipient.startsWith("MCH:")?"mch_account_no":"user_no")+"=? AND status='ACTIVE' AND deleted=0",a.tenantNo(),recipient.substring(recipient.indexOf(':')+1))==null)return false;
  var now=Instant.now();var from=instant(g.get("valid_from"));var to=instant(g.get("valid_to"));
  if(now.isBefore(from)||(to!=null&&!now.isBefore(to)))return false;
  var m=space(a,str(g,"management_no"));if(m==null||!"ACTIVE".equals(str(m,"status")))return false;
  String source=str(g,"authority_source_type"),no=str(g,"authority_source_no");
  if("SPACE_OWNER".equals(source))return no.equals(str(m,"management_no"))&&ownedBySpace(a,m,str(g,"resource_type"),str(g,"resource_no"));
  if("OWNER_ACCOUNT".equals(source)){
   var account=db.one("SELECT * FROM mch_account WHERE tenant_no=? AND mch_account_no=? AND status='ACTIVE' AND is_owner=true AND deleted=0",a.tenantNo(),no);
   return account!=null&&resourceEntity(a,str(g,"resource_type"),str(g,"resource_no")).equals(str(account,"entity_no"));
  }
  if(!"PARENT_GRANT".equals(source))return false;
  var parent=db.one("SELECT * FROM mch_management_grant WHERE tenant_no=? AND grant_no=? AND deleted=0",a.tenantNo(),no);
  return parent!=null&&bool(parent,"delegatable")&&Objects.equals(str(g,"management_no"),str(parent,"management_no"))
   &&Objects.equals(str(g,"resource_type"),str(parent,"resource_type"))&&Objects.equals(str(g,"resource_no"),str(parent,"resource_no"))
   &&Objects.equals(str(g,"permission_code"),str(parent,"permission_code"))&&valid(a,parent,seen);
 }
 boolean ownedBySpace(Actor a,Map<String,Object> space,String type,String no){
  return "SPACE".equals(type)&&no.equals(str(space,"management_no"))||"BRAND".equals(type)&&db.one("SELECT brand_no FROM mch_brand WHERE tenant_no=? AND management_no=? AND brand_no=? AND deleted=0",a.tenantNo(),str(space,"management_no"),no)!=null;
 }
 String resourceEntity(Actor a,String type,String no){
  Map<String,Object> row=switch(type){
   case "ENTITY" -> db.one("SELECT entity_no FROM mch_entity WHERE tenant_no=? AND entity_no=? AND deleted=0",a.tenantNo(),no);
   case "STORE" -> db.one("SELECT entity_no FROM mch_store WHERE tenant_no=? AND store_no=? AND deleted=0",a.tenantNo(),no);
   case "PAYMENT_MERCHANT" -> db.one("SELECT entity_no FROM mch_payment_merchant WHERE tenant_no=? AND pay_merchant_no=? AND deleted=0",a.tenantNo(),no);
   default -> null;
  };return row==null?"":str(row,"entity_no");
 }
 public void require(Actor a,String m,String type,String no,String permission){if(!can(a,m,type,no,permission))throw BizException.of(ErrorCode.FORBIDDEN);}
 List<Map<String,Object>> ownerAccounts(Actor a){
  boolean mch=a.actorKey().startsWith("MCH:");String no=a.actorKey().substring(a.actorKey().indexOf(':')+1);
  return db.rows("SELECT * FROM mch_account WHERE tenant_no=? AND "+(mch?"mch_account_no":"user_no")+"=? AND status='ACTIVE' AND is_owner=true AND deleted=0",a.tenantNo(),no);
 }
 public Map<String,Object> context(Actor a){
  var spaces=db.rows("SELECT DISTINCT s.* FROM mch_management_space s WHERE s.tenant_no=? AND s.status='ACTIVE' AND s.deleted=0 AND (s.owner_actor_no=? OR EXISTS (SELECT 1 FROM mch_management_grant g WHERE g.tenant_no=s.tenant_no AND g.management_no=s.management_no AND g.actor_no=? AND g.status='ACTIVE' AND g.deleted=0)) ORDER BY s.id DESC",a.tenantNo(),a.actorKey(),a.actorKey()).stream()
   .filter(s->can(a,str(s,"management_no"),"SPACE",str(s,"management_no"),"management.enter"))
   .map(s->{var v=db.view(s);v.put("actions",PERMISSIONS.get("SPACE").stream().filter(p->can(a,str(s,"management_no"),"SPACE",str(s,"management_no"),p)).sorted().toList());return v;}).toList();
  return Map.of("actorKey",a.actorKey(),"spaces",spaces,"canBootstrap",!ownerAccounts(a).isEmpty(),"bootstrapSources",ownerAccounts(a).stream().map(x->{var entity=db.one("SELECT name FROM mch_entity WHERE tenant_no=? AND entity_no=? AND deleted=0",a.tenantNo(),str(x,"entity_no"));return Map.of("accountNo",str(x,"mch_account_no"),"entityNo",str(x,"entity_no"),"entityName",entity==null?"—":str(entity,"name"),"stores",db.rows("SELECT store_no AS storeNo,name FROM mch_store WHERE tenant_no=? AND entity_no=? AND deleted=0",a.tenantNo(),str(x,"entity_no")),"paymentMerchants",db.rows("SELECT pay_merchant_no AS payMerchantNo FROM mch_payment_merchant WHERE tenant_no=? AND entity_no=? AND deleted=0",a.tenantNo(),str(x,"entity_no")));}).toList());
 }
 public Map<String,Object> create(Actor a,String name,String source,String key){
  name=text(name,64);key=text(key,64);final String n=name,k=key;
  String hash=ManagementRepository.hash(db.encode(List.of(n,source)));
  return db.write(()->{
   if(ownerAccounts(a).stream().noneMatch(x->source.equals(str(x,"mch_account_no"))))throw BizException.of(ErrorCode.FORBIDDEN);
   // Lock the source row, so concurrent initialization using the same key is serialized.
   db.one("SELECT mch_account_no FROM mch_account WHERE tenant_no=? AND mch_account_no=? FOR UPDATE",a.tenantNo(),source);
   var existing=db.one("SELECT * FROM mch_management_space WHERE tenant_no=? AND owner_actor_no=? AND bootstrap_key=?",a.tenantNo(),a.actorKey(),k);
   if(existing!=null){if(!hash.equals(str(existing,"bootstrap_hash")))throw BizException.of(ErrorCode.CONFLICT);return db.view(existing);}
   var row=db.base(a);String no=BizKey.next(BizKey.MANAGEMENT_SPACE);
   row.put("management_no",no);row.put("name",n);row.put("owner_actor_no",a.actorKey());row.put("status","ACTIVE");row.put("bootstrap_source_account_no",source);row.put("bootstrap_key",k);row.put("bootstrap_hash",hash);row.put("auth_revision",0);
   db.insert("mch_management_space",row);seedOwnedResource(a,no,"SPACE",no,PERMISSIONS.get("SPACE"));var result=db.view(row);db.emit(a,db.commandId(a,no,"SPACE_CREATE",k),no,"SPACE_CREATE",hash,result);return result;
  });
 }
 // Called only inside the authorized creation transaction, never from a grant API.
 void seedBrandCreator(Actor a,String m,String brand){
  require(a,m,"SPACE",m,"brand.manage");
  if(db.one("SELECT brand_no FROM mch_brand WHERE tenant_no=? AND management_no=? AND brand_no=? AND created_by=? AND deleted=0",a.tenantNo(),m,brand,a.actorKey())==null)throw BizException.of(ErrorCode.FORBIDDEN);
  seedOwnedResource(a,m,"BRAND",brand,PERMISSIONS.get("BRAND"));
 }
 private void seedOwnedResource(Actor a,String m,String type,String no,Set<String> permissions){
  for(String permission:permissions){var g=db.base(a);g.put("grant_no",BizKey.next(BizKey.MANAGEMENT_GRANT));g.put("management_no",m);g.put("actor_no",a.actorKey());g.put("resource_type",type);g.put("resource_no",no);g.put("permission_code",permission);g.put("status","ACTIVE");g.put("valid_from",Timestamp.from(Instant.now()));g.put("delegatable",1);g.put("authority_source_type","SPACE_OWNER");g.put("authority_source_no",m);g.put("authority_snapshot",db.encode(Map.of("actor",a.actorKey(),"creationAuthorized",true)));db.insert("mch_management_grant",g);}
 }
 public Map<String,Object> grant(Actor a,String m,GrantCommand c){return db.write(()->{
  lock(a,m);require(a,m,"SPACE",m,"management.grant.manage");
  new Actor(a.tenantNo(),c.actorNo());
  if(!PERMISSIONS.getOrDefault(c.resourceType(),Set.of()).contains(c.permissionCode()))throw BizException.of(ErrorCode.BAD_REQUEST);
  String target=c.actorNo().substring(c.actorNo().indexOf(':')+1);
  // Existing entity membership is required for a grant recipient; arbitrary user identifiers are not an invitation mechanism.
  if(db.one("SELECT mch_account_no FROM mch_account WHERE tenant_no=? AND "+(c.actorNo().startsWith("MCH:")?"mch_account_no":"user_no")+"=? AND status='ACTIVE' AND deleted=0",a.tenantNo(),target)==null)throw BizException.of(ErrorCode.NOT_FOUND);
  var space=space(a,m);String sourceType=null,sourceNo=null;
  if(a.actorKey().equals(str(space,"owner_actor_no"))&&ownedBySpace(a,space,c.resourceType(),c.resourceNo())){sourceType="SPACE_OWNER";sourceNo=m;}
  if(sourceType==null){String entity=resourceEntity(a,c.resourceType(),c.resourceNo());var owner=ownerAccounts(a).stream().filter(x->!entity.isEmpty()&&entity.equals(str(x,"entity_no"))).findFirst();if(owner.isPresent()){sourceType="OWNER_ACCOUNT";sourceNo=str(owner.get(),"mch_account_no");}}
  if(sourceType==null){for(var g:db.rows("SELECT * FROM mch_management_grant WHERE tenant_no=? AND management_no=? AND actor_no=? AND resource_type=? AND resource_no=? AND permission_code=? AND delegatable=1 AND deleted=0",a.tenantNo(),m,a.actorKey(),c.resourceType(),c.resourceNo(),c.permissionCode()))if(valid(a,g,new HashSet<>())){sourceType="PARENT_GRANT";sourceNo=str(g,"grant_no");break;}}
  if(sourceType==null)throw BizException.of(ErrorCode.FORBIDDEN);
  var from=c.validFrom()==null?Instant.now():c.validFrom();if(c.validTo()!=null&&!c.validTo().isAfter(from))throw BizException.of(ErrorCode.BAD_REQUEST);
  var existing=db.one("SELECT * FROM mch_management_grant WHERE tenant_no=? AND management_no=? AND actor_no=? AND resource_type=? AND resource_no=? AND permission_code=?",a.tenantNo(),m,c.actorNo(),c.resourceType(),c.resourceNo(),c.permissionCode());
  if(existing!=null&&c.expectedRevision()==null){
   if(!valid(a,existing,new HashSet<>())||bool(existing,"delegatable")!=c.delegatable()||c.validFrom()!=null||c.validTo()!=null)throw BizException.of(ErrorCode.CONFLICT);
   return db.view(existing);
  }
  String no=existing==null?BizKey.next(BizKey.MANAGEMENT_GRANT):str(existing,"grant_no");
  if("PARENT_GRANT".equals(sourceType)&&sourceNo.equals(no))throw BizException.of(ErrorCode.FORBIDDEN);
  var row=existing==null?db.base(a):new LinkedHashMap<>(existing);
  if(existing!=null){if(c.expectedRevision()!=num(existing,"version")||!"ACTIVE".equals(str(existing,"status"))&&!c.reactivate())throw BizException.of(ErrorCode.CONFLICT);}
  row.put("grant_no",no);row.put("management_no",m);row.put("actor_no",c.actorNo());row.put("resource_type",c.resourceType());row.put("resource_no",c.resourceNo());row.put("permission_code",c.permissionCode());row.put("status","ACTIVE");row.put("valid_from",Timestamp.from(from));row.put("valid_to",c.validTo()==null?null:Timestamp.from(c.validTo()));row.put("delegatable",c.delegatable()?1:0);row.put("authority_source_type",sourceType);row.put("authority_source_no",sourceNo);row.put("authority_snapshot",db.encode(Map.of("actor",a.actorKey(),"type",sourceType,"no",sourceNo)));
  if(existing==null)db.insert("mch_management_grant",row);
  else{int changed=db.update("UPDATE mch_management_grant SET status='ACTIVE',valid_from=?,valid_to=?,delegatable=?,authority_source_type=?,authority_source_no=?,authority_snapshot=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND grant_no=? AND version=?",row.get("valid_from"),row.get("valid_to"),row.get("delegatable"),sourceType,sourceNo,row.get("authority_snapshot"),Timestamp.from(Instant.now()),a.actorKey(),a.tenantNo(),no,c.expectedRevision());if(changed!=1)throw BizException.of(ErrorCode.CONFLICT);}
  db.update("UPDATE mch_management_space SET auth_revision=auth_revision+1,version=version+1,updated_at=?,updated_by=? WHERE tenant_no=? AND management_no=?",Timestamp.from(Instant.now()),a.actorKey(),a.tenantNo(),m);
  var result=db.view(db.one("SELECT * FROM mch_management_grant WHERE tenant_no=? AND grant_no=?",a.tenantNo(),no));
  db.emit(a,ManagementRepository.hash(a.tenantNo()+no+result.get("revision")),m,"GRANT_CHANGED",ManagementRepository.hash(db.encode(c)),result);return result;
 });}
 boolean canDelegate(Actor a,String m,String type,String no,String permission){
  var space=space(a,m);if(space==null)return false;
  if(a.actorKey().equals(str(space,"owner_actor_no"))&&ownedBySpace(a,space,type,no))return true;
  String entity=resourceEntity(a,type,no);if(!entity.isEmpty()&&ownerAccounts(a).stream().anyMatch(owner->entity.equals(str(owner,"entity_no"))))return true;
  return db.rows("SELECT * FROM mch_management_grant WHERE tenant_no=? AND management_no=? AND actor_no=? AND resource_type=? AND resource_no=? AND permission_code=? AND delegatable=1 AND deleted=0",a.tenantNo(),m,a.actorKey(),type,no,permission).stream().anyMatch(grant->valid(a,grant,new HashSet<>()));
 }
 public Map<String,Object> revoke(Actor a,String m,String no,long revision,String reason){return db.write(()->{
  lock(a,m);require(a,m,"SPACE",m,"management.grant.manage");text(reason,255);
  var row=db.one("SELECT * FROM mch_management_grant WHERE tenant_no=? AND management_no=? AND grant_no=? AND deleted=0",a.tenantNo(),m,no);
  if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);
  // The revoker must also control the grant's actual resource, not just its space.
  if(!canDelegate(a,m,str(row,"resource_type"),str(row,"resource_no"),str(row,"permission_code")))throw BizException.of(ErrorCode.FORBIDDEN);
  if("SPACE".equals(str(row,"resource_type"))&&str(row,"actor_no").equals(str(space(a,m),"owner_actor_no")))throw BizException.of(ErrorCode.CONFLICT);
  if("REVOKED".equals(str(row,"status")))return db.view(row);
  if(num(row,"version")!=revision)throw BizException.of(ErrorCode.CONFLICT);
  db.update("UPDATE mch_management_grant SET status='REVOKED',version=version+1,updated_at=?,updated_by=? WHERE tenant_no=? AND grant_no=? AND version=?",Timestamp.from(Instant.now()),a.actorKey(),a.tenantNo(),no,revision);
  db.update("UPDATE mch_management_space SET auth_revision=auth_revision+1,version=version+1,updated_at=?,updated_by=? WHERE tenant_no=? AND management_no=?",Timestamp.from(Instant.now()),a.actorKey(),a.tenantNo(),m);
  var result=db.view(db.one("SELECT * FROM mch_management_grant WHERE tenant_no=? AND grant_no=?",a.tenantNo(),no));db.emit(a,ManagementRepository.hash(a.tenantNo()+no+result.get("revision")),m,"GRANT_REVOKED",ManagementRepository.hash(reason),result);return result;
 });}
}
