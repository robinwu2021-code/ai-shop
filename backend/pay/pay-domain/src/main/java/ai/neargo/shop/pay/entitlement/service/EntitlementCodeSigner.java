package ai.neargo.shop.pay.entitlement.service;

import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.digest;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.unavailable;

import ai.neargo.shop.pay.entitlement.entity.EntCodeToken;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** A durable key and a public random nonce permit idempotent replay without storing a plaintext token. */
@Component
public class EntitlementCodeSigner {
    private final byte[] key;
    private final ObjectMapper json;
    public EntitlementCodeSigner(@Value("${shop.pay.entitlement.code-key:}") String key, ObjectMapper json) {
        this.key = key.getBytes(StandardCharsets.UTF_8); this.json = json;
    }
    public String token(EntCodeToken row) {
        if (key.length < 32) unavailable();
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            var message = List.of("ENTITLEMENT_CODE_V1", row.getTenantNo(), row.getUserNo(), row.getInstanceNo(),
                    row.getRequestKeyHash(), row.getNonce());
            String raw = "ec1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(json.writeValueAsString(message).getBytes(StandardCharsets.UTF_8)));
            if (row.getTokenHash() != null && !row.getTokenHash().equals(digest(raw))) unavailable();
            return raw;
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("HMAC-SHA256 required", failure); }
    }
}
