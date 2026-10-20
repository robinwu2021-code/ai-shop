package ai.neargo.shop.pay.migration.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Authenticated internal evidence storage. No key fallback, plaintext logging, or financial commands. */
@Component
public class LegacySnapshotCipher {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final String configuredKey;
    private final String keyId;
    public LegacySnapshotCipher(@Value("${shop.pay.entitlement.migration-snapshot-key:}") String configuredKey,@Value("${shop.pay.entitlement.migration-snapshot-key-id:}") String keyId) {
        this.configuredKey=configuredKey; this.keyId=keyId;
    }
    public void requireConfigured() { key(); }
    public String keyId() { requireConfigured(); return keyId; }
    public String encrypt(String plaintext,String aad) {
        var key=key();
        if(plaintext==null || plaintext.isEmpty() || aad==null || aad.isEmpty())
            throw BizException.of(ErrorCode.BAD_REQUEST);
        byte[] bytes=plaintext.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>LegacyCouponFrozenSnapshotPort.MAX_PAYLOAD_BYTES)
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        try {
            byte[] nonce=new byte[12]; RANDOM.nextBytes(nonce);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            var base64=Base64.getUrlEncoder().withoutPadding();
            return "v1."+keyId+"."+base64.encodeToString(nonce)+"."+base64.encodeToString(cipher.doFinal(bytes));
        } catch(GeneralSecurityException failure) {
            throw new IllegalStateException("Authenticated snapshot encryption unavailable");
        }
    }
    public String decrypt(String ciphertext,String aad) {
        var key=key();
        if(ciphertext==null || ciphertext.length()>12000000 || aad==null || aad.isEmpty())
            throw BizException.of(ErrorCode.CONFLICT);
        var parts=ciphertext.split("\\.",-1);
        if(parts.length!=4 || !"v1".equals(parts[0])) throw BizException.of(ErrorCode.CONFLICT);
        if(!keyId.equals(parts[1])) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        try {
            byte[] nonce=Base64.getUrlDecoder().decode(parts[2]);
            if(nonce.length!=12) throw BizException.of(ErrorCode.CONFLICT);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] bytes=cipher.doFinal(Base64.getUrlDecoder().decode(parts[3]));
            if(bytes.length==0 || bytes.length>LegacyCouponFrozenSnapshotPort.MAX_PAYLOAD_BYTES)
                throw BizException.of(ErrorCode.CONFLICT);
            return new String(bytes,StandardCharsets.UTF_8);
        } catch(GeneralSecurityException | IllegalArgumentException invalid) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }
    private SecretKeySpec key() {
        if(configuredKey==null || keyId==null || !keyId.matches("[A-Za-z0-9_-]{1,32}"))
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        try {
            byte[] bytes=Base64.getDecoder().decode(configuredKey);
            if(bytes.length!=32) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            return new SecretKeySpec(bytes,"AES");
        } catch(IllegalArgumentException invalid) {
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        }
    }
}
