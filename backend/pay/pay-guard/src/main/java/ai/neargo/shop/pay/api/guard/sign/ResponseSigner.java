package ai.neargo.shop.pay.api.guard.sign;

import ai.neargo.shop.pay.api.guard.ConditionalOnGuardEntry;
import ai.neargo.shop.pay.api.guard.PortalFace;
import ai.neargo.shop.pay.openapp.AppSigner;
import ai.neargo.shop.pay.openapp.SignatureCanonicalizer;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 开放 API 响应签名（开发计划 M3.4；API 清单 §1.2，向量 P2）：每个响应都签，含 401 / 403 ——
 * {@code X-Signature = Base64(HMAC(Timestamp\nNonce\nhex(SHA256(响应体))))}，用接入方当前那版密钥（{@link AppSigner}），
 * 带上 {@code X-Timestamp} · {@code X-Nonce}，有序列号时带 {@code X-Key-Serial}。
 *
 * <p>接入方没有可用密钥（AppId 不存在、未发、已吊销）时不签 —— 签了也只能用别人的。
 * 第一版「带头不强制验」（开发计划 §6 风险）：接入方可以先不验，平台照常签。
 */
@ConditionalOnGuardEntry(PortalFace.OPEN)
@Component
public class ResponseSigner {

    private final AppSigner signer;

    public ResponseSigner(AppSigner signer) {
        this.signer = signer;
    }

    /** 现在的时间戳、新 nonce */
    public Optional<Map<String, String>> sign(PayApp app, byte[] body) {
        return sign(app, String.valueOf(System.currentTimeMillis()), UUID.randomUUID().toString().replace("-", ""), body);
    }

    /** @return 要加到响应上的头；签不了为空 */
    public Optional<Map<String, String>> sign(PayApp app, String timestamp, String nonce, byte[] body) {
        return signer.sign(app, stringToSign(timestamp, nonce, body).getBytes(StandardCharsets.UTF_8)).map(s -> {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("X-Timestamp", timestamp);
            h.put("X-Nonce", nonce);
            if (s.keySerial() != null) {
                h.put("X-Key-Serial", s.keySerial());
            }
            h.put("X-Signature", s.base64());
            return h;
        });
    }

    static String stringToSign(String timestamp, String nonce, byte[] body) {
        return timestamp + "\n" + nonce + "\n" + SignatureCanonicalizer.sha256Hex(body == null ? new byte[0] : body);
    }
}
