package ai.neargo.shop.merchant.brand.impl;
import ai.neargo.shop.merchant.brand.BrandFinancialChangeService;
import ai.neargo.shop.merchant.brand.ManagementRepository;
import ai.neargo.shop.merchant.brand.ManagementScopeService;
import ai.neargo.shop.merchant.brand.ManagementModels;
import static ai.neargo.shop.merchant.brand.ManagementRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.numbering.*;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueMembership.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
/** Core-local durable participant. No Pay call, balance change or identity merge occurs in these transactions. */
@Service("brandFinancialChangeService")
public class BrandFinancialChangeServiceImpl implements BrandFinancialChangeService {
 private final ManagementRepository db;private final ManagementScopeService scopes;private final DocumentNumberCoordinator numbers;
 public BrandFinancialChangeServiceImpl(ManagementRepository db,ManagementScopeService scopes,@Qualifier("coreDocumentNumberCoordinator")DocumentNumberCoordinator numbers){this.db=db;this.scopes=scopes;this.numbers=numbers;}




 public Source source(Actor a,String management,String store,String to){authorize(a,management,store,from(a,store),to);var st=store(a,store,false);var brands=new ArrayList<String>();String old=from(a,store);if(old!=null)brands.add(old);if(to!=null&&!to.equals(old))brands.add(to);if(Objects.equals(old,to))bad();return new Source(management,store,str(st,"entity_no"),brands);}
 public Pending original(Actor a,String management,String no){var row=row(a,management,no,false);authorize(a,management,str(row,"store_no"),str(row,"from_brand_no"),str(row,"to_brand_no"));return pending(row);}
 public Pending findOriginal(Actor a,String management,String store,String key){ContractChecks.id(key);scopes.resolve(a,management);scopes.require(a,management,"STORE",store,"store.read");var row=db.one("SELECT change_no FROM mch_brand_financial_change WHERE tenant_no=? AND original_actor_no=? AND command_key=? AND management_no=? AND store_no=? AND deleted=0",a.tenantNo(),a.actorKey(),key,management,store);return row==null?null:original(a,management,str(row,"change_no"));}
 public Pending byKey(Actor a,String management,String store,String key,long expected,String to,String reason){var row=db.one("SELECT * FROM mch_brand_financial_change WHERE tenant_no=? AND original_actor_no=? AND command_key=? AND deleted=0",a.tenantNo(),a.actorKey(),key);if(row==null)return null;if(!management.equals(str(row,"management_no"))||!store.equals(str(row,"store_no"))||!requestHash(a,management,store,to,expected,reason).equals(str(row,"command_hash")))conflict();return original(a,management,str(row,"change_no"));}
 public Pending register(Actor a,String management,String store,String to,long expected,String reason,String key,List<Target> targets){
  ContractChecks.id(key);ManagementModels.text(reason,255);var prior=byKey(a,management,store,key,expected,to,reason);if(prior!=null)return prior;
  return numbers.withReservation(Map.of(DocumentType.BRAND_CHANGE,1),()->db.write(()->{
   scopes.lock(a,management);var repeated=byKey(a,management,store,key,expected,to,reason);if(repeated!=null)return repeated;
   var st=store(a,store,false);String old=from(a,store);authorize(a,management,store,old,to);if(Objects.equals(old,to)||num(st,"version")!=expected||!"ACTIVE".equals(str(st,"status")))conflict();
   var brands=lockBrands(a,management,old,to);st=store(a,store,true);if(num(st,"version")!=expected||!Objects.equals(from(a,store),old))conflict();

   if(db.one("SELECT change_no FROM mch_brand_change WHERE tenant_no=? AND store_no=? AND status IN ('CHECKED','PREPARING') AND deleted=0",a.tenantNo(),store)!=null)conflict();
   var binding=db.one("SELECT * FROM mch_brand_store_binding WHERE tenant_no=? AND store_no=? AND status='ACTIVE' AND deleted=0 FOR UPDATE",a.tenantNo(),store);String oldBinding=str(binding,"binding_no"),newBinding=to==null?null:BizKey.next(BizKey.BRAND_BINDING);long newRevision=to==null?-1:Math.addExact(num(brands.get(to),"scope_revision"),1);var sides=new ArrayList<Side>();
   for(var t:targets){if(!brands.containsKey(t.brandNo()))throw BizException.of(ErrorCode.FORBIDDEN);boolean leave=t.brandNo().equals(old);if(leave&&(!Objects.equals(oldBinding,t.bindingNo())||num(binding,"scope_revision")!=t.bindingRevision()))conflict();long revision=leave?Math.addExact(t.bindingRevision(),1):Math.max(newRevision,Math.addExact(t.bindingRevision(),1));if(!leave)newRevision=revision;sides.add(new Side(t.issuerNo(),t.issuerVersionNo(),t.brandNo(),leave?"LEAVE":"JOIN",t.bindingNo(),leave?null:newBinding,t.bindingRevision(),revision));}
   if(sides.isEmpty())throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
   String digest=requestHash(a,management,store,to,expected,reason),no=numbers.bind(DocumentType.BRAND_CHANGE,new DocumentSource(a.tenantNo(),"MERCHANT","BRAND_FINANCIAL_CHANGE",db.commandId(a,store,"FINANCIAL_CHANGE",key)));var command=new Prepare(a.tenantNo(),a.actorKey(),management,no,store,str(st,"entity_no"),sides,2);var frozen=new Frozen(command,expected,old,to,oldBinding,newBinding,newRevision);
   var saved=db.base(a);saved.put("change_no",no);saved.put("management_no",management);saved.put("store_no",store);saved.put("from_brand_no",old);saved.put("to_brand_no",to);saved.put("original_actor_no",a.actorKey());saved.put("command_key",key);saved.put("command_hash",digest);saved.put("source_snapshot",db.encode(frozen));saved.put("status","PREPARING");db.insert("mch_brand_financial_change",saved);
   // Reserve the existing organization-change unique pending-store slot too; old flows cannot race this saga.
   var marker=db.base(a);for(String field:List.of("change_no","management_no","store_no","from_brand_no","to_brand_no","command_key","command_hash"))marker.put(field,saved.get(field));marker.put("status","PREPARING");marker.put("expected_scope_revision",num(binding,"scope_revision"));marker.put("expected_store_version",expected);marker.put("expected_from_brand_revision",old==null?null:num(brands.get(old),"scope_revision"));marker.put("expected_to_brand_revision",to==null?null:num(brands.get(to),"scope_revision"));marker.put("check_snapshot",db.encode(Map.of("financial",true)));marker.put("authorization_snapshot",db.encode(Map.of("actor",a.actorKey(),"managementNo",management)));marker.put("check_expires_at",Timestamp.from(Instant.now()));marker.put("submitted_by",a.actorKey());marker.put("reason",reason);db.insert("mch_brand_change",marker);
   return pending(saved);
  }));
 }
 public Pending prepared(Actor a,String management,String no,Ack ack){return db.write(()->{scopes.lock(a,management);var row=row(a,management,no,true);authorizeRow(a,row);var p=pending(row);validate(p.command(),ack,"PREPARED");if(!"PREPARING".equals(p.state()))return p;db.update("UPDATE mch_brand_financial_change SET status='PAY_PREPARED',pay_prepare_snapshot=?,updated_at=CURRENT_TIMESTAMP,updated_by=?,version=version+1 WHERE tenant_no=? AND change_no=?",db.encode(new Receipt(ack,false)),a.actorKey(),a.tenantNo(),no);return pending(row(a,management,no,false));});}
 public Pending stage(Actor a,String management,String no){return db.write(()->{
  scopes.lock(a,management);var row=row(a,management,no,true);authorizeRow(a,row);var p=pending(row);if(p.staged()||"COMPLETED".equals(p.state()))return p;if(!"PAY_PREPARED".equals(p.state()))conflict();var f=frozen(row);lockBrands(a,management,f.fromBrand(),f.toBrand());var st=store(a,f.command().storeNo(),true);if(!"ACTIVE".equals(str(st,"status"))||!f.command().operatingEntityNo().equals(str(st,"entity_no"))||!Objects.equals(from(a,f.command().storeNo()),f.fromBrand()))conflict();
  var at=Timestamp.from(Instant.now());if(f.oldBinding()!=null){if(db.update("UPDATE mch_brand_store_binding SET status='PREPARING',updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND binding_no=? AND status='ACTIVE'",at,a.actorKey(),a.tenantNo(),f.oldBinding())!=1)conflict();}
  if(f.toBrand()!=null){var binding=db.base(a);binding.put("binding_no",f.newBinding());binding.put("brand_no",f.toBrand());binding.put("store_no",f.command().storeNo());binding.put("status","PREPARING");binding.put("scope_revision",f.newRevision());binding.put("change_no",no);binding.put("effective_from",at);db.insert("mch_brand_store_binding",binding);}
  // Membership owns binding revisions; unrelated store profile versions and STORE issuer access remain independent.
  var receipt=receipt(row);db.update("UPDATE mch_brand_financial_change SET pay_prepare_snapshot=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND change_no=?",db.encode(new Receipt(receipt.ack(),true)),at,a.actorKey(),a.tenantNo(),no);return pending(row(a,management,no,false));
 });}
 public Pending complete(Actor a,String management,String no,Ack ack){return db.write(()->{
  scopes.lock(a,management);var row=row(a,management,no,true);authorizeRow(a,row);var p=pending(row);validate(p.command(),ack,"ACTIVE");if("COMPLETED".equals(p.state()))return p;if(!p.staged())conflict();var f=frozen(row);lockBrands(a,management,f.fromBrand(),f.toBrand());store(a,f.command().storeNo(),true);var at=Timestamp.from(Instant.now());
  if(f.oldBinding()!=null&&db.update("UPDATE mch_brand_store_binding SET status='ENDED',effective_to=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND binding_no=? AND status='PREPARING'",at,at,a.actorKey(),a.tenantNo(),f.oldBinding())!=1)conflict();
  if(f.newBinding()!=null&&db.update("UPDATE mch_brand_store_binding SET status='ACTIVE',updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND binding_no=? AND status='PREPARING'",at,a.actorKey(),a.tenantNo(),f.newBinding())!=1)conflict();
  for(String b:new String[]{f.fromBrand(),f.toBrand()})if(b!=null)db.update("UPDATE mch_brand SET scope_revision=scope_revision+1,version=version+1,updated_at=?,updated_by=? WHERE tenant_no=? AND brand_no=?",at,a.actorKey(),a.tenantNo(),b);
  db.update("UPDATE mch_brand_financial_change SET status='COMPLETED',updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND change_no=?",at,a.actorKey(),a.tenantNo(),no);db.update("UPDATE mch_brand_change SET status='COMPLETED',decision='APPROVE',decided_by=?,decided_at=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND change_no=?",a.actorKey(),at,at,a.actorKey(),a.tenantNo(),no);
  db.emit(a,hash(no+"|FINANCIAL_MEMBERSHIP_COMMITTED"),f.command().storeNo(),"BRAND_STORE_CHANGED",expectedPayHash(p.command()),Map.of("changeNo",no,"storeNo",f.command().storeNo(),"state","COMPLETED"));return pending(row(a,management,no,false));
 });}
 public String expectedPayHash(Prepare c){return hash(db.encode(List.of(c.tenantNo(),c.actorNo(),c.managementNo(),c.changeNo(),c.storeNo(),c.operatingEntityNo(),c.sides().stream().sorted(Comparator.comparing(Side::issuerNo)).toList(),c.coreProtocol())));}
 private void validate(Prepare c,Ack ack,String state){if(!state.equals(ack.state())||!c.changeNo().equals(ack.changeNo())||!expectedPayHash(c).equals(ack.snapshotHash())||!c.sides().stream().map(Side::issuerNo).sorted().toList().equals(ack.issuerNos().stream().sorted().toList()))conflict();}
 private String requestHash(Actor a,String management,String store,String to,long expected,String reason){return hash(db.encode(Arrays.asList(a.actorKey(),management,store,to,expected,reason)));}
 private void authorizeRow(Actor a,Map<String,Object> row){authorize(a,str(row,"management_no"),str(row,"store_no"),str(row,"from_brand_no"),str(row,"to_brand_no"));}
 private void authorize(Actor a,String management,String store,String from,String to){ContractChecks.id(management);ContractChecks.id(store);scopes.resolve(a,management);scopes.require(a,management,"STORE",store,"store.read");scopes.require(a,management,"STORE",store,"store.brand.change");for(String b:new String[]{from,to})if(b!=null){if(db.one("SELECT brand_no FROM mch_brand WHERE tenant_no=? AND management_no=? AND brand_no=? AND deleted=0",a.tenantNo(),management,b)==null)throw BizException.of(ErrorCode.NOT_FOUND);scopes.require(a,management,"BRAND",b,"brand.manage");scopes.require(a,management,"BRAND",b,"brand.change.decide");}}
 private TreeMap<String,Map<String,Object>> lockBrands(Actor a,String management,String from,String to){var result=new TreeMap<String,Map<String,Object>>();for(String b:java.util.stream.Stream.of(from,to).filter(Objects::nonNull).distinct().sorted().toList()){var row=db.one("SELECT * FROM mch_brand WHERE tenant_no=? AND management_no=? AND brand_no=? AND deleted=0 FOR UPDATE",a.tenantNo(),management,b);if(row==null||!"ACTIVE".equals(str(row,"status")))conflict();result.put(b,row);}return result;}
 private Map<String,Object> store(Actor a,String no,boolean lock){var row=db.one("SELECT * FROM mch_store WHERE tenant_no=? AND store_no=? AND deleted=0"+(lock?" FOR UPDATE":""),a.tenantNo(),no);if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);return row;}
 private String from(Actor a,String store){return str(db.one("SELECT brand_no FROM mch_brand_store_binding WHERE tenant_no=? AND store_no=? AND status='ACTIVE' AND deleted=0",a.tenantNo(),store),"brand_no");}
 private Map<String,Object> row(Actor a,String management,String no,boolean lock){var row=db.one("SELECT * FROM mch_brand_financial_change WHERE tenant_no=? AND management_no=? AND change_no=? AND deleted=0"+(lock?" FOR UPDATE":""),a.tenantNo(),management,no);if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);return row;}
 private Frozen frozen(Map<String,Object> row){return db.decode(str(row,"source_snapshot"),Frozen.class);}
 private Receipt receipt(Map<String,Object> row){return str(row,"pay_prepare_snapshot")==null?null:db.decode(str(row,"pay_prepare_snapshot"),Receipt.class);}
 private Pending pending(Map<String,Object> row){var receipt=receipt(row);return new Pending(str(row,"change_no"),str(row,"status"),receipt!=null&&receipt.staged(),frozen(row).command());}
 private static void bad(){throw BizException.of(ErrorCode.BAD_REQUEST);}private static void conflict(){throw BizException.of(ErrorCode.CONFLICT);}
}
