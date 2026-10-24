package ai.neargo.shop.merchant.brand.impl;

import static ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import static ai.neargo.shop.merchant.brand.ManagementModels.BrandCreate;
import static ai.neargo.shop.merchant.brand.ManagementModels.BrandEdit;
import static ai.neargo.shop.merchant.brand.ManagementModels.ChangeCheck;
import static ai.neargo.shop.merchant.brand.ManagementModels.ChangeDecision;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Brand lifecycle and atomic organization binding. This class never moves customer money. */
@Service("brandService")
public class BrandServiceImpl implements BrandService {
 private final ManagementRepository db;private final ManagementScopeService scopes;
 private final ManagementTokenCodec tokens;private final BrandChangeImpactPort impacts;private final DocumentNumberCoordinator numbers;
 public BrandServiceImpl(ManagementRepository db,ManagementScopeService scopes,ManagementTokenCodec tokens,BrandChangeImpactPort impacts,
     @Qualifier("coreDocumentNumberCoordinator") DocumentNumberCoordinator numbers){this.db=db;this.scopes=scopes;this.tokens=tokens;this.impacts=impacts;this.numbers=numbers;}
 Map<String,Object> raw(Actor a,String no){var row=db.one("SELECT * FROM mch_brand WHERE tenant_no=? AND brand_no=? AND deleted=0",a.tenantNo(),no);if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);return row;}
 public Map<String,Object> detail(Actor a,String no){var row=raw(a,no);var m=str(row,"management_no");scopes.resolve(a,m);scopes.require(a,m,"BRAND",no,"brand.read");var result=db.view(row);result.put("storeCount",num(db.one("SELECT COUNT(*) AS total FROM mch_brand_store_binding WHERE tenant_no=? AND brand_no=? AND status='ACTIVE' AND deleted=0",a.tenantNo(),no),"total"));result.put("actions",List.of("brand.read","brand.manage","brand.audit.read","brand.change.decide","sv.issuer.read","sv.issuer.configure","sv.brand.activity.read","sv.brand.activity.manage","sv.brand.activity.publish","sv.brand.activity.approve").stream().filter(p->scopes.can(a,m,"BRAND",no,p)).toList());return result;}
 public Map<String,Object> create(Actor a,BrandCreate c,String key){
  var name=Normalizer.normalize(text(c.name(),100),Normalizer.Form.NFC);var status=c.status()==null?"DRAFT":c.status();
  if(!Set.of("DRAFT","ACTIVE").contains(status))throw BizException.of(ErrorCode.BAD_REQUEST);validateLogo(c.logo());text(key,64);
  var digest=hash(db.encode(c));return db.write(()->{
   scopes.lock(a,c.managementNo());scopes.resolve(a,c.managementNo());scopes.require(a,c.managementNo(),"SPACE",c.managementNo(),"brand.manage");
   var old=db.one("SELECT * FROM mch_brand WHERE tenant_no=? AND management_no=? AND create_key=?",a.tenantNo(),c.managementNo(),key);
   if(old!=null){if(!digest.equals(str(old,"create_hash")))throw BizException.of(ErrorCode.CONFLICT);return db.view(old);}
   var row=db.base(a);String no=BizKey.next(BizKey.BRAND);row.put("brand_no",no);row.put("management_no",c.managementNo());row.put("name",name);row.put("name_key",name);row.put("logo",c.logo());row.put("status",status);row.put("scope_revision",0);row.put("create_key",key);row.put("create_hash",digest);db.insert("mch_brand",row);
   // Creator receives only this brand, not any entity financial rights.
   scopes.seedBrandCreator(a,c.managementNo(),no);
   var result=db.view(row);db.emit(a,db.commandId(a,c.managementNo(),"BRAND_CREATE",key),no,"BRAND_CREATE",digest,result);return result;
  });
 }
 private static void validateLogo(String logo){if(logo!=null&&!logo.isBlank()&&(!logo.startsWith("/")||logo.startsWith("//")||logo.contains("..")||logo.length()>512))throw BizException.of(ErrorCode.BAD_REQUEST);}
 public Map<String,Object> edit(Actor a,String no,BrandEdit c,String key){validateLogo(c.logo());var name=Normalizer.normalize(text(c.name(),100),Normalizer.Form.NFC);return mutate(a,no,"BRAND_EDIT",key,db.encode(c),c.expectedRevision(),()->db.update("UPDATE mch_brand SET name=?,name_key=?,logo=?,version=version+1,updated_at=?,updated_by=? WHERE tenant_no=? AND brand_no=? AND version=?",name,name,c.logo(),Timestamp.from(Instant.now()),a.actorKey(),a.tenantNo(),no,c.expectedRevision()));}
 public Map<String,Object> status(Actor a,String no,StatusChange c,String key){
  if(!Set.of("ACTIVE","INACTIVE").contains(c.targetStatus()))throw BizException.of(ErrorCode.BAD_REQUEST);text(c.reason(),255);
  return mutate(a,no,"BRAND_STATUS",key,db.encode(c),c.expectedRevision(),()->{
   if("INACTIVE".equals(c.targetStatus())&&(db.one("SELECT binding_no FROM mch_brand_store_binding WHERE tenant_no=? AND brand_no=? AND status='ACTIVE'",a.tenantNo(),no)!=null||db.one("SELECT change_no FROM mch_brand_change WHERE tenant_no=? AND (from_brand_no=? OR to_brand_no=?) AND status IN ('CHECKED','PREPARING')",a.tenantNo(),no,no)!=null))throw BizException.of(ErrorCode.CONFLICT);
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
  var result=facts(a,store,c);var impact=impacts.inspect(a.tenantNo(),(String)result.get("entityNo"),store);var blocker=impact.blocker();result.put("allowed",blocker==null);result.put("blockers",blocker==null?List.of():List.of(blocker));result.put("checkedAt",Instant.now().toString());result.put("impact",impact);result.put("canDecide",canDecide(a,c.managementNo(),(String)result.get("fromBrandNo"),c.toBrandNo()));
  result.put("checkToken",tokens.encode("BRAND_CHANGE",result,60));return result;
 }
 public Map<String,Object> submit(Actor a,String store,ChangeSubmit c,String key){
  store(a,c.managementNo(),store);scopes.require(a,c.managementNo(),"STORE",store,"store.brand.change");
  String originalCommand=db.commandId(a,store,"BRAND_CHANGE",key);String originalDigest=hash(db.encode(c));var originalResult=db.replay(originalCommand,originalDigest);
  if(originalResult!=null){change(a,(String)originalResult.get("changeNo"));return originalResult;}
  var signed=tokens.decode("BRAND_CHANGE",c.checkToken());var facts=facts(a,store,new ChangeCheck(c.managementNo(),c.toBrandNo()));
  verifyFacts(signed,facts);if(c.expectedStoreRevision()!=((Number)facts.get("storeVersion")).longValue()||c.expectedScopeRevision()!=((Number)facts.get("bindingRevision")).longValue()||!Objects.equals(c.expectedFromBrandRevision(),asLong(facts.get("fromRevision")))||!Objects.equals(c.expectedToBrandRevision(),asLong(facts.get("toRevision"))))throw BizException.of(ErrorCode.CONFLICT);
  var impact=impacts.inspect(a.tenantNo(),(String)facts.get("entityNo"),store);String digest=hash(db.encode(c));String commandId=db.commandId(a,store,"BRAND_CHANGE",key);
  return numbers.withReservation(Map.of(DocumentType.BRAND_CHANGE,1),()->db.write(()->{
   scopes.lock(a,c.managementNo());var replay=db.replay(commandId,digest);if(replay!=null)return replay;
   lockBrandStores(a,facts);verifyFacts(facts,facts(a,store,new ChangeCheck(c.managementNo(),c.toBrandNo())));
   if(db.one("SELECT change_no FROM mch_brand_change WHERE tenant_no=? AND store_no=? AND status IN ('CHECKED','PREPARING')",a.tenantNo(),store)!=null)throw BizException.of(ErrorCode.CONFLICT);
   var row=db.base(a);String no=numbers.bind(DocumentType.BRAND_CHANGE,new DocumentSource(a.tenantNo(),"MERCHANT","BRAND_CHANGE",commandId));
   row.put("change_no",no);row.put("management_no",c.managementNo());row.put("store_no",store);row.put("from_brand_no",facts.get("fromBrandNo"));row.put("to_brand_no",facts.get("toBrandNo"));row.put("expected_scope_revision",c.expectedScopeRevision());row.put("expected_store_version",c.expectedStoreRevision());row.put("expected_from_brand_revision",c.expectedFromBrandRevision());row.put("expected_to_brand_revision",c.expectedToBrandRevision());row.put("status",impact.blocker()==null?"CHECKED":"BLOCKED");row.put("check_snapshot",db.encode(Map.of("facts",facts,"impact",impact)));row.put("authorization_snapshot",db.encode(Map.of("actor",a.actorKey(),"managementNo",c.managementNo())));row.put("reason",c.reason()==null?null:text(c.reason(),255));row.put("command_key",text(key,64));row.put("command_hash",digest);row.put("check_expires_at",Timestamp.from(Instant.now().plusSeconds(60)));row.put("submitted_by",a.actorKey());db.insert("mch_brand_change",row);
   var result=db.view(row);result.put("blockers",impact.blocker()==null?List.of():List.of(impact.blocker()));result.put("applied",false);db.emit(a,commandId,store,"BRAND_CHANGE_SUBMIT",digest,result);return result;
  }));
 }
 private static Long asLong(Object o){return o==null?null:((Number)o).longValue();}
 private void verifyFacts(Map<String,Object> expected,Map<String,Object> actual){for(String k:List.of("tenantNo","actorKey","managementNo","storeNo","entityNo","fromBrandNo","toBrandNo","storeVersion","bindingRevision","fromRevision","toRevision"))if(!Objects.equals(String.valueOf(expected.get(k)),String.valueOf(actual.get(k))))throw BizException.of(ErrorCode.CONFLICT);}
 private void lockBrandStores(Actor a,Map<String,Object> facts){
  java.util.stream.Stream.of(facts.get("fromBrandNo"),facts.get("toBrandNo")).filter(Objects::nonNull).map(Object::toString).sorted().forEach(b->db.one("SELECT brand_no FROM mch_brand WHERE tenant_no=? AND brand_no=? FOR UPDATE",a.tenantNo(),b));
  db.one("SELECT store_no FROM mch_store WHERE tenant_no=? AND store_no=? FOR UPDATE",a.tenantNo(),facts.get("storeNo"));
 }
 private boolean canDecide(Actor a,String m,String from,String to){
  for(String no:new String[]{from,to})if(no!=null&&!scopes.can(a,m,"BRAND",no,"brand.change.decide"))return false;
  return true;
 }
 public Map<String,Object> change(Actor a,String no){var row=db.one("SELECT * FROM mch_brand_change WHERE tenant_no=? AND change_no=? AND deleted=0",a.tenantNo(),no);if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);var m=str(row,"management_no");scopes.resolve(a,m);if(!scopes.can(a,m,"SPACE",m,"brand.change.read")&&!scopes.can(a,m,"STORE",str(row,"store_no"),"store.read"))throw BizException.of(ErrorCode.FORBIDDEN);var result=db.view(row);result.put("applied","COMPLETED".equals(str(row,"status")));
  result.put("actions","CHECKED".equals(str(row,"status"))&&scopes.can(a,m,"STORE",str(row,"store_no"),"store.brand.change")&&canDecide(a,m,str(row,"from_brand_no"),str(row,"to_brand_no"))?List.of("brand.change.decide"):List.of());
  var snapshot=db.decode(row.get("check_snapshot"));var impact=db.decode(snapshot.get("impact"));String blocker=!Boolean.TRUE.equals(impact.get("complete"))?"IMPACT_UNKNOWN":Boolean.TRUE.equals(impact.get("brandFinancialEnabled"))?"DEPENDENCY_NOT_READY":java.util.stream.Stream.of("outstandingMinor","heldMinor","pendingRecharges","unattributedAccounts").anyMatch(k->((Number)impact.get(k)).longValue()!=0)?"STORED_VALUE_UNRESOLVED":null;
  result.put("blockers",blocker==null?List.of():List.of(blocker));return result;}
 public Map<String,Object> decide(Actor a,String no,ChangeDecision c,String key){
  if(!Set.of("APPROVE","REJECT").contains(c.decision()))throw BizException.of(ErrorCode.BAD_REQUEST);text(c.reason(),255);
  var original=change(a,no);var row=db.one("SELECT * FROM mch_brand_change WHERE tenant_no=? AND change_no=?",a.tenantNo(),no);String m=str(row,"management_no"),store=str(row,"store_no"),to=str(row,"to_brand_no");
  // Read dependencies outside financial/database locks; P1 only switches organization when brand finance is absent.
  var st=store(a,m,store);scopes.require(a,m,"STORE",store,"store.brand.change");
  if(!canDecide(a,m,str(row,"from_brand_no"),to))throw BizException.of(ErrorCode.FORBIDDEN);
  var prior=db.replay(db.commandId(a,no,"BRAND_CHANGE_DECIDE",key),hash(db.encode(c)));if(prior!=null)return prior;
  var impact="REJECT".equals(c.decision())?new BrandChangeImpactPort.Impact(true,false,0,0,0,0):impacts.inspect(a.tenantNo(),str(st,"entity_no"),store);
  return db.write(()->{
   scopes.lock(a,m);String id=db.commandId(a,no,"BRAND_CHANGE_DECIDE",key),digest=hash(db.encode(c));var replay=db.replay(id,digest);if(replay!=null)return replay;
   var current=db.one("SELECT * FROM mch_brand_change WHERE tenant_no=? AND change_no=? FOR UPDATE",a.tenantNo(),no);if(num(current,"version")!=c.expectedRevision()||!"CHECKED".equals(str(current,"status")))throw BizException.of(ErrorCode.CONFLICT);
   scopes.require(a,m,"STORE",store,"store.brand.change");
   for(String brand:new String[]{str(current,"from_brand_no"),to})if(brand!=null){scopes.require(a,m,"BRAND",brand,"brand.manage");scopes.require(a,m,"BRAND",brand,"brand.change.decide");}
   var frozen=db.decode(db.decode(current.get("check_snapshot")).get("facts"));
   lockBrandStores(a,frozen);
   String state="REJECTED";
   if("APPROVE".equals(c.decision())){
    // The approver can differ from the submitter; revalidate its authority independently.
    var nowFacts=facts(a,store,new ChangeCheck(m,to));var compare=new LinkedHashMap<>(frozen);compare.put("actorKey",a.actorKey());verifyFacts(compare,nowFacts);
    state=impact.blocker()==null?"COMPLETED":"BLOCKED";
    if("COMPLETED".equals(state)){
     var at=Timestamp.from(Instant.now());db.update("UPDATE mch_brand_store_binding SET status='ENDED',effective_to=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND store_no=? AND status='ACTIVE'",at,at,a.actorKey(),a.tenantNo(),store);
     for(String b:new String[]{str(current,"from_brand_no"),to})if(b!=null)db.update("UPDATE mch_brand SET scope_revision=scope_revision+1,version=version+1,updated_at=?,updated_by=? WHERE tenant_no=? AND brand_no=?",at,a.actorKey(),a.tenantNo(),b);
     if(to!=null){var binding=db.base(a);binding.put("binding_no",BizKey.next(BizKey.BRAND_BINDING));binding.put("brand_no",to);binding.put("store_no",store);binding.put("status","ACTIVE");binding.put("effective_from",at);binding.put("effective_to",null);binding.put("scope_revision",num(raw(a,to),"scope_revision"));binding.put("change_no",no);db.insert("mch_brand_store_binding",binding);}
     if(db.update("UPDATE mch_store SET version=version+1 WHERE tenant_no=? AND store_no=? AND version=?",a.tenantNo(),store,num(current,"expected_store_version"))!=1)throw BizException.of(ErrorCode.CONFLICT);
    }
   }
   if("APPROVE".equals(c.decision()))db.update("UPDATE mch_brand_change SET check_snapshot=? WHERE tenant_no=? AND change_no=?",db.encode(Map.of("facts",frozen,"impact",impact)),a.tenantNo(),no);
   db.update("UPDATE mch_brand_change SET status=?,decision=?,decision_key=?,decision_hash=?,decided_by=?,decided_at=?,reason=?,version=version+1,updated_at=?,updated_by=? WHERE tenant_no=? AND change_no=? AND version=?",state,c.decision(),key,digest,a.actorKey(),Timestamp.from(Instant.now()),c.reason(),Timestamp.from(Instant.now()),a.actorKey(),a.tenantNo(),no,c.expectedRevision());
   var result=change(a,no);result.put("blockers",impact.blocker()==null?List.of():List.of(impact.blocker()));db.emit(a,id,store,"BRAND_CHANGE_DECIDED",digest,result);
   if("COMPLETED".equals(state))for(String brand:new String[]{str(current,"from_brand_no"),to})if(brand!=null)db.emit(a,hash(id+"|"+brand),brand,"BRAND_STORE_CHANGED",digest,result);
   return result;
  });
 }
}
