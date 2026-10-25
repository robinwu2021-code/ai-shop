package ai.neargo.shop.merchant.brand.impl;

import static ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import static ai.neargo.shop.merchant.brand.ManagementModels.Page;
import static ai.neargo.shop.merchant.brand.ManagementModels.text;
import static ai.neargo.shop.merchant.brand.ManagementRepository.bool;
import static ai.neargo.shop.merchant.brand.ManagementRepository.hash;
import static ai.neargo.shop.merchant.brand.ManagementRepository.num;
import static ai.neargo.shop.merchant.brand.ManagementRepository.str;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.brand.ManagementModels;
import ai.neargo.shop.merchant.brand.ManagementQueryService;
import ai.neargo.shop.merchant.brand.ManagementRepository;
import ai.neargo.shop.merchant.brand.ManagementScopeService;
import ai.neargo.shop.merchant.brand.ManagementTokenCodec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/** SQL narrows candidates by explicit grants; source-chain validation runs before disclosure. */
@Service("managementQueryService")
public class ManagementQueryServiceImpl implements ManagementQueryService {
 private final ManagementRepository db;private final ManagementScopeService scopes;private final ManagementTokenCodec tokens;
 public ManagementQueryServiceImpl(ManagementRepository db,ManagementScopeService scopes,ManagementTokenCodec tokens){this.db=db;this.scopes=scopes;this.tokens=tokens;}
 public Page list(Actor a,String m,String type,String brand,String keyword,String status,String cursor,int size){
  scopes.resolve(a,m);if(size<1||size>100)throw BizException.of(ErrorCode.BAD_REQUEST);
  String fingerprint=hash(db.encode(Arrays.asList(a.tenantNo(),a.actorKey(),m,type,brand,keyword,status)));long before=Long.MAX_VALUE;
  if(cursor!=null){var c=tokens.decode("RESOURCE_CURSOR",cursor);if(!fingerprint.equals(c.get("scope")))throw BizException.of(ErrorCode.CONFLICT);before=((Number)c.get("before")).longValue();}
  String table,column,permission;
  switch(type){case "BRAND"->{table="mch_brand";column="brand_no";permission="brand.read";}case "ENTITY"->{table="mch_entity";column="entity_no";permission="entity.read";}case "STORE"->{table="mch_store";column="store_no";permission="store.read";}default->throw BizException.of(ErrorCode.BAD_REQUEST);}
  boolean brandSummary="STORE".equals(type)&&brand!=null;if(brandSummary)scopes.require(a,m,"BRAND",brand,"brand.read");
  String sql="SELECT r.* FROM "+table+" r WHERE r.tenant_no=? AND r.deleted=0 AND r.id<? AND ";
  var args=new ArrayList<Object>(Arrays.asList(a.tenantNo(),before));
  if("BRAND".equals(type)){sql+="r.management_no=? AND ";args.add(m);}
  if(brandSummary){sql+="1=1";}else{
  sql+="(EXISTS(SELECT 1 FROM mch_management_grant g WHERE g.tenant_no=r.tenant_no AND g.management_no=? AND g.actor_no=? AND g.resource_type=? AND g.resource_no=r."+column+" AND g.permission_code=? AND g.status='ACTIVE' AND g.deleted=0)";
  args.addAll(Arrays.asList(m,a.actorKey(),type,permission));
  if("BRAND".equals(type)){sql+=" OR EXISTS(SELECT 1 FROM mch_management_space s WHERE s.tenant_no=r.tenant_no AND s.management_no=r.management_no AND s.owner_actor_no=? AND s.status='ACTIVE')";args.add(a.actorKey());}
  sql+=")";}
  if(keyword!=null&&!keyword.isBlank()){text(keyword,100);sql+=" AND r.name LIKE ?";args.add("%"+keyword.trim()+"%");}
  if(status!=null){sql+=" AND r.status=?";args.add(text(status,24));}
  if("STORE".equals(type)&&brand!=null){scopes.require(a,m,"BRAND",brand,"brand.read");sql+=" AND EXISTS(SELECT 1 FROM mch_brand_store_binding b WHERE b.tenant_no=r.tenant_no AND b.store_no=r.store_no AND b.brand_no=? AND b.status='ACTIVE' AND b.deleted=0)";args.add(brand);}
  sql+=" ORDER BY r.id DESC LIMIT ?";args.add(size+1);var raw=db.rows(sql,args.toArray());var chosen=raw.stream().limit(size).toList();
  var items=chosen.stream().filter(row->brandSummary||scopes.can(a,m,type,str(row,column),permission)).map(row->{
   // Organization summaries do not serialize full merchant entity/store rows.
   var result=new LinkedHashMap<String,Object>();result.put("resourceType",type);result.put("resourceNo",str(row,column));result.put(switch(type){case "BRAND"->"brandNo";case "ENTITY"->"entityNo";default->"storeNo";},str(row,column));result.put("name",str(row,"name"));result.put("status",str(row,"status"));result.put("revision",num(row,"version"));
   if("ENTITY".equals(type)){boolean owned=scopes.ownerAccounts(a).stream().anyMatch(account->Objects.equals(str(account,"entity_no"),str(row,"entity_no")));result.put("canManageLegacy",owned);result.put("canPrepareFinance",owned&&scopes.can(a,m,"SPACE",m,"management.grant.manage"));}
   if("STORE".equals(type))result.put("canConfigureBrand",scopes.can(a,m,"STORE",str(row,"store_no"),"store.brand.change"));
   if("STORE".equals(type)&&!brandSummary)result.put("entityNo",str(row,"entity_no"));
   if("BRAND".equals(type)){result.put("scopeRevision",num(row,"scope_revision"));result.put("storedValueStatus",str(row,"stored_value_status"));}return (Map<String,Object>)result;
  }).toList();
  String next=raw.size()>size?tokens.encode("RESOURCE_CURSOR",Map.of("scope",fingerprint,"before",num(chosen.getLast(),"id")),900):null;return new Page(items,next);
 }
 public Page resources(Actor a,String m,String type,String view,String actorNo,String cursor,int size){
  scopes.resolve(a,m);scopes.require(a,m,"SPACE",m,"management.resource.read");boolean manage="GRANTS".equals(view);
  if(!manage&&!"EFFECTIVE".equals(view))throw BizException.of(ErrorCode.BAD_REQUEST);
  if(manage)scopes.require(a,m,"SPACE",m,"management.grant.manage");
  if(!manage&&actorNo!=null&&!a.actorKey().equals(actorNo))throw BizException.of(ErrorCode.FORBIDDEN);
  String target=manage?actorNo:a.actorKey();String fingerprint=hash(db.encode(Arrays.asList(a.tenantNo(),a.actorKey(),m,type,view,target)));long before=Long.MAX_VALUE;
  if(size<1||size>100)throw BizException.of(ErrorCode.BAD_REQUEST);
  if(cursor!=null){var c=tokens.decode("RESOURCE_CURSOR",cursor);if(!fingerprint.equals(c.get("scope")))throw BizException.of(ErrorCode.CONFLICT);before=((Number)c.get("before")).longValue();}
  var raw=db.rows("SELECT * FROM mch_management_grant WHERE tenant_no=? AND management_no=? AND deleted=0 AND id<? AND (? IS NULL OR resource_type=?) AND (? IS NULL OR actor_no=?) ORDER BY id DESC LIMIT ?",a.tenantNo(),m,before,type,type,target,target,size+1);var chosen=raw.stream().limit(size).toList();
  var items=chosen.stream().filter(g->manage?scopes.canDelegate(a,m,str(g,"resource_type"),str(g,"resource_no"),str(g,"permission_code")):scopes.valid(a,g,new HashSet<>())).map(db::view).toList();
  return new Page(items,raw.size()>size?tokens.encode("RESOURCE_CURSOR",Map.of("scope",fingerprint,"before",num(chosen.getLast(),"id")),900):null);
 }
 public Page recipients(Actor a,String m,String cursor,int size){
  scopes.resolve(a,m);scopes.require(a,m,"SPACE",m,"management.resource.read");
  scopes.require(a,m,"SPACE",m,"management.grant.manage");if(size<1||size>100)throw BizException.of(ErrorCode.BAD_REQUEST);
  var entities=new TreeSet<String>();for(var owner:scopes.ownerAccounts(a))entities.add(str(owner,"entity_no"));
  for(var g:db.rows("SELECT * FROM mch_management_grant WHERE tenant_no=? AND management_no=? AND actor_no=? AND deleted=0",a.tenantNo(),m,a.actorKey()))if(bool(g,"delegatable")&&scopes.valid(a,g,new HashSet<>())){String e=scopes.resourceEntity(a,str(g,"resource_type"),str(g,"resource_no"));if(!e.isEmpty())entities.add(e);}
  if(entities.isEmpty())return new Page(List.of(),null);
  String fingerprint=hash(db.encode(List.of(a.tenantNo(),a.actorKey(),m,entities)));String after="";
  if(cursor!=null){var c=tokens.decode("RECIPIENT_CURSOR",cursor);if(!fingerprint.equals(c.get("scope")))throw BizException.of(ErrorCode.CONFLICT);after=(String)c.get("after");}
  var args=new ArrayList<Object>(List.of(a.tenantNo(),after));args.addAll(entities);args.add(size+1);
  var rows=db.rows("SELECT mch_account_no,display_name FROM mch_account WHERE tenant_no=? AND mch_account_no>? AND status='ACTIVE' AND deleted=0 AND entity_no IN ("+String.join(",",Collections.nCopies(entities.size(),"?"))+") ORDER BY mch_account_no LIMIT ?",args.toArray());
  var chosen=rows.stream().limit(size).toList();var items=chosen.stream().map(x->Map.<String,Object>of("actorNo","MCH:"+str(x,"mch_account_no"),"displayName",Objects.toString(x.get("display_name"),"—"))).toList();
  return new Page(items,rows.size()>size?tokens.encode("RECIPIENT_CURSOR",Map.of("scope",fingerprint,"after",str(chosen.getLast(),"mch_account_no")),900):null);
 }
 public Map<String,Object> operating(Actor a,String m,String store){
  scopes.resolve(a,m);scopes.require(a,m,"STORE",store,"store.read");var row=db.one("SELECT store_no,entity_no,name,status,version FROM mch_store WHERE tenant_no=? AND store_no=? AND deleted=0",a.tenantNo(),store);if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);
  var binding=db.one("SELECT b.brand_no,b.scope_revision,r.name,r.status,r.stored_value_status FROM mch_brand_store_binding b JOIN mch_brand r ON r.tenant_no=b.tenant_no AND r.brand_no=b.brand_no WHERE b.tenant_no=? AND b.store_no=? AND b.status='ACTIVE' AND b.deleted=0",a.tenantNo(),store);
  var result=db.view(row);result.put("managementNo",m);
  var pending=db.one("SELECT change_no,status,version FROM mch_brand_change WHERE tenant_no=? AND store_no=? AND status IN ('CHECKED','PREPARING') AND deleted=0",a.tenantNo(),store);
  var issuerActions=new java.util.ArrayList<>(List.of("store.read","store.brand.change","sv.issuer.read","sv.issuer.configure","sv.store.report").stream().filter(p->scopes.can(a,m,"STORE",store,p)).toList());
  if(scopes.can(a,m,"SPACE",m,"management.grant.manage")&&scopes.ownerAccounts(a).stream().anyMatch(account->str(row,"entity_no").equals(str(account,"entity_no"))))issuerActions.add("sv.issuer.prepare");
  if(pending!=null){pending=new java.util.LinkedHashMap<>(pending);pending.put("financial",db.one("SELECT change_no FROM mch_brand_financial_change WHERE tenant_no=? AND change_no=? AND deleted=0",a.tenantNo(),str(pending,"change_no"))!=null);}result.put("pendingChange",pending==null?null:db.view(pending));result.put("brand",binding==null?null:db.view(binding));result.put("storedValue",Map.of("store",Map.of("availability","LEGACY_STORE"),"brand",Map.of("availability","NOT_ENABLED")));result.put("actions",issuerActions);return result;
 }
 public Page audit(Actor a,String brand,String cursor,int size){
  var row=db.one("SELECT management_no FROM mch_brand WHERE tenant_no=? AND brand_no=? AND deleted=0",a.tenantNo(),brand);if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);String m=str(row,"management_no");scopes.resolve(a,m);scopes.require(a,m,"BRAND",brand,"brand.audit.read");
  if(size<1||size>100)throw BizException.of(ErrorCode.BAD_REQUEST);var scope=hash(a.tenantNo()+a.actorKey()+brand);long before=Long.MAX_VALUE;
  if(cursor!=null){var c=tokens.decode("AUDIT_CURSOR",cursor);if(!scope.equals(c.get("scope")))throw BizException.of(ErrorCode.CONFLICT);before=((Number)c.get("before")).longValue();}
  // All audit reads use the durable source, including unprojected events; no async read-after-write gap.
  var rows=db.rows("SELECT id,payload,created_at FROM sys_outbox WHERE aggregate_type='MANAGEMENT' AND aggregate_id=? AND (source_system='PLATFORM' OR source_system IS NULL) AND id<? ORDER BY id DESC LIMIT ?",a.tenantNo()+"|"+brand,before,size+1);
  var chosen=rows.stream().limit(size).toList();var items=chosen.stream().map(r->{var payload=db.decode(r.get("payload"));if(!a.tenantNo().equals(payload.get("tenantNo")))throw BizException.of(ErrorCode.FORBIDDEN);return Map.<String,Object>of("action",payload.get("action"),"actorKey",payload.get("actorKey"),"occurredAt",String.valueOf(r.get("created_at")),"result",payload.get("resultSnapshot"));}).toList();
  return new Page(items,rows.size()>size?tokens.encode("AUDIT_CURSOR",Map.of("scope",scope,"before",num(chosen.getLast(),"id")),900):null);
 }
}
