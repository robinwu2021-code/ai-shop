package ai.neargo.shop.media;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从一段字符串里把图片 key 全抠出来。
 *
 * <p><b>一个提取器管所有形态</b>：单值列、JSON 数组、富文本正文，
 * 在这里没有区别 —— 都是「找出所有形如 {@code /uploads/E0001/…/x.jpg} 的地址」。
 * 正因为不需要区分，{@link MediaRefColumn} 才去掉了「形态」字段，
 * 也就去掉了「声明错形态导致漏抠、进而误删」那一整类故障。
 *
 * <p>顺带能吃下几种现实里会出现的写法：
 * <ul>
 *   <li>相对路径 {@code /uploads/…}（当前存库的形态）</li>
 *   <li>带域名的绝对 URL {@code https://cdn.example.com/uploads/…}（切 CDN 之后）</li>
 *   <li>存量的两段式 {@code /uploads/M2026…/x.png}（不搬家，长期共存）</li>
 * </ul>
 */
public final class MediaKeys {

    /*
     * 路径段不含点，只有扩展名前那一个点 —— 所以字符类里排除 '.' 能避免
     * 「一直吃到句子末尾的另一个 .jpg」这种贪婪匹配。
     *
     * 前缀写成 uploads|media 两选一而不是通配：通配会把正文里随便一个
     * 「xxx/yyy.jpg」也当成资产 key，那些抠出来匹配不上任何记账行，
     * 属于无害的噪音，但会让扫描白跑很多字符串比较。
     */
    private static final Pattern KEY = Pattern.compile(
            "(?:/uploads/|/media/)([A-Za-z0-9_\\-/]+\\.(?:jpg|jpeg|png|webp|gif))",
            Pattern.CASE_INSENSITIVE);

    private MediaKeys() {
    }

    /** 抠出全部 key（不含 {@code /uploads} 这类前缀，与 {@code sys_media_asset.asset_key} 同形）。 */
    public static Set<String> extract(String text) {
        Set<String> keys = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return keys;
        }
        Matcher m = KEY.matcher(text);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }
}
