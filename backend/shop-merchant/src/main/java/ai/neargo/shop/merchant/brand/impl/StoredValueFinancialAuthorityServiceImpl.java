package ai.neargo.shop.merchant.brand.impl;

import ai.neargo.shop.merchant.brand.ManagementRepository;
import ai.neargo.shop.merchant.brand.ManagementScopeService;
import ai.neargo.shop.merchant.brand.StoredValueFinancialAuthorityService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.merchant.brand.ManagementModels.GrantCommand;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.AuthorizedScope;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.ScopeRef;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.View;
import ai.neargo.shop.spi.storedvalue.StoredValueReports;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import static ai.neargo.shop.merchant.brand.ManagementRepository.num;
import static ai.neargo.shop.merchant.brand.ManagementRepository.str;
/** Live Core authorization before Pay calls. Space/brand ownership never grants another entity financial powers. */
@Service("storedValueFinancialAuthorityService")
public class StoredValueFinancialAuthorityServiceImpl implements StoredValueFinancialAuthorityService {
 private final ManagementRepository db;private final ManagementScopeService scopes;
 public StoredValueFinancialAuthorityServiceImpl(ManagementRepository db,ManagementScopeService scopes){this.db=db;this.scopes=scopes;}
 public String principal(Actor actor){if(actor.actorKey().startsWith("USER:"))return actor.actorKey();var row=db.one("SELECT user_no FROM mch_account WHERE tenant_no=? AND mch_account_no=? AND status='ACTIVE' AND deleted=0",actor.tenantNo(),actor.actorKey().substring(4));if(row==null)throw BizException.of(ErrorCode.FORBIDDEN);return str(row,"user_no")==null?actor.actorKey():"USER:"+str(row,"user_no");}
 public AuthorizedScope reportLookup(Actor actor,String management,ScopeRef ref,String permission){
  scopes.resolve(actor,management);String entity=resource(actor,management,ref);
  if(!Set.of("sv.brand.report","sv.store.report","sv.store.report.all","sv.activity.report").contains(permission))throw BizException.of(ErrorCode.BAD_REQUEST);
  if("sv.store.report.all".equals(permission)){if(!"STORE".equals(ref.scopeType())||!businessStores(actor,management).contains(ref))throw BizException.of(ErrorCode.FORBIDDEN);}
  else if("sv.store.report".equals(permission)&&"STORE".equals(ref.scopeType())){
   boolean own=scopes.can(actor,management,"SPACE",management,"sv.store.report.all")&&scopes.ownerAccounts(actor).stream().anyMatch(a->entity.equals(str(a,"entity_no")));
   if(!own)scopes.require(actor,management,"STORE",ref.scopeNo(),permission);
  }else{if("sv.brand.report".equals(permission)&&!"BRAND".equals(ref.scopeType()))throw BizException.of(ErrorCode.FORBIDDEN);scopes.require(actor,management,ref.scopeType(),ref.scopeNo(),permission);}
  return lookup(actor,management,ref,entity);
 }
 public StoredValueReports.Scope report(Actor actor,String management,View issuer,String permission,String activity,String store){
  var ref=new ScopeRef(issuer.scopeType(),issuer.scopeNo());reportLookup(actor,management,ref,permission);return new StoredValueReports.Scope(actor.tenantNo(),actor.actorKey(),management,issuer.issuerNo(),issuer.scopeType(),issuer.scopeNo(),issuer.currency(),activity,store);
 }
 public AuthorizedScope financialLookup(Actor actor,String management,ScopeRef ref){scopes.resolve(actor,management);String entity=resource(actor,management,ref);var entities=financialEntities(actor,management);if(entities.isEmpty())throw BizException.of(ErrorCode.FORBIDDEN);requireFinanceStore(ref,entity,entities);return lookup(actor,management,ref,entity);}
 public StoredValueSettlements.Authority financial(Actor actor,String management,List<StoredValueSettlements.IssuerGrant> issuers,Set<String> verifiedEvidenceRefs){
  scopes.resolve(actor,management);var permissions=financialEntities(actor,management);if(permissions.isEmpty())throw BizException.of(ErrorCode.FORBIDDEN);for(var grant:issuers){var ref=new ScopeRef(grant.scopeType(),grant.scopeNo());requireFinanceStore(ref,resource(actor,management,ref),permissions);}return new StoredValueSettlements.Authority(actor.tenantNo(),actor.actorKey(),principal(actor),management,issuers,permissions,verifiedEvidenceRefs);
 }
 public Map<String,Set<String>> financialEntities(Actor actor,String management){
  var rows=db.rows("SELECT DISTINCT resource_no FROM mch_management_grant WHERE tenant_no=? AND management_no=? AND actor_no=? AND resource_type='ENTITY' AND status='ACTIVE' AND deleted=0 ORDER BY resource_no LIMIT 1001",actor.tenantNo(),management,actor.actorKey());if(rows.size()>1000)throw BizException.of(ErrorCode.CONFLICT);var result=new TreeMap<String,Set<String>>();
  for(var row:rows){String no=str(row,"resource_no");if(db.one("SELECT entity_no FROM mch_entity WHERE tenant_no=? AND entity_no=? AND deleted=0",actor.tenantNo(),no)==null)continue;var allowed=new TreeSet<String>();for(String permission:FINANCIAL)if(scopes.can(actor,management,"ENTITY",no,permission))allowed.add(permission);if(!allowed.isEmpty())result.put(no,Collections.unmodifiableSet(allowed));}
  return Collections.unmodifiableMap(result);
 }
 public List<ScopeRef> financeResources(Actor actor,String management,String type){
  scopes.resolve(actor,management);var entities=financialEntities(actor,management);if(entities.isEmpty())throw BizException.of(ErrorCode.FORBIDDEN);var result=new ArrayList<ScopeRef>();
  if(type==null||"BRAND".equals(type)){var brands=db.rows("SELECT brand_no FROM mch_brand WHERE tenant_no=? AND management_no=? AND deleted=0 ORDER BY brand_no LIMIT 1001",actor.tenantNo(),management);if(brands.size()>1000)throw BizException.of(ErrorCode.CONFLICT);for(var b:brands)result.add(new ScopeRef("BRAND",str(b,"brand_no")));}
  if(type==null||"STORE".equals(type)){var args=new ArrayList<Object>();args.add(actor.tenantNo());args.addAll(entities.keySet());var stores=db.rows("SELECT store_no FROM mch_store WHERE tenant_no=? AND entity_no IN ("+String.join(",",Collections.nCopies(entities.size(),"?"))+") AND deleted=0 ORDER BY store_no LIMIT 1001",args.toArray());if(stores.size()>1000)throw BizException.of(ErrorCode.CONFLICT);for(var st:stores)result.add(new ScopeRef("STORE",str(st,"store_no")));}
  if(type!=null&&!Set.of("BRAND","STORE").contains(type))throw BizException.of(ErrorCode.BAD_REQUEST);return List.copyOf(result);
 }
 public List<ScopeRef> businessStores(Actor actor,String management){
  scopes.resolve(actor,management);scopes.require(actor,management,"SPACE",management,"sv.store.report.all");var owned=scopes.ownerAccounts(actor).stream().map(a->str(a,"entity_no")).collect(Collectors.toSet());
  var registered=db.rows("SELECT DISTINCT resource_no FROM mch_management_grant WHERE tenant_no=? AND management_no=? AND actor_no=? AND resource_type='STORE' AND permission_code='sv.store.report' AND status='ACTIVE' AND deleted=0 ORDER BY resource_no LIMIT 1001",actor.tenantNo(),management,actor.actorKey()).stream().map(r->str(r,"resource_no")).toList();if(registered.size()>1000||owned.size()>1000)throw BizException.of(ErrorCode.CONFLICT);if(owned.isEmpty()&&registered.isEmpty())return List.of();var args=new ArrayList<Object>();args.add(actor.tenantNo());String condition="";if(!owned.isEmpty()){condition="entity_no IN ("+String.join(",",Collections.nCopies(owned.size(),"?"))+")";args.addAll(owned);}if(!registered.isEmpty()){condition+=(condition.isEmpty()?"":" OR ")+"store_no IN ("+String.join(",",Collections.nCopies(registered.size(),"?"))+")";args.addAll(registered);}var rows=db.rows("SELECT store_no,entity_no FROM mch_store WHERE tenant_no=? AND ("+condition+") AND deleted=0 ORDER BY store_no LIMIT 1001",args.toArray());if(rows.size()>1000)throw BizException.of(ErrorCode.CONFLICT);return rows.stream().filter(s->owned.contains(str(s,"entity_no"))||scopes.can(actor,management,"STORE",str(s,"store_no"),"sv.store.report")).map(s->new ScopeRef("STORE",str(s,"store_no"))).toList();
 }
 public ai.neargo.shop.spi.storedvalue.StoredValueCollections.Proof collectionProof(Actor actor,ai.neargo.shop.spi.storedvalue.StoredValueWallets.ExecutionScope scope,ai.neargo.shop.spi.storedvalue.StoredValueCollections.Requirement requirement){
  if(requirement==null||!requirement.required())return null;
  if(!scope.equals(requirement.scope())||!actor.tenantNo().equals(scope.tenantNo())||!(actor.actorKey().equals(scope.actorNo())||actor.actorKey().substring(actor.actorKey().indexOf(':')+1).equals(scope.actorNo())))throw BizException.of(ErrorCode.FORBIDDEN);
  if(!canCollect(actor,scope.managementNo(),requirement.settlementEntityNo()))return null;
  return new ai.neargo.shop.spi.storedvalue.StoredValueCollections.Proof(requirement,actor.actorKey(),num(scopes.space(actor,scope.managementNo()),"auth_revision"),java.time.Instant.now().plusSeconds(60));
 }
 public boolean canCollect(Actor actor,String management,String entity){return scopes.can(actor,management,"ENTITY",entity,"sv.issuer.collection.confirm");}
 public Map<String,Object> prepare(Actor actor,String management,String entity){
  scopes.resolve(actor,management);scopes.require(actor,management,"SPACE",management,"management.grant.manage");if(scopes.ownerAccounts(actor).stream().noneMatch(a->entity.equals(str(a,"entity_no"))))throw BizException.of(ErrorCode.FORBIDDEN);
  for(String permission:FINANCIAL){if(scopes.can(actor,management,"ENTITY",entity,permission))continue;var prior=db.one("SELECT status,version FROM mch_management_grant WHERE tenant_no=? AND management_no=? AND actor_no=? AND resource_type='ENTITY' AND resource_no=? AND permission_code=? AND deleted=0",actor.tenantNo(),management,actor.actorKey(),entity,permission);scopes.grant(actor,management,new GrantCommand(actor.actorKey(),"ENTITY",entity,permission,null,null,false,prior==null?null:num(prior,"version"),prior!=null));}
  return Map.of("prepared",true,"entityNo",entity,"permissions",FINANCIAL.stream().sorted().toList());
 }
 /**
  * 门店口径的财务范围 = 我在这个经营空间里持有财务权的主体名下的店 —— 与 {@link #financeResources} 列给他看的那份同一个集合。
  * 品牌由 management_no 圈住了；门店此前只判「租户里有这家店」，于是 A 空间的财务能把任意一家店（别的空间、别的商家）
  * 塞进 financialLookup / financial，按那家店的主体去查结算。
  */
 private void requireFinanceStore(ScopeRef ref,String entity,Map<String,Set<String>> entities){if("STORE".equals(ref.scopeType())&&(entity==null||!entities.containsKey(entity)))throw BizException.of(ErrorCode.FORBIDDEN);}
 private AuthorizedScope lookup(Actor actor,String management,ScopeRef ref,String entity){return new AuthorizedScope(actor.tenantNo(),actor.actorKey(),management,ref.scopeType(),ref.scopeNo(),num(scopes.space(actor,management),"auth_revision"),entity,Set.of(),List.of());}
 private String resource(Actor actor,String management,ScopeRef ref){
  if("BRAND".equals(ref.scopeType())){if(db.one("SELECT brand_no FROM mch_brand WHERE tenant_no=? AND management_no=? AND brand_no=? AND deleted=0",actor.tenantNo(),management,ref.scopeNo())==null)throw BizException.of(ErrorCode.NOT_FOUND);return null;}
  if("STORE".equals(ref.scopeType())){var row=db.one("SELECT entity_no FROM mch_store WHERE tenant_no=? AND store_no=? AND deleted=0",actor.tenantNo(),ref.scopeNo());if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);return str(row,"entity_no");}throw BizException.of(ErrorCode.BAD_REQUEST);
 }
}
