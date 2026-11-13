package ai.neargo.shop.paybridge;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Fixed raw-body signing and authenticated opaque account/store cursors. */
public final class PaymentCollectionSecurity {
    private final PaymentCollectionProperties properties;
    private final Clock clock;
    private final byte[] cursorKey;
    private final SecureRandom random = new SecureRandom();

    public PaymentCollectionSecurity(PaymentCollectionProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        cursorKey = digest(properties.getCursorSecret().getBytes(StandardCharsets.UTF_8));
    }

    public void verify(
            String timestamp, String nonce, String environment, String signature, String raw) {
        if (!"SANDBOX".equals(environment)
                || !properties.getEnvironment().equals(environment)
                || Math.abs(clock.instant().getEpochSecond() - Long.parseLong(timestamp)) > 300
                || !MessageDigest.isEqual(
                        signature.getBytes(StandardCharsets.US_ASCII),
                        signature(timestamp, nonce, environment, raw)
                                .getBytes(StandardCharsets.US_ASCII))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    public String signature(String timestamp, String nonce, String environment, String raw) {
        return HexFormat.of()
                .formatHex(
                        hmac(
                                properties.getSecret(),
                                timestamp + "\n" + nonce + "\n" + environment + "\n" + raw));
    }

    public static String hash(String raw) {
        return HexFormat.of().formatHex(digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    public String cursor(String scope, long sequence) {
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        byte[] encrypted =
                crypt(
                        Cipher.ENCRYPT_MODE,
                        iv,
                        (scope
                                        + "\n"
                                        + sequence
                                        + "\n"
                                        + (clock.millis() + properties.getRetentionMs()))
                                .getBytes(StandardCharsets.UTF_8));
        byte[] token = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, token, 0, iv.length);
        System.arraycopy(encrypted, 0, token, iv.length, encrypted.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    public long sequence(String cursor, String scope) {
        try {
            byte[] token = Base64.getUrlDecoder().decode(cursor);
            if (token.length < 29) throw new IllegalArgumentException("cursor length");
            String[] fields =
                    new String(
                                    crypt(
                                            Cipher.DECRYPT_MODE,
                                            Arrays.copyOf(token, 12),
                                            Arrays.copyOfRange(token, 12, token.length)),
                                    StandardCharsets.UTF_8)
                            .split("\n", -1);
            if (fields.length != 3) throw new IllegalArgumentException("cursor format");
            if (!scope.equals(fields[0]))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "cursor scope");
            if (Long.parseLong(fields[2]) < clock.millis())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "resync_required");
            return Long.parseLong(fields[1]);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cursor");
        }
    }

    private byte[] crypt(int mode, byte[] iv, byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(cursorKey, "AES"), new GCMParameterSpec(128, iv));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cursor", e);
        }
    }

    private static byte[] hmac(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
