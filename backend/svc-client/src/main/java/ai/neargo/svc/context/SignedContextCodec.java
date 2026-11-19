package ai.neargo.svc.context;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 签名上下文的编解码，出站与入站共用同一份（TDD-网关层与统一权限 §2.7）。
 *
 * <p>两个请求头：{@link #HEADER_CONTEXT} 是上下文本身（Base64URL），{@link #HEADER_SIGNATURE} 是对它做的 HMAC-SHA256。
 * 方法、地址、请求体摘要都在上下文里，所以一个签名就把「谁」与「调了什么」绑在一起：改任何一样，签名都对不上。
 *
 * <p>上下文用「键=值&键=值」而不是 JSON：这个模块不带 JSON 库，而字段是固定的几项。
 */
public final class SignedContextCodec {

    public static final String HEADER_CONTEXT = "X-Gateway-Context";
    public static final String HEADER_SIGNATURE = "X-Gateway-Signature";
    private static final String HMAC = "HmacSHA256";

    private SignedContextCodec() {
    }

    /** 编成请求头的值。 */
    public static String encode(SignedContext context) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("op", context.operator());
        fields.put("realm", context.realm());
        fields.put("mch", context.merchantNo());
        fields.put("store", context.storeNo());
        fields.put("points", String.join(",", context.points()));
        fields.put("rid", context.requestId());
        fields.put("ts", Long.toString(context.timestamp()));
        fields.put("method", context.method());
        fields.put("path", context.path());
        fields.put("body", context.bodyDigest());
        // 只在有动作时才写：不带动作的上下文与加这一项之前逐字节相同
        if (!context.actions().isEmpty()) {
            fields.put("actions", String.join(",", context.actions()));
        }
        StringBuilder out = new StringBuilder();
        fields.forEach((key, value) -> {
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(key).append('=').append(URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8));
        });
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 解不开返回空，不抛：头是外面来的，什么形状都可能。 */
    public static Optional<SignedContext> decode(String header) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(header.trim()), StandardCharsets.UTF_8);
            Map<String, String> fields = new LinkedHashMap<>();
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    fields.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
            return Optional.of(new SignedContext(blankToNull(fields.get("op")), blankToNull(fields.get("realm")),
                    blankToNull(fields.get("mch")), blankToNull(fields.get("store")), list(fields.get("points")),
                    blankToNull(fields.get("rid")), Long.parseLong(fields.getOrDefault("ts", "0")),
                    blankToNull(fields.get("method")), blankToNull(fields.get("path")),
                    blankToNull(fields.get("body")), list(fields.get("actions"))));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 对请求头的值签名。 */
    public static String sign(String encodedContext, String key) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC));
            return HexFormat.of().formatHex(mac.doFinal(encodedContext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 比签名用定长比较，不让比对耗时泄露前缀。 */
    public static boolean signatureMatches(String encodedContext, String key, String signature) {
        if (signature == null) {
            return false;
        }
        return MessageDigest.isEqual(sign(encodedContext, key).getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    public static String digest(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> list(String joined) {
        List<String> out = new ArrayList<>();
        for (String item : (joined == null ? "" : joined).split(",")) {
            if (!item.isBlank()) {
                out.add(item);
            }
        }
        return out;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
