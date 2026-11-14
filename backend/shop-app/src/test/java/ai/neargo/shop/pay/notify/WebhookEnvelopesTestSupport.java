package ai.neargo.shop.pay.notify;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;

/** 接入方收推送的做法（P4 解密、P2 验签），独立实现 —— 场景测试用 */
public final class WebhookEnvelopesTestSupport {

    private WebhookEnvelopesTestSupport() {
    }

    public static String decrypt(String encryptKey, JsonNode resource) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptKey.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, Base64.getDecoder().decode(resource.get("nonce").asString())));
        c.updateAAD(resource.get("associatedData").asString().getBytes(StandardCharsets.UTF_8));
        return new String(c.doFinal(Base64.getDecoder().decode(resource.get("ciphertext").asString())),
                StandardCharsets.UTF_8);
    }

    public static String signature(String hmacKey, String ts, String nonce, String body) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(hmacKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal((ts + "\n" + nonce + "\n" + hash).getBytes(StandardCharsets.UTF_8)));
    }
}
