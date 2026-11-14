package ai.neargo.shop.merchant.brand;
import ai.neargo.shop.common.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
/** Purpose-bound short lived checks and opaque list cursors; invalid after expiry. */
@Component
public class ManagementTokenCodec {
 private final String key;private final ObjectMapper json;
 public ManagementTokenCodec(@Value("${shop.management.signing-key:${shop.pay.entitlement.cursor-key:}}") String key,ObjectMapper json){this.key=key;this.json=json;}
 private byte[] sign(String body){
  if(key==null||key.length()<32)throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
  try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return mac.doFinal(("MANAGEMENT-v1|"+body).getBytes(StandardCharsets.UTF_8));}catch(java.security.GeneralSecurityException e){throw new IllegalStateException(e);}
 }
 public String encode(String purpose,Map<String,Object> facts,int seconds){
  var body=new LinkedHashMap<>(facts);body.put("purpose",purpose);body.put("expiresAt",Instant.now().plusSeconds(seconds).getEpochSecond());
  var data=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
  return data+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(sign(data));
 }
 @SuppressWarnings("unchecked") public Map<String,Object> decode(String purpose,String token){
  try{
   if(token==null||token.length()>8192)throw new IllegalArgumentException();
   var parts=token.split("\\.");if(parts.length!=2||!MessageDigest.isEqual(sign(parts[0]),Base64.getUrlDecoder().decode(parts[1])))throw new IllegalArgumentException();
   Map<String,Object> body=json.readValue(Base64.getUrlDecoder().decode(parts[0]),Map.class);
   if(!purpose.equals(body.get("purpose"))||((Number)body.get("expiresAt")).longValue()<=Instant.now().getEpochSecond())throw new IllegalArgumentException();return body;
  }catch(BizException e){throw e;}catch(Exception e){throw BizException.of(ErrorCode.CONFLICT);}
 }
}
