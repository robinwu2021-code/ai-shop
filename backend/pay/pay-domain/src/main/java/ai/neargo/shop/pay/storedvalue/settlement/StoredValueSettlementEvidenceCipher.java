package ai.neargo.shop.pay.storedvalue.settlement;
import ai.neargo.shop.common.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
/** Dedicated authenticated private receipt encryption, without plaintext logging or a default key. */
@Component
public class StoredValueSettlementEvidenceCipher {
 private final String configured,keyId;private static final SecureRandom RANDOM=new SecureRandom();
 public StoredValueSettlementEvidenceCipher(@Value("${shop.pay.stored-value.settlement-evidence-key:}")String configured,@Value("${shop.pay.stored-value.settlement-evidence-key-id:}")String keyId){this.configured=configured;this.keyId=keyId;}
 public boolean configured(){try{key();return true;}catch(BizException e){return false;}}
 public String keyId(){key();return keyId;}
 public byte[] encrypt(String receipt,String aad){
  var key=key();if(receipt==null||receipt.isBlank()||receipt.length()>160||aad==null||aad.isBlank())throw BizException.of(ErrorCode.BAD_REQUEST);
  try{byte[] nonce=new byte[12];RANDOM.nextBytes(nonce);var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));var enc=Base64.getUrlEncoder().withoutPadding();return ("v1."+keyId+"."+enc.encodeToString(nonce)+"."+enc.encodeToString(cipher.doFinal(receipt.getBytes(StandardCharsets.UTF_8)))).getBytes(StandardCharsets.US_ASCII);}
  catch(GeneralSecurityException e){throw new IllegalStateException("Private settlement encryption failed",e);}
 }
 public String decrypt(byte[] ciphertext,String aad){
  try{var parts=new String(ciphertext,StandardCharsets.US_ASCII).split("\\.",-1);if(parts.length!=4||!parts[0].equals("v1")||!parts[1].equals(keyId))throw new IllegalArgumentException();var dec=Base64.getUrlDecoder();byte[] nonce=dec.decode(parts[2]);if(nonce.length!=12)throw new IllegalArgumentException();var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));return new String(cipher.doFinal(dec.decode(parts[3])),StandardCharsets.UTF_8);}
  catch(GeneralSecurityException|IllegalArgumentException e){throw BizException.of(ErrorCode.CONFLICT);}
 }
 private SecretKeySpec key(){try{if(configured==null||keyId==null||!keyId.matches("[A-Za-z0-9_-]{1,32}"))throw new IllegalArgumentException();byte[] raw=Base64.getDecoder().decode(configured);if(raw.length!=32)throw new IllegalArgumentException();return new SecretKeySpec(raw,"AES");}catch(IllegalArgumentException e){throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);}}
}
