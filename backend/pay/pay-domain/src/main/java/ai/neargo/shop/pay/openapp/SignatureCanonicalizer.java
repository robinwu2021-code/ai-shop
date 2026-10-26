package ai.neargo.shop.pay.openapp;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 开放 API 签名 v1 的待签串（安全规范 §2.3）。<b>发布即冻结</b>：改任一规则都只能出 v2，对外文档的示例向量逐字依赖它。
 * <pre>
 * stringToSign = METHOD \n PATH \n CANONICAL_QUERY \n X-App-Id \n X-Timestamp \n X-Nonce \n hex(SHA256(原始请求体))
 * CANONICAL_QUERY = 参数名按字典序（同名按值字典序），名与值各自 RFC 3986 编码，用 &amp; 连接；没有查询串为空串
 * </pre>
 */
public final class SignatureCanonicalizer {

    private SignatureCanonicalizer() {
    }

    public static String stringToSign(String method, String path, Map<String, List<String>> query, String appNo,
                                      String timestamp, String nonce, String bodySha256Hex) {
        return method.toUpperCase() + "\n" + path + "\n" + canonicalQuery(query) + "\n" + appNo + "\n" + timestamp + "\n"
                + nonce + "\n" + bodySha256Hex;
    }

    public static String canonicalQuery(Map<String, List<String>> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        new TreeMap<>(query).forEach((k, vs) -> vs.stream().sorted()
                .forEach(v -> parts.add(encode(k) + "=" + encode(v == null ? "" : v))));
        return String.join("&", parts);
    }

    public static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** RFC 3986：空格 %20、星号 %2A、波浪号不编码 */
    static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }
}
