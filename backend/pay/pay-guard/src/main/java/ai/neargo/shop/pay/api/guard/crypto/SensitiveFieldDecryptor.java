package ai.neargo.shop.pay.api.guard.crypto;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.api.guard.ConditionalOnGuardEntry;
import ai.neargo.shop.pay.api.guard.PortalFace;
import ai.neargo.shop.pay.api.guard.core.GuardResult;
import ai.neargo.shop.pay.openapp.AppFieldCipher;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 管线 ⑨ 敏感字段解密（开发计划 M3.5；TDD-支付服务-开放API加解密与密钥分发 §2.4 P3 · §2.7）：OpenAPI 标了
 * {@code x-sensitive} 的字段必须是 {@code Base64(IV ‖ 密文 ‖ Tag)}，AAD = {@code AppId|字段路径}；解成明文写回请求体交给 controller。
 *
 * <p>传了明文 → 400 {@code PAY_OPEN_FIELD_ENCRYPT_REQUIRED}；解不开（版本不认识 / 标签不符 / 字段被挪到别的路径）→
 * 400 {@code PAY_OPEN_FIELD_DECRYPT_FAILED}，不分哪一步。没传该字段、请求体不是 JSON 原样放行（后者交给 controller）。
 * 请求记录仍记原始（加密的）请求体 —— 明文只活在这一次请求里。
 */
@ConditionalOnGuardEntry(PortalFace.OPEN)
@Component
public class SensitiveFieldDecryptor {

    /** 动作 → 该动作请求体里的敏感字段路径（JSON 点路径，camelCase；API 清单 §2.2） */
    public static final Map<String, List<String>> FIELDS = Map.of(
            "CREATE_ONLINE", List.of("payer.phone", "payer.email", "payer.name"),
            "REQUEST_REFUND", List.of("refundAccount.bankCode", "refundAccount.accountNo", "refundAccount.accountName"));

    /** IV(12) + Tag(16)：比这还短的不可能是密文 */
    private static final int MIN_PACKED = 12 + 16;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** @param body 给 controller 的请求体（解密后；没改就是原样）；{@code rejection} 非空即回绝 */
    public record Outcome(byte[] body, GuardResult rejection) {
    }

    private final AppFieldCipher cipher;

    public SensitiveFieldDecryptor(AppFieldCipher cipher) {
        this.cipher = cipher;
    }

    public Outcome decrypt(PayApp app, String action, String encryptSerial, byte[] body) {
        List<String> fields = FIELDS.get(action);
        if (fields == null || body == null || body.length == 0) {
            return new Outcome(body, null);
        }
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (RuntimeException notJson) {
            return new Outcome(body, null);
        }
        if (!(root instanceof ObjectNode)) {
            return new Outcome(body, null);
        }
        boolean changed = false;
        for (String path : fields) {
            int dot = path.lastIndexOf('.');
            JsonNode parent = root.path(path.substring(0, dot));
            String leaf = path.substring(dot + 1);
            if (!(parent instanceof ObjectNode obj) || !obj.has(leaf) || obj.get(leaf).isNull()) {
                continue;
            }
            JsonNode value = obj.get(leaf);
            byte[] packed = value.isString() ? base64(value.asString()) : null;
            if (packed == null || packed.length < MIN_PACKED) {
                return new Outcome(body, reject(ErrorCode.PAY_OPEN_FIELD_ENCRYPT_REQUIRED, path));
            }
            Optional<byte[]> plain = cipher.decrypt(app, encryptSerial, packed,
                    (app.getAppNo() + "|" + path).getBytes(StandardCharsets.UTF_8));
            if (plain.isEmpty()) {
                return new Outcome(body, reject(ErrorCode.PAY_OPEN_FIELD_DECRYPT_FAILED, path));
            }
            obj.put(leaf, new String(plain.get(), StandardCharsets.UTF_8));
            changed = true;
        }
        return new Outcome(changed ? JSON.writeValueAsBytes(root) : body, null);
    }

    private static GuardResult reject(ErrorCode code, String path) {
        return new GuardResult(null, 400, code, "REJECTED_PARAM", code.name() + " " + path);
    }

    private static byte[] base64(String s) {
        try {
            return Base64.getDecoder().decode(s.trim());
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
    }
}
