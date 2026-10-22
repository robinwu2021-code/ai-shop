package ai.neargo.shop.member.service;
import static ai.neargo.shop.member.service.MemberScopeRepository.*;
import ai.neargo.shop.common.*;import ai.neargo.shop.spi.user.MemberIdentityFactsPort;import org.springframework.stereotype.Service;import org.springframework.beans.factory.annotation.Value;
import java.util.*;import java.time.Instant;import java.nio.charset.StandardCharsets;import java.security.*;import javax.crypto.*;import javax.crypto.spec.SecretKeySpec;
@Service
public class MemberScopeQueryService {
 private final MemberScopeRepository db;private final MemberScopeService members;private final MemberIdentityFactsPort identities;private final String key;
 public MemberScopeQueryService(MemberScopeRepository db,MemberScopeService members,MemberIdentityFactsPort identities,@Value("${shop.management.signing-key:}") String key){this.db=db;this.members=members;this.identities=identities;this.key=key;}
 public MemberScopeModels.Summary detail(String tenant,String entity,String store,String no){var m=members.require(tenant,entity,store,no);var person=members.identity(tenant,m.customerNo());var profile=db.one("SELECT remark FROM mbr_store_profile WHERE tenant_no=? AND store_no=? AND customer_no=? AND deleted=0",tenant,store,m.customerNo());String remark=s(profile,"remark");return new MemberScopeModels.Summary(no,remark==null||remark.isBlank()?"顾客":remark,person.phoneMasked(),m.status(),m.firstStoreNo(),sourceName(tenant,entity,store,m.firstStoreNo()),remark);}
 private String sourceName(String tenant,String entity,String store,String source){return members.sourceName(tenant,entity,store,source);}
 public MemberScopeModels.Page search(String tenant,String entity,String store,String actor,String keyword,String cursor,int size){
  if(size<1||size>100||keyword==null||keyword.length()>100||keyword.strip().length()<(keyword.matches("[0-9]+")?3:2))throw BizException.of(ErrorCode.BAD_REQUEST);keyword=keyword.strip();
  long conflicts=members.importLegacy(tenant,entity,store,actor);var scope=members.scope(tenant,entity,store);String fingerprint=hash(db.encode(List.of(tenant,entity,store,actor,scope.scopeType(),scope.scopeNo(),scope.revision(),keyword)));long before=Long.MAX_VALUE;
  if(cursor!=null){var c=decode(cursor);if(!fingerprint.equals(c.get("scope")))throw BizException.of(ErrorCode.CONFLICT);before=((Number)c.get("before")).longValue();}
  var items=new ArrayList<MemberScopeModels.Summary>();long after=before;boolean more=false;
  while(items.size()<size+1){var batch=db.rows("SELECT * FROM mbr_scope_member WHERE tenant_no=? AND scope_type=? AND scope_no=? AND deleted=0 AND id<? ORDER BY id DESC LIMIT 100",tenant,scope.scopeType(),scope.scopeNo(),after);if(batch.isEmpty())break;
   var ids=batch.stream().map(x->s(x,"customer_no")).toList();var matched=identities.match(tenant,ids,keyword).stream().filter(MemberIdentityFactsPort.Identity::consistent).map(MemberIdentityFactsPort.Identity::customerNo).collect(java.util.stream.Collectors.toSet());
   for(var row:batch){long id=n(row,"id");String customer=s(row,"customer_no");var profile=db.one("SELECT remark FROM mbr_store_profile WHERE tenant_no=? AND store_no=? AND customer_no=? AND deleted=0",tenant,store,customer);String remark=s(profile,"remark");boolean hit=matched.contains(customer)||!keyword.matches("[0-9]+")&&remark!=null&&remark.contains(keyword);
    if(hit){if(items.size()==size){more=true;break;}items.add(detail(tenant,entity,store,s(row,"scope_member_no")));}after=id;
   }if(more||batch.size()<100)break;
  }
  var current=members.scope(tenant,entity,store);if(!current.scopeType().equals(scope.scopeType())||!current.scopeNo().equals(scope.scopeNo())||current.revision()!=scope.revision())throw BizException.of(ErrorCode.CONFLICT);
  return new MemberScopeModels.Page(items,more?encode(Map.of("scope",fingerprint,"before",after,"expiresAt",Instant.now().plusSeconds(900).getEpochSecond())):null,conflicts);
 }
 private byte[] sign(String value){if(key==null||key.length()<32)throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return mac.doFinal(("MEMBER_SCOPE_v1|"+value).getBytes(StandardCharsets.UTF_8));}catch(GeneralSecurityException e){throw new IllegalStateException(e);}}
 private String encode(Map<String,Object> value){String body=Base64.getUrlEncoder().withoutPadding().encodeToString(db.encode(value).getBytes(StandardCharsets.UTF_8));return body+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(sign(body));}
 private Map<String,Object> decode(String value){try{var parts=value.split("\\.");if(parts.length!=2||!MessageDigest.isEqual(sign(parts[0]),Base64.getUrlDecoder().decode(parts[1])))throw new IllegalArgumentException();var c=db.decode(new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8));if(((Number)c.get("expiresAt")).longValue()<=Instant.now().getEpochSecond())throw new IllegalArgumentException();return c;}catch(RuntimeException e){throw BizException.of(ErrorCode.CONFLICT);}}
 private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
