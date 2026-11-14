package ai.neargo.shop.pay.notify;

import ai.neargo.shop.pay.notify.entity.PayBizNotify;
import ai.neargo.shop.pay.openapp.AppFieldCipher;
import ai.neargo.shop.pay.openapp.AppSigner;
import ai.neargo.shop.pay.openapp.SignatureCanonicalizer;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 推送的体与头（开发计划 M3.6；API 清单 §3；TDD-支付服务-开放API加解密与密钥分发 §2.4 P4）：
 * <ul>
 *   <li>业务内容（{@code pay_biz_notify.payload_masked} 里的 {@code data}，本就只有掩码）整体放进 {@code resource}：
 *       AES-256-GCM（接入方 EncryptKey），{@code nonce} 单独一个字段、每次新生成，AAD = {@code eventType|eventId}，
 *       {@code ciphertext} = 密文 ‖ Tag（不含 nonce）；</li>
 *   <li>签名同响应签名（P2）：{@code HMAC(Timestamp\nNonce\nhex(SHA256(体)))}，带 {@code X-Key-Serial}；</li>
 *   <li>首投用当前版本，<b>重推用首投时冻结的版本</b>（{@code enc_key_version} / {@code sign_key_version}，V14）。</li>
 * </ul>
 * 过渡：接入方还没有 EncryptKey 时体照旧带明文 {@code data}（推送内容只有掩码），签名仍按 P2。
 */
@Component
public class WebhookEnvelopes {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AppSigner signer;
    private final AppFieldCipher cipher;

    public WebhookEnvelopes(AppSigner signer, AppFieldCipher cipher) {
        this.signer = signer;
        this.cipher = cipher;
    }

    /**
     * @param encKeyVersion  这次加密用的 EncryptKey 版本（首投写进推送行冻结）；没加密或没有版本为空
     * @param signKeyVersion 这次签名用的密钥版本（同上）
     */
    public record Envelope(String body, Map<String, String> headers, Integer encKeyVersion, Integer signKeyVersion) {
    }

    /** 接入方没有可用的签名密钥时为空（推送失败、按退避重试） */
    public Optional<Envelope> build(PayBizNotify n, PayApp app) {
        JsonNode stored = JSON.readTree(n.getPayloadMasked());
        String associated = n.getEventType() + "|" + n.getEventKey();
        byte[] data = JSON.writeValueAsBytes(stored.get("data"));
        Optional<AppFieldCipher.Encrypted> enc = cipher.encrypt(app, n.getEncKeyVersion(), data,
                associated.getBytes(StandardCharsets.UTF_8));

        ObjectNode body = JSON.createObjectNode();
        body.put("eventId", n.getEventKey());
        body.put("eventType", n.getEventType());
        if (n.getStateVersion() != null) {
            body.put("stateVersion", n.getStateVersion());
        }
        body.set("createdAt", stored.get("createdAt"));
        if (enc.isPresent()) {
            byte[] packed = enc.get().packed();
            ObjectNode resource = body.putObject("resource");
            resource.put("algorithm", "AEAD_AES_256_GCM");
            if (enc.get().keySerial() != null) {
                resource.put("keySerial", enc.get().keySerial());
            }
            resource.put("nonce", Base64.getEncoder().encodeToString(Arrays.copyOfRange(packed, 0, 12)));
            resource.put("associatedData", associated);
            resource.put("ciphertext", Base64.getEncoder().encodeToString(Arrays.copyOfRange(packed, 12, packed.length)));
        } else {
            body.set("data", stored.get("data"));
        }
        String text = JSON.writeValueAsString(body);

        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String toSign = ts + "\n" + nonce + "\n" + SignatureCanonicalizer.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
        return signer.sign(app, n.getSignKeyVersion(), toSign.getBytes(StandardCharsets.UTF_8)).map(s -> {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Content-Type", "application/json; charset=UTF-8");
            h.put("X-App-Id", app.getAppNo());
            h.put("X-Timestamp", ts);
            h.put("X-Nonce", nonce);
            if (s.keySerial() != null) {
                h.put("X-Key-Serial", s.keySerial());
            }
            h.put("X-Signature", s.base64());
            h.put("X-Event-Id", n.getEventKey());
            return new Envelope(text, h, enc.map(AppFieldCipher.Encrypted::keyVersion).orElse(null), s.keyVersion());
        });
    }
}
