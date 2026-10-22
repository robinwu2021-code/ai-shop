package ai.neargo.shop.member.service;
import static ai.neargo.shop.member.service.MemberScopeRepository.*;
import ai.neargo.shop.common.*;import ai.neargo.shop.spi.member.MemberOperatingScopePort;import ai.neargo.shop.spi.user.MemberIdentityFactsPort;
import org.springframework.stereotype.Service;import java.util.*;import java.sql.Timestamp;import java.time.Instant;
@Service
public class MemberScopeService {
 private final MemberScopeRepository db;private final MemberOperatingScopePort organizations;private final MemberIdentityFactsPort identities;
 public MemberScopeService(MemberScopeRepository db,MemberOperatingScopePort organizations,MemberIdentityFactsPort identities){this.db=db;this.organizations=organizations;this.identities=identities;}
 public MemberOperatingScopePort.Scope scope(String tenant,String entity,String store){var scope=organizations.resolve(tenant,entity,store);if(scope==null||!tenant.equals(scope.tenantNo())||!entity.equals(scope.entityNo())||!store.equals(scope.storeNo())||!Set.of("BRAND","STORE").contains(scope.scopeType()))throw BizException.of(ErrorCode.NOT_FOUND);return scope;}
 public MemberIdentityFactsPort.Identity identity(String tenant,String customer){var id=identities.find(tenant,customer).orElseThrow(()->BizException.of(ErrorCode.NOT_FOUND));if(!id.consistent()||!customer.equals(id.customerNo()))throw BizException.of(ErrorCode.CONFLICT);return id;}
 public MemberScopeModels.ScopeMember enroll(String tenant,String entity,String store,String customer,String actor,String remark){
  return enrollInternal(tenant,entity,store,customer,actor,remark,false);
 }
 private MemberScopeModels.ScopeMember enrollInternal(String tenant,String entity,String store,String customer,String actor,String remark,boolean legacy){
  identity(tenant,customer);if(actor==null||actor.isBlank()||actor.length()>64||remark!=null&&remark.length()>500)throw BizException.of(ErrorCode.BAD_REQUEST);
  return db.write(()->{var scope=organizations.lock(tenant,entity,store);if(scope==null||!tenant.equals(scope.tenantNo())||!entity.equals(scope.entityNo())||!store.equals(scope.storeNo()))throw BizException.of(ErrorCode.NOT_FOUND);
   var row=db.one("SELECT * FROM mbr_scope_member WHERE tenant_no=? AND scope_type=? AND scope_no=? AND customer_no=? FOR UPDATE",tenant,scope.scopeType(),scope.scopeNo(),customer);
   var now=Timestamp.from(Instant.now());if(row==null){row=db.base(tenant,actor);row.put("scope_member_no",BizKey.next(BizKey.SCOPE_MEMBER));row.put("scope_type",scope.scopeType());row.put("scope_no",scope.scopeNo());row.put("customer_no",customer);row.put("first_store_no",store);row.put("status",identity(tenant,customer).userNo()==null?"LEAD":"ACTIVE");row.put("joined_at",now);row.put("shared_profile","{}");db.insert("mbr_scope_member",row);}
   var profile=db.one("SELECT * FROM mbr_store_profile WHERE tenant_no=? AND store_no=? AND customer_no=? FOR UPDATE",tenant,store,customer);
   if(profile==null){profile=db.base(tenant,actor);profile.put("store_profile_no",BizKey.next(BizKey.STORE_MEMBER_PROFILE));profile.put("customer_no",customer);profile.put("store_no",store);profile.put("remark",remark);profile.put("first_seen_at",now);profile.put("last_seen_at",legacy?null:now);db.insert("mbr_store_profile",profile);}else if(!legacy)db.update("UPDATE mbr_store_profile SET remark=COALESCE(?,remark),last_seen_at=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND store_no=? AND customer_no=?",remark,now,now,actor,tenant,store,customer);
   return db.member(row);
  });
 }
 public MemberScopeModels.ScopeMember require(String tenant,String entity,String store,String no){var scope=scope(tenant,entity,store);var member=db.member(db.one("SELECT * FROM mbr_scope_member WHERE tenant_no=? AND scope_member_no=? AND scope_type=? AND scope_no=? AND deleted=0",tenant,no,scope.scopeType(),scope.scopeNo()));identity(tenant,member.customerNo());
  if(!"BLOCKED".equals(member.status())&&db.one("SELECT l.link_no FROM mbr_scope_legacy_link l JOIN mbr_member m ON m.tenant_no=l.tenant_no AND m.member_no=l.legacy_member_no WHERE l.tenant_no=? AND l.scope_member_no=? AND l.status='VERIFIED' AND l.deleted=0 AND m.deleted=0 AND m.entity_no=? AND m.status NOT IN ('ACTIVE','LEAD')",tenant,no,entity)!=null)return new MemberScopeModels.ScopeMember(member.scopeMemberNo(),member.customerNo(),member.scopeType(),member.scopeNo(),member.firstStoreNo(),"BLOCKED");
  return member;}
 public void linkLegacy(String tenant,String legacyNo,String memberNo,String sourceStore,String actor){db.write(()->{
  var member=db.member(db.one("SELECT * FROM mbr_scope_member WHERE tenant_no=? AND scope_member_no=? AND deleted=0",tenant,memberNo));var old=db.one("SELECT * FROM mbr_member WHERE tenant_no=? AND member_no=? AND deleted=0",tenant,legacyNo);
  if(old==null||!member.customerNo().equals(s(old,"person_no")))throw BizException.of(ErrorCode.CONFLICT);
  if(db.one("SELECT link_no FROM mbr_scope_legacy_link WHERE tenant_no=? AND legacy_member_no=? AND scope_member_no=?",tenant,legacyNo,memberNo)==null){var row=db.base(tenant,actor);row.put("link_no",BizKey.next(BizKey.MEMBER_SCOPE_LINK));row.put("legacy_member_no",legacyNo);row.put("scope_member_no",memberNo);row.put("migration_no","P2_VERIFIED_STORE");row.put("identity_evidence",db.encode(Map.of("kind","CANONICAL_PERSON_AND_STORE","sourceStoreNo",sourceStore)));row.put("status","VERIFIED");db.insert("mbr_scope_legacy_link",row);}return null;});}
 public void claim(String tenant,String customer){var person=identity(tenant,customer);if(person.userNo()==null)throw BizException.of(ErrorCode.CONFLICT);db.update("UPDATE mbr_scope_member SET status='ACTIVE',updated_at=?,updated_by='SYSTEM',version=version+1 WHERE tenant_no=? AND customer_no=? AND status='LEAD' AND deleted=0",Timestamp.from(Instant.now()),tenant,customer);}
 public String sourceName(String tenant,String entity,String store,String source){var current=scope(tenant,entity,store);return organizations.stores(current).stream().filter(s->source.equals(s.storeNo())).map(ai.neargo.shop.spi.member.MemberOperatingScopePort.Scope::storeName).findFirst().orElse("");}
 public MemberScopeModels.ScopeMember byCustomer(String tenant,String entity,String store,String customer){var scope=scope(tenant,entity,store);var row=db.one("SELECT * FROM mbr_scope_member WHERE tenant_no=? AND scope_type=? AND scope_no=? AND customer_no=? AND deleted=0",tenant,scope.scopeType(),scope.scopeNo(),customer);return require(tenant,entity,store,db.member(row).scopeMemberNo());}
 /** Existing rows are mapped only from explicit source-store evidence, never by phone or entity alone. */
 public long importLegacy(String tenant,String entity,String store,String actor){var target=scope(tenant,entity,store);var conflicts=new HashSet<String>();
  for(var source:organizations.stores(target)){
   var verified=scope(tenant,source.entityNo(),source.storeNo());if(!target.scopeType().equals(verified.scopeType())||!target.scopeNo().equals(verified.scopeNo()))continue;
   for(var old:db.rows("SELECT m.* FROM mbr_member m WHERE m.tenant_no=? AND m.entity_no=? AND m.deleted=0 AND (m.first_store_no=? OR EXISTS(SELECT 1 FROM mbr_member_store ms WHERE ms.tenant_no=m.tenant_no AND ms.member_no=m.member_no AND ms.store_no=? AND ms.deleted=0)) AND NOT EXISTS(SELECT 1 FROM mbr_scope_legacy_link l JOIN mbr_scope_member sm ON sm.tenant_no=l.tenant_no AND sm.scope_member_no=l.scope_member_no WHERE l.tenant_no=m.tenant_no AND l.legacy_member_no=m.member_no AND l.status='VERIFIED' AND l.deleted=0 AND sm.scope_type=? AND sm.scope_no=? AND sm.deleted=0)",tenant,source.entityNo(),source.storeNo(),source.storeNo(),target.scopeType(),target.scopeNo())){
    String customer=s(old,"person_no");var evidence=identities.find(tenant,customer);if(evidence.isEmpty()||!evidence.get().consistent()){conflicts.add(s(old,"member_no"));continue;}
    var member=enrollInternal(tenant,source.entityNo(),source.storeNo(),customer,actor,null,true);
    linkLegacy(tenant,s(old,"member_no"),member.scopeMemberNo(),source.storeNo(),actor);
   }
  }
  return conflicts.size();
 }
}
