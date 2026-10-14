package ai.neargo.shop.pay.channel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

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
    };

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

    /** 把 map 拍成 {@code k=v} 一行一条的文本，敏感值换成 {@value #MASK} */
    public static String mask(Map<String, ?> src) {
        if (src == null || src.isEmpty()) {
            return null;
        }
        return truncate(src.entrySet().stream()
                .map(e -> e.getKey() + "=" + (sensitive(e.getKey()) ? MASK : e.getValue()))
                .sorted()   // 固定顺序，两条报文才能直接 diff
                .collect(Collectors.joining("\n")));
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
