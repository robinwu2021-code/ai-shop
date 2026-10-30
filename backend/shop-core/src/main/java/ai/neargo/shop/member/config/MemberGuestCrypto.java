package ai.neargo.shop.member.config;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Frozen v1 AAD is length-framed tenant/entity/store/guest/purpose; rotation needs an explicit migration. */
@Component
public final class MemberGuestCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] key;
    public MemberGuestCrypto(@Value("${shop.member.guest.credential-key:}") String configured) {
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(configured); }
        catch (IllegalArgumentException malformed) { decoded = null; }
        key = decoded != null && decoded.length == 32 ? decoded : null;
    }
    public void requireAvailable() {
        if (key == null) { throw BizException.of(ErrorCode.MEMBER_GUEST_UNAVAILABLE); }
    }
    public String encrypt(String tenant, String entity, String store, String guest, String purpose, String value) {
        requireAvailable();
        if (value == null) { return null; }
        byte[] iv = new byte[12]; RANDOM.nextBytes(iv);
        try {
            var cipher = cipher(Cipher.ENCRYPT_MODE, iv, tenant, entity, store, guest, purpose);
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return "v1." + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv).put(encrypted).array());
        } catch (GeneralSecurityException unavailable) { throw BizException.of(ErrorCode.MEMBER_GUEST_UNAVAILABLE); }
    }
    public String decrypt(String tenant, String entity, String store, String guest, String purpose, String value) {
        requireAvailable();
        if (value == null) { return null; }
        try {
            if (!value.startsWith("v1.")) { throw new GeneralSecurityException(); }
            var bytes = ByteBuffer.wrap(Base64.getDecoder().decode(value.substring(3)));
            if (bytes.remaining() < 28) { throw new GeneralSecurityException(); }
            byte[] iv = new byte[12]; bytes.get(iv);
            byte[] encrypted = new byte[bytes.remaining()]; bytes.get(encrypted);
            return new String(cipher(Cipher.DECRYPT_MODE, iv, tenant, entity, store, guest, purpose)
                    .doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException unavailable) {
            throw BizException.of(ErrorCode.MEMBER_GUEST_UNAVAILABLE);
        }
    }
    private Cipher cipher(int mode, byte[] iv, String tenant, String entity, String store, String guest, String purpose)
            throws GeneralSecurityException {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(frame("member-guest-v1", tenant, entity, store, guest, purpose));
        return cipher;
    }
    /** Low-entropy contact values must never become an offline guessing oracle through an unkeyed digest. */
    public String requestFingerprint(String... values) {
        requireAvailable();
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(frame("member-guest-registration-request-v1"));
            return HexFormat.of().formatHex(mac.doFinal(frame(values)));
        } catch (GeneralSecurityException unavailable) { throw BizException.of(ErrorCode.MEMBER_GUEST_UNAVAILABLE); }
    }
    public static String token() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String hash(String... values) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(frame(values))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static byte[] frame(String... values) {
        int length = 0;
        for (String value : values) {
            length += 4 + (value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length);
        }
        var out = ByteBuffer.allocate(length);
        for (String value : values) {
            byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
            out.putInt(bytes == null ? -1 : bytes.length);
            if (bytes != null) { out.put(bytes); }
        }
        return out.array();
    }
}
