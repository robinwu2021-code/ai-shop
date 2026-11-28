package ai.neargo.shop.merchant.brand.impl;

import static ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import static ai.neargo.shop.merchant.brand.ManagementModels.BrandCreate;
import static ai.neargo.shop.merchant.brand.ManagementModels.BrandEdit;
import static ai.neargo.shop.merchant.brand.ManagementModels.ChangeCheck;
import static ai.neargo.shop.merchant.brand.ManagementModels.ChangeSubmit;
import static ai.neargo.shop.merchant.brand.ManagementModels.StatusChange;
import static ai.neargo.shop.merchant.brand.ManagementModels.text;
import static ai.neargo.shop.merchant.brand.ManagementRepository.hash;
import static ai.neargo.shop.merchant.brand.ManagementRepository.num;
import static ai.neargo.shop.merchant.brand.ManagementRepository.str;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.storedvalue.BrandChangeImpactPort;
import ai.neargo.shop.merchant.brand.BrandService;
import ai.neargo.shop.merchant.brand.ManagementModels;
import ai.neargo.shop.merchant.brand.ManagementRepository;
import ai.neargo.shop.merchant.brand.ManagementScopeService;
import ai.neargo.shop.merchant.brand.ManagementTokenCodec;
import ai.neargo.shop.numbering.DocumentSource;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.ArrayList;
import java.util.HashSet;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import ai.neargo.shop.merchant.brand.ManagementModels.BrandLegalEntity;

/** Brand lifecycle and atomic organization binding. This class never moves customer money. */
@Service("brandService")
public class BrandServiceImpl implements BrandService {
 private final ManagementRepository db;private final ManagementScopeService scopes;
 private StoreOwnershipWritePort entityWrites;
 @Autowired public void setEntityWrites(StoreOwnershipWritePort entityWrites){this.entityWrites=entityWrites;}
 private final ManagementTokenCodec tokens;private final BrandChangeImpactPort impacts;private final DocumentNumberCoordinator numbers;
 public BrandServiceImpl(ManagementRepository db,ManagementScopeService scopes,ManagementTokenCodec tokens,BrandChangeImpactPort impacts,
     @Qualifier("coreDocumentNumberCoordinator") DocumentNumberCoordinator numbers){this.db=db;this.scopes=scopes;this.tokens=tokens;this.impacts=impacts;this.numbers=numbers;this.admission=new ChainAdmissionPolicy(db);}
 /** 入连锁的准入（唯一入口） */
 private final ChainAdmissionPolicy admission;
 Map<String,Object> raw(Actor a,String no){var row=db.one("SELECT * FROM mch_brand WHERE tenant_no=? AND brand_no=? AND deleted=0",a.tenantNo(),no);if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);return row;}
 public Map<String,Object> detail(Actor a,String no){var row=raw(a,no);var m=str(row,"management_no");scopes.resolve(a,m);scopes.require(a,m,"BRAND",no,"brand.read");var result=db.view(row);result.put("storeCount",num(db.one("SELECT COUNT(*) AS total FROM mch_brand_store_binding WHERE tenant_no=? AND brand_no=? AND status='ACTIVE' AND deleted=0",a.tenantNo(),no),"total"));result.put("actions",List.of("brand.read","brand.manage","brand.audit.read","brand.change.decide","sv.issuer.read","sv.issuer.configure","sv.brand.activity.read","sv.brand.activity.manage","sv.brand.activity.publish","sv.brand.activity.approve","sv.brand.report","promotion.activity.read","promotion.activity.manage","promotion.activity.publish","promotion.coupon.read","promotion.coupon.manage","promotion.coupon.issue","promotion.cost.rule.manage").stream().filter(p->scopes.can(a,m,"BRAND",no,p)).toList());return result;}
 public Map<String,Object> create(Actor a,BrandCreate c,String key){
  var name=Normalizer.normalize(text(c.name(),100),Normalizer.Form.NFC);var status=c.status()==null?"DRAFT":c.status();
  if(!Set.of("DRAFT","ACTIVE").contains(status))throw BizException.of(ErrorCode.BAD_REQUEST);validateLogo(c.logo());text(key,64);
  var digest=hash(db.encode(c));return db.write(()->{
   scopes.lock(a,c.managementNo());scopes.resolve(a,c.managementNo());scopes.require(a,c.managementNo(),"SPACE",c.managementNo(),"brand.manage");
   // 建品牌的幂等走统一防重（原 create_key / hash 新库已去掉，Q10）
   String command=db.commandId(a,c.managementNo(),"BRAND_CREATE",key);
   var prior=db.replay(command,digest);if(prior!=null)return prior;
   var row=db.base(a);String no=BizKey.next(BizKey.BRAND);row.put("brand_no",no);row.put("management_no",c.managementNo());row.put("name",name);row.put("name_key",name);row.put("logo",c.logo());row.put("status",status);row.put("scope_revision",0);db.insert("mch_brand",row);
   // Creator receives only this brand, not any entity financial rights.
   scopes.seedBrandCreator(a,c.managementNo(),no);
   var result=db.view(row);db.emit(a,command,no,"BRAND_CREATE",digest,result);return result;
  });
 }
 private static void validateLogo(String logo){if(logo!=null&&!logo.isBlank()&&(!logo.startsWith("/")||logo.startsWith("//")||logo.contains("..")||logo.length()>512))throw BizException.of(ErrorCode.BAD_REQUEST);}
 public Map<String,Object> edit(Actor a,String no,BrandEdit c,String key){validateLogo(c.logo());var name=Normalizer.normalize(text(c.name(),100),Normalizer.Form.NFC);return mutate(a,no,"BRAND_EDIT",key,db.encode(c),c.expectedRevision(),()->db.update("UPDATE mch_brand SET name=?,name_key=?,logo=?,version=version+1,updated_at=?,updated_by=? WHERE tenant_no=? AND brand_no=? AND version=?",name,name,c.logo(),Timestamp.from(Instant.now()),a.actorKey(),a.tenantNo(),no,c.expectedRevision()));}
 public Map<String,Object> editResult(Actor a,String no,String key){
  var m=str(raw(a,no),"management_no");scopes.require(a,m,"SPACE",m,"management.enter");scopes.require(a,m,"BRAND",no,"brand.manage");
  return db.command(a,no,"BRAND_EDIT",key);
 }
 public List<Map<String,Object>> legalEntityCandidates(Actor a,String no){
  var m=str(raw(a,no),"management_no");scopes.require(a,m,"SPACE",m,"management.enter");scopes.require(a,m,"BRAND",no,"brand.manage");
  var candidates=new TreeSet<String>();scopes.ownerAccounts(a).forEach(r->candidates.add(str(r,"entity_no")));
  for(var g:db.rows("SELECT * FROM mch_grant WHERE grant_kind='PERMISSION' AND tenant_no=? AND management_no=? AND actor_no=? AND resource_type='ENTITY' AND permission_code='promotion.sponsor.authorize' AND deleted=0",a.tenantNo(),m,a.actorKey()))if(scopes.valid(a,g,new HashSet<>()))candidates.add(str(g,"resource_no"));
  var result=new ArrayList<Map<String,Object>>();
  for(var entity:candidates){var row=db.one("SELECT entity_no,name FROM mch_entity WHERE tenant_no=? AND entity_no=? AND status='ACTIVE' AND ownership_change_no IS NULL AND deleted=0",a.tenantNo(),entity);if(row!=null)result.add(Map.of("entityNo",entity,"name",str(row,"name")));}
  return List.copyOf(result);
 }
 public Map<String,Object> bindLegalEntity(Actor a,String no,BrandLegalEntity c,String key){
  return mutate(a,no,"BRAND_LEGAL_ENTITY",key,db.encode(c),c.expectedRevision(),()->{
   String m=str(raw(a,no),"management_no");entityWrites.lockEntityAndCheck(a.tenantNo(),c.entityNo());
   var entity=db.one("SELECT entity_no FROM mch_entity WHERE tenant_no=? AND entity_no=? AND status='ACTIVE' AND deleted=0 FOR SHARE",a.tenantNo(),c.entityNo());
   if(entity==null)throw BizException.of(ErrorCode.NOT_FOUND);
   if(scopes.ownerAccounts(a).stream().noneMatch(r->c.entityNo().equals(str(r,"entity_no")))&&!scopes.can(a,m,"ENTITY",c.entityNo(),"promotion.sponsor.authorize"))throw BizException.of(ErrorCode.FORBIDDEN);
   return db.update("UPDATE mch_brand SET legal_entity_no=?,version=version+1,updated_at=?,updated_by=? WHERE tenant_no=? AND brand_no=? AND version=? AND legal_entity_no IS NULL",c.entityNo(),Timestamp.from(Instant.now()),a.actorKey(),a.tenantNo(),no,c.expectedRevision());
  });
 }
 public Map<String,Object> legalEntityResult(Actor a,String no,String key){
  var m=str(raw(a,no),"management_no");scopes.require(a,m,"SPACE",m,"management.enter");scopes.require(a,m,"BRAND",no,"brand.manage");return db.command(a,no,"BRAND_LEGAL_ENTITY",key);
 }
 public Map<String,Object> status(Actor a,String no,StatusChange c,String key){
  if(!Set.of("ACTIVE","INACTIVE").contains(c.targetStatus()))throw BizException.of(ErrorCode.BAD_REQUEST);text(c.reason(),255);
  return mutate(a,no,"BRAND_STATUS",key,db.encode(c),c.expectedRevision(),()->{
   if("INACTIVE".equals(c.targetStatus())&&(db.one("SELECT binding_no FROM mch_brand_store_binding WHERE tenant_no=? AND brand_no=? AND status='ACTIVE'",a.tenantNo(),no)!=null))throw BizException.of(ErrorCode.CONFLICT);
   return db.update("UPDATE mch_brand SET status=?,version=version+1,updated_at=?,updated_by=? WHERE tenant_no=? AND brand_no=? AND version=?",c.targetStatus(),Timestamp.from(Instant.now()),a.actorKey(),a.tenantNo(),no,c.expectedRevision());
  });
 }
 private Map<String,Object> mutate(Actor a,String no,String action,String key,String body,long revision,java.util.function.IntSupplier update){
  var m=str(raw(a,no),"management_no");return db.write(()->{
  scopes.lock(a,m);var row=raw(a,no);if("BRAND_STATUS".equals(action)&&str(row,"stored_value_status")!=null&&!"NONE".equals(str(row,"stored_value_status")))throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);if(!m.equals(str(row,"management_no")))throw BizException.of(ErrorCode.CONFLICT);scopes.resolve(a,m);scopes.require(a,m,"BRAND",no,"brand.manage");
  var id=db.commandId(a,no,action,key);var digest=hash(body);var replay=db.replay(id,digest);if(replay!=null)return replay;
  row=db.one("SELECT * FROM mch_brand WHERE tenant_no=? AND brand_no=? FOR UPDATE",a.tenantNo(),no);
  if(num(row,"version")!=revision||update.getAsInt()!=1)throw BizException.of(ErrorCode.CONFLICT);
  var result=db.view(raw(a,no));db.emit(a,id,no,action,digest,result);return result;
 });}
 Map<String,Object> store(Actor a,String m,String store){scopes.resolve(a,m);scopes.require(a,m,"STORE",store,"store.read");var row=db.one("SELECT * FROM mch_store WHERE tenant_no=? AND store_no=? AND deleted=0",a.tenantNo(),store);if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);return row;}
 Map<String,Object> binding(Actor a,String store){return db.one("SELECT * FROM mch_brand_store_binding WHERE tenant_no=? AND store_no=? AND status='ACTIVE' AND deleted=0",a.tenantNo(),store);}
 private Map<String,Object> facts(Actor a,String store,ChangeCheck c){
  var st=store(a,c.managementNo(),store);scopes.require(a,c.managementNo(),"STORE",store,"store.brand.change");
  if(!"ACTIVE".equals(str(st,"status")))throw BizException.of(ErrorCode.CONFLICT);
  var old=binding(a,store);String from=str(old,"brand_no"),to=c.toBrandNo();if(Objects.equals(from,to))throw BizException.of(ErrorCode.BAD_REQUEST);
  Long fromRevision=null,toRevision=null;
  for(String no:new String[]{from,to})if(no!=null){var brand=raw(a,no);if(str(brand,"stored_value_status")!=null&&!"NONE".equals(str(brand,"stored_value_status")))throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);if(!c.managementNo().equals(str(brand,"management_no")))throw BizException.of(ErrorCode.FORBIDDEN);scopes.require(a,c.managementNo(),"BRAND",no,"brand.manage");if(no.equals(to)&&!"ACTIVE".equals(str(brand,"status")))throw BizException.of(ErrorCode.CONFLICT);if(no.equals(from))fromRevision=num(brand,"scope_revision");else toRevision=num(brand,"scope_revision");}
  var result=new LinkedHashMap<String,Object>();result.put("tenantNo",a.tenantNo());result.put("actorKey",a.actorKey());result.put("managementNo",c.managementNo());result.put("storeNo",store);result.put("entityNo",str(st,"entity_no"));result.put("fromBrandNo",from);result.put("toBrandNo",to);result.put("storeVersion",num(st,"version"));result.put("bindingRevision",num(old,"scope_revision"));result.put("fromRevision",fromRevision);result.put("toRevision",toRevision);return result;
 }
 public Map<String,Object> check(Actor a,String store,ChangeCheck c){
  var result=facts(a,store,c);var impact=impacts.inspect(a.tenantNo(),(String)result.get("entityNo"),store);
  var admitted=c.toBrandNo()==null?null:admission.admit(a.tenantNo(),store);var blockers=blockers(impact,admitted);
  result.put("allowed",blockers.isEmpty());result.put("blockers",blockers);result.put("checkedAt",Instant.now().toString());result.put("impact",impact);
  result.put("admission",admissionView(admitted));result.put("nextAction",blockers.contains(ChainAdmissionPolicy.BLOCKER)?"BUY":null);
  result.put("checkToken",tokens.encode("BRAND_CHANGE",result,60));return result;
 }
 /**
  * 入退连锁当场生效，不走审批（TDD-平台基础库换表「连锁按权益准入」）：校验令牌、版本号、权限码、影响检查与准入，通过即改绑定。
  * 影响检查或准入不过时不落任何行，只返回阻断项。
  */
 public Map<String,Object> submit(Actor a,String store,ChangeSubmit c,String key){
  store(a,c.managementNo(),store);scopes.require(a,c.managementNo(),"STORE",store,"store.brand.change");
  String commandId=db.commandId(a,store,"BRAND_CHANGE",key);String digest=hash(db.encode(c));var original=db.replay(commandId,digest);if(original!=null)return original;
  var signed=tokens.decode("BRAND_CHANGE",c.checkToken());var facts=facts(a,store,new ChangeCheck(c.managementNo(),c.toBrandNo()));
  verifyFacts(signed,facts);if(c.expectedStoreRevision()!=((Number)facts.get("storeVersion")).longValue()||c.expectedScopeRevision()!=((Number)facts.get("bindingRevision")).longValue()||!Objects.equals(c.expectedFromBrandRevision(),asLong(facts.get("fromRevision")))||!Objects.equals(c.expectedToBrandRevision(),asLong(facts.get("toRevision"))))throw BizException.of(ErrorCode.CONFLICT);
  if(c.reason()!=null)text(c.reason(),255);
  var impact=impacts.inspect(a.tenantNo(),(String)facts.get("entityNo"),store);var admitted=c.toBrandNo()==null?null:admission.admit(a.tenantNo(),store);var blockers=blockers(impact,admitted);
  if(!blockers.isEmpty()){var rejected=outcome(c.managementNo(),store,facts,null,null,admitted);rejected.put("applied",false);rejected.put("blockers",blockers);return rejected;}
  return numbers.withReservation(Map.of(DocumentType.BRAND_CHANGE,1),()->db.write(()->{
   scopes.lock(a,c.managementNo());var replay=db.replay(commandId,digest);if(replay!=null)return replay;
   lockBrandStores(a,facts);verifyFacts(facts,facts(a,store,new ChangeCheck(c.managementNo(),c.toBrandNo())));
   // 储值期间入退在途的门店不能再走普通入退
   if(db.one("SELECT change_no FROM mch_brand_financial_change WHERE tenant_no=? AND in_flight_store_no=? AND deleted=0",a.tenantNo(),store)!=null)throw BizException.of(ErrorCode.CONFLICT);
   String from=(String)facts.get("fromBrandNo"),to=c.toBrandNo();var basis=to==null?null:admission.admit(a.tenantNo(),store);if(basis!=null&&!basis.allowed())throw BizException.of(ErrorCode.CONFLICT);
   String no=numbers.bind(DocumentType.BRAND_CHANGE,new DocumentSource(a.tenantNo(),"MERCHANT","BRAND_CHANGE",commandId));var at=Timestamp.from(Instant.now());
   db.update("UPDATE mch_brand_store_binding SET status='ENDED',active_store_no=NULL,effective_to=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND store_no=? AND status='ACTIVE'",at,at,a.actorKey(),a.tenantNo(),store);
   for(String b:new String[]{from,to})if(b!=null)db.update("UPDATE mch_brand SET scope_revision=scope_revision+1,version=version+1,updated_at=?,updated_by=? WHERE tenant_no=? AND brand_no=?",at,a.actorKey(),a.tenantNo(),b);
   String bindingNo=null;
   if(to!=null){var binding=db.base(a);bindingNo=BizKey.next(BizKey.BRAND_BINDING);binding.put("binding_no",bindingNo);binding.put("brand_no",to);binding.put("store_no",store);binding.put("status","ACTIVE");binding.put("active_store_no",store);binding.put("effective_from",at);binding.put("effective_to",null);binding.put("scope_revision",num(raw(a,to),"scope_revision"));binding.put("change_no",no);binding.put("admission_basis",basis.basis());db.insert("mch_brand_store_binding",binding);}
   if(db.update("UPDATE mch_store SET version=version+1 WHERE tenant_no=? AND store_no=? AND version=?",a.tenantNo(),store,c.expectedStoreRevision())!=1)throw BizException.of(ErrorCode.CONFLICT);
   var result=outcome(c.managementNo(),store,facts,no,bindingNo,basis);result.put("applied",true);result.put("blockers",List.of());
   db.emit(a,commandId,store,"BRAND_CHANGE_APPLIED",digest,result);
   for(String brand:new String[]{from,to})if(brand!=null)db.emit(a,hash(commandId+"|"+brand),brand,"BRAND_STORE_CHANGED",digest,result);
   return result;
  }));
 }
 private static List<String> blockers(BrandChangeImpactPort.Impact impact,ChainAdmissionPolicy.Admission admitted){
  var out=new ArrayList<String>();if(impact.blocker()!=null)out.add(impact.blocker());if(admitted!=null&&!admitted.allowed())out.add(ChainAdmissionPolicy.BLOCKER);return out;
 }
 private static Map<String,Object> admissionView(ChainAdmissionPolicy.Admission admitted){
  var view=new LinkedHashMap<String,Object>();view.put("required",ChainAdmissionPolicy.ENTITLEMENT);view.put("basis",admitted==null?null:admitted.basis());view.put("entitledUntil",admitted==null?null:admitted.entitledUntil());return view;
 }
 private static LinkedHashMap<String,Object> outcome(String management,String store,Map<String,Object> facts,String changeNo,String bindingNo,ChainAdmissionPolicy.Admission admitted){
  var result=new LinkedHashMap<String,Object>();result.put("changeNo",changeNo);result.put("managementNo",management);result.put("storeNo",store);result.put("fromBrandNo",facts.get("fromBrandNo"));result.put("toBrandNo",facts.get("toBrandNo"));result.put("bindingNo",bindingNo);result.put("admissionBasis",admitted==null?null:admitted.basis());return result;
 }
 private static Long asLong(Object o){return o==null?null:((Number)o).longValue();}
 private void verifyFacts(Map<String,Object> expected,Map<String,Object> actual){for(String k:List.of("tenantNo","actorKey","managementNo","storeNo","entityNo","fromBrandNo","toBrandNo","storeVersion","bindingRevision","fromRevision","toRevision"))if(!Objects.equals(String.valueOf(expected.get(k)),String.valueOf(actual.get(k))))throw BizException.of(ErrorCode.CONFLICT);}
 private void lockBrandStores(Actor a,Map<String,Object> facts){
  java.util.stream.Stream.of(facts.get("fromBrandNo"),facts.get("toBrandNo")).filter(Objects::nonNull).map(Object::toString).sorted().forEach(b->db.one("SELECT brand_no FROM mch_brand WHERE tenant_no=? AND brand_no=? FOR UPDATE",a.tenantNo(),b));
  db.one("SELECT store_no FROM mch_store WHERE tenant_no=? AND store_no=? FOR UPDATE",a.tenantNo(),facts.get("storeNo"));
 }
}
