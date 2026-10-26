package ai.neargo.shop.pay.channel;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 报文落库前的脱敏。
 *
 * <p><b>为什么必须在这一层做，而不是「落库时小心点」</b>：
 * {@link ChannelClient} 的注释已经写了同一个顾虑 ——
 * 任何一次「打个日志看看参数」都可能把私钥或签名串写出去。
 * 报文表比日志更危险：它会被备份、会被导出成 CSV、会被整段贴进工单。
 *
 * <p>做成<b>按键名的白名单反面</b>（命中就遮）而不是「列出要存哪些字段」：
 * 通道随时会加字段，而漏遮一个新字段的代价远大于多遮一个无害字段。
 * 遮错了顶多让排查少一条线索，漏遮一次是凭据泄露。
 *
 * <p><b>递归</b>（R30，TDD-支付服务-W2渠道层补完 S2）：此前只看第一层键，而微信回调的 {@code nonce} 在 {@code resource} 里、
 * 分账接收方是一组、蓝新把 {@code TradeSha} 放在一段字符串化的 JSON 里 —— 第一层干净不等于整篇干净。
 * 嵌套 Map / List 逐层遮；字符串值像 JSON 就先解析再遮。
 */
public final class PayloadMasker {

    /** 报文列是 TEXT，但没必要存整篇 —— 超过这个长度的报文,后面基本是重复结构 */
    static final int MAX_LEN = 4000;

    private static final String MASK = "***";

    /**
     * 键名命中任一片段就遮掉整个值。
     *
     * <p>{@code serial} 是微信的证书序列号，{@code nonce} 与 {@code timestamp}
     * 单独看无害，<b>但它们与签名串一起构成可重放的一组</b>，所以一并遮。
     */
    private static final String[] SENSITIVE = {
            "sign", "key", "secret", "token", "cert", "serial",
            "auth", "password", "credential", "nonce", "private",
            // R30：时间戳（注释一直这么说，代码此前没有）；蓝新 TradeSha / CheckCode / CheckValue；
            // LINE Pay 的 ChannelSecret 已被 secret 命中，单列是为了让「新渠道的键在不在规则里」一眼可查
            "timestamp", "tradesha", "checkcode", "checkvalue", "hashiv", "channelsecret",
    };

    /** 递归的深度上限：报文不会真有这么深，到了就整段遮掉 —— 宁可多遮 */
    private static final int MAX_DEPTH = 16;

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private PayloadMasker() {
    }

    /** 键名是否要遮。大小写不敏感 —— 通道的头是 {@code Wechatpay-Signature}，体里是 {@code sign} */
    public static boolean sensitive(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase(Locale.ROOT);
        for (String s : SENSITIVE) {
            if (k.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把 map 拍成 {@code k=v} 一行一条的文本，敏感值换成 {@value #MASK}。第一层的形状照旧（{@code stl_channel_message} 的读者认它），
     * 值里的嵌套结构递归遮后以 JSON 写出。
     */
    public static String mask(Map<String, ?> src) {
        if (src == null || src.isEmpty()) {
            return null;
        }
        return truncate(src.entrySet().stream()
                .map(e -> e.getKey() + "=" + (sensitive(e.getKey()) ? MASK : render(maskValue(e.getValue(), 1))))
                .sorted()   // 固定顺序，两条报文才能直接 diff
                .collect(Collectors.joining("\n")));
    }

    /**
     * 整段报文文本（HTTP 请求 / 响应体）。JSON 递归遮后照原形状写回；{@code k=v&k=v} 表单按参数遮（值先 URL 解码，
     * 支付宝的 {@code biz_content} 是编码过的 JSON）；两者都不是的（XML、加密串）认不出哪里敏感，只留指纹与长度。
     */
    public static String maskText(String body) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        String t = body.strip();
        if (t.startsWith("{") || t.startsWith("[")) {
            Object tree = parseJson(t);
            if (tree != null) {
                return truncate(render(maskValue(tree, 0)));
            }
        }
        if (looksLikeForm(t)) {
            return truncate(maskForm(t));
        }
        return "不可识别的报文，只留指纹\nsha256=" + fingerprint(body) + "\nlength=" + body.length();
    }

    /** 深拷贝并遮：Map 按键判、List 逐项、像 JSON 的字符串解析后再遮。返回的是新对象，入参不动 */
    static Object maskValue(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            return MASK;
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> {
                String key = String.valueOf(k);
                out.put(key, sensitive(key) ? MASK : maskValue(v, depth + 1));
            });
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            l.forEach(v -> out.add(maskValue(v, depth + 1)));
            return out;
        }
        if (value instanceof String s) {
            String t = s.strip();
            if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) {
                Object tree = parseJson(t);
                if (tree != null) {
                    return render(maskValue(tree, depth + 1));
                }
            }
        }
        return value;
    }

    private static Object parseJson(String text) {
        try {
            return JSON.readValue(text, Object.class);
        } catch (JacksonException notJson) {
            return null;
        }
    }

    /** Map / List 写成 JSON；标量原样 */
    private static String render(Object value) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                return JSON.writeValueAsString(value);
            } catch (JacksonException e) {
                return MASK;
            }
        }
        return String.valueOf(value);
    }

    private static boolean looksLikeForm(String text) {
        return !text.contains("\n") && !text.contains(" ") && text.contains("=");
    }

    private static String maskForm(String text) {
        List<String> out = new ArrayList<>();
        for (String pair : text.split("&")) {
            int i = pair.indexOf('=');
            String key = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String value = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            out.add(key + "=" + (sensitive(key) ? MASK : render(maskValue(value, 1))));
        }
        return String.join("&", out);
    }

    /** 没验过签的报文只留这么长 —— 见 {@link #unverified} */
    static final int UNVERIFIED_LEN = 512;

    /**
     * <b>没通过验签的报文</b>。存指纹 + 一小段前缀，<b>不存全文</b>。
     *
     * <p>理由不是脱敏，是这段文本的来源：回调端点公网可达，
     * <b>任何人都能往它 POST 任意内容</b>。把没验过签的 body 原样落库，
     * 等于给了一个免费的写入口 —— 几兆的 body 灌几万次就是存储事故，
     * 而内容还会原样出现在运营控制台里。
     *
     * <p>指纹（SHA-256 前 16 位）回答的是排查时真正要问的那个问题：
     * <b>「通道是在重推同一份，还是每次都不一样」</b>。
     * 前者是我方验签配置错了，后者才是有人在扫端点 —— 两种处置完全不同，
     * 而这个区分不需要全文。
     */
    public static String unverified(String body) {
        if (body == null) {
            return null;
        }
        String head = body.length() <= UNVERIFIED_LEN
                ? body
                : body.substring(0, UNVERIFIED_LEN) + "…";
        return "未验签报文，只留指纹与前缀\nsha256=" + fingerprint(body)
                + "\nlength=" + body.length() + "\nhead=" + head;
    }

    /** SHA-256 的前 16 个十六进制位。够区分「同一份重推」与「每次都不同」，又不占地方 */
    public static String fingerprint(String body) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须实现的算法，走到这里说明运行环境被裁过
            throw new IllegalStateException("JDK 没有 SHA-256", e);
        }
    }

    private static String truncate(String s) {
        if (s == null || s.length() <= MAX_LEN) {
            return s;
        }
        return s.substring(0, MAX_LEN) + "\n…（已截断，原长 " + s.length() + "）";
    }
}
