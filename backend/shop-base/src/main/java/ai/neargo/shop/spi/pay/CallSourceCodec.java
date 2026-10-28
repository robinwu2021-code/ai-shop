package ai.neargo.shop.spi.pay;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link CallSource} ↔ {@link InternalHeaders#CALL_SOURCE} 头：Base64URL（无填充）的 JSON。
 *
 * <p>它只用于留痕，所以两头都不失败：编码时单字段按落库列宽截断（整个头因此不会超过 {@link #MAX_HEADER}）；
 * 解码时缺失、乱码、不是对象、超长一律给空 —— 接收方照样放行，记 {@code UNKNOWN}（ADR-045 G9）。
 */
public final class CallSourceCodec {

    /** 头的上限：Tomcat 默认整个头区 8KB，这里只占一半 */
    public static final int MAX_HEADER = 4096;

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    private CallSourceCodec() {
    }

    public static String encode(CallSource s) {
        CallSource cut = new CallSource(cut(s.service(), 64), cut(s.module(), 24), cut(s.instance(), 128),
                cut(s.serverIp(), 64), cut(s.traceId(), 64), cut(s.originApi(), 128), cut(s.originClient(), 24),
                cut(s.originIp(), 64), cut(s.originUaDigest(), 64), cut(s.operatorNo(), 64), cut(s.invokedAt(), 40));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(cut));
    }

    public static Optional<CallSource> decode(String header) {
        if (header == null || header.isBlank() || header.length() > MAX_HEADER) {
            return Optional.empty();
        }
        try {
            byte[] json = Base64.getUrlDecoder().decode(header.trim());
            String text = new String(json, StandardCharsets.UTF_8).trim();
            if (!text.startsWith("{")) {
                return Optional.empty();
            }
            return Optional.of(JSON.readValue(text, CallSource.class));
        } catch (RuntimeException broken) {
            // 乱码、不是 JSON、类型不对：留痕用的头坏了不该让调用失败
            return Optional.empty();
        }
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
