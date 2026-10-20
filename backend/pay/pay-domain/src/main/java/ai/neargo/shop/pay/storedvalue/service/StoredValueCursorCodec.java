package ai.neargo.shop.pay.storedvalue.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Signed keyset cursor bound to a single tenant, member and query filter. */
@Component
public class StoredValueCursorCodec {
    private final String key;
    private final ObjectMapper json;

    public StoredValueCursorCodec(@Value("${shop.pay.entitlement.cursor-key:}") String key, ObjectMapper json) {
        this.key = key;
        this.json = json;
    }

    public String encode(String kind, String tenant, String user, String filters, long occurredAtMillis, String sortKey) {
        requireKey();
        var cursor = new Cursor(kind, holder(tenant, user), filters, occurredAtMillis, sortKey,
                Instant.now().plusSeconds(1800).getEpochSecond());
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.writeValueAsString(cursor).getBytes(StandardCharsets.UTF_8));
        return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
    }

    public Cursor decode(String token, String kind, String tenant, String user, String filters) {
        if (token == null) return null;
        requireKey();
        try {
            var pieces = token.split("\\.", -1);
            if (pieces.length != 2 || !MessageDigest.isEqual(sign(pieces[0]),
                    Base64.getUrlDecoder().decode(pieces[1]))) throw new IllegalArgumentException();
            var cursor = json.readValue(Base64.getUrlDecoder().decode(pieces[0]), Cursor.class);
            if (!kind.equals(cursor.kind()) || !holder(tenant, user).equals(cursor.holderHash())
                    || !filters.equals(cursor.filters()) || invalidTime(kind,cursor.occurredAtMillis())
                    || cursor.sortKey() == null || cursor.sortKey().isBlank()
                    || Instant.now().getEpochSecond() >= cursor.expiresAt()) throw new IllegalArgumentException();
            return cursor;
        } catch (RuntimeException invalid) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private static boolean invalidTime(String kind,long at) {
        if(!"RIGHTS_USAGE".equals(kind)) return at<=0;
        return at<Instant.parse("1000-01-01T00:00:00Z").toEpochMilli()
                || at>Instant.parse("9999-12-31T23:59:59.999Z").toEpochMilli();
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

    public record Cursor(String kind, String holderHash, String filters, long occurredAtMillis, String sortKey,
                         long expiresAt) { }
}
