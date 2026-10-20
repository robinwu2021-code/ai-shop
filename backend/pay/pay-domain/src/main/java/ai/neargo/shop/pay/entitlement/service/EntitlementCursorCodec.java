package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** HMAC-bound keyset cursor works across nodes/restarts when the deployment shares a stable secret. */
@Component
public class EntitlementCursorCodec {
    private final String key;
    private final ObjectMapper json;
    public EntitlementCursorCodec(@Value("${shop.pay.entitlement.cursor-key:}") String key, ObjectMapper json) {
        this.key = key;
        this.json = json;
    }
    public String encode(String tenant, String user, String filters, long lastId) {
        requireKey();
        var cursor = new Cursor(holder(tenant, user), filters, lastId,
                Instant.now().plusSeconds(1800).getEpochSecond());
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.writeValueAsString(cursor).getBytes(StandardCharsets.UTF_8));
        return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
    }
    public long decode(String token, String tenant, String user, String filters) {
        if (token == null) return Long.MAX_VALUE;
        requireKey();
        try {
            var pieces = token.split("\\.", -1);
            if (pieces.length != 2 || !MessageDigest.isEqual(sign(pieces[0]),
                    Base64.getUrlDecoder().decode(pieces[1]))) throw new IllegalArgumentException();
            var cursor = json.readValue(Base64.getUrlDecoder().decode(pieces[0]), Cursor.class);
            if (!holder(tenant, user).equals(cursor.holderHash()) || !filters.equals(cursor.filters())
                    || cursor.lastId() <= 0 || Instant.now().getEpochSecond() >= cursor.expiresAt()) {
                throw new IllegalArgumentException();
            }
            return cursor.lastId();
        } catch (RuntimeException invalid) { throw BizException.of(ErrorCode.BAD_REQUEST); }
    }
    private byte[] sign(String payload) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 required", impossible);
        }
    }
    private void requireKey() {
        if (key.length() < 32) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
    }
    private String holder(String tenant, String user) {
        return EntitlementRecords.digest(json.writeValueAsString(List.of(tenant, user)));
    }
    public record Cursor(String holderHash, String filters, long lastId, long expiresAt) { }
}
