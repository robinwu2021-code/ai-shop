package ai.neargo.shop.media;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从一段字符串里把图片 key 全抠出来。
 *
 * <p><b>一个提取器管所有形态</b>：单值列、JSON 数组、富文本正文，
 * 在这里没有区别 —— 都是「找出所有形如 {@code …/x.jpg} 的地址」。
 * 正因为不需要区分，{@link MediaRefColumn} 才去掉了「形态」字段，
 * 也就去掉了「声明错形态导致漏抠、进而误删」那一整类故障。
 *
 * <p>要吃下的写法（都是现实里真出现过的）：
 * <ul>
 *   <li>相对路径 {@code /uploads/E0001/…/x.jpg}（本地盘 provider）</li>
 *   <li>带域名的绝对 URL {@code https://<桶>.cos.<区域>.myqcloud.com/goods/…/x.jpg}
 *       （COS provider 的 {@code publicUrl}）</li>
 *   <li><b>裸 key</b> {@code goods/M0001/x.jpg}（COS provider 的 {@code privatePath}
 *       —— 私有段的图存库存的就是它，前面什么都没有）</li>
 * </ul>
 *
 * <h2>2026-08-30：这里曾经把生产上 238/239 张图判成孤儿</h2>
 * <p>上一版的正则是 {@code (?:/uploads/|/media/)(…)} —— <b>只认那两个前缀</b>。
 * 而生产早已 {@code shop.media.provider=cos}，地址是 COS 域名或裸 key，
 * <b>两个前缀一个都不含</b>，于是 {@link #extract} 恒返回空集，
 * 每张图都「没有任何引用」。挡住这件事的只有扫描器里那道
 * 「超过 50% 被判可回收就中止」的阈值，它连报了三天。
 *
 * <p>为什么测试全绿：media 的测试全跑在默认 provider 上
 * （{@code @ConditionalOnProperty(matchIfMissing = true)} → 本地盘），
 * 测试往列里写的正是 {@code "/uploads/" + key}。
 * <b>被测的是本地盘那一半，生产跑的是另一半。</b>
 * 判据见 {@code MediaKeyRoundTripTest}：它对着<b>每个 provider 的真实出口</b>验，
 * 而不是对着这里写死的几种形态 —— 再加 provider 或换 CDN 域名，它会自己红。
 *
 * <h2>宁可多抠，不要少抠</h2>
 * <p>多抠一个 key 的代价：它匹配不上任何记账行，白比一次字符串。
 * 少抠一个 key 的代价：那张图被判成孤儿，<b>不可逆</b>。
 * 两边不对称，所以下面每一处取舍都往「多抠」偏。
 */
public final class MediaKeys {

    /**
     * 先剥掉 {@code scheme://host}，剩下的一律当路径处理。
     *
     * <p><b>不这么做会抠出错的 key</b>：{@code [A-Za-z0-9_\-/]} 不含点，所以正则
     * 跨不过 {@code myqcloud.com} 里的点，会从最后一个点之后起匹配 ——
     * {@code https://b.cos.r.myqcloud.com/goods/x.jpg} 抠出来是
     * {@code com/goods/x.jpg}，多带一段 {@code com/}，一样对不上记账行。
     * 这个坑不响：它照样抠得出东西来，只是永远匹配不上。
     */
    private static final Pattern SCHEME_HOST =
            Pattern.compile("https?://[^/\\s\"'\\\\]+", Pattern.CASE_INSENSITIVE);

    /*
     * 路径段不含点，只有扩展名前那一个点 —— 排除 '.' 能避免
     * 「一直吃到句子末尾的另一个 .jpg」这种贪婪匹配。
     *
     * 前导的 `/`、`uploads/`、`media/` 都是可选的：剥掉之后剩下的就与
     * sys_media_asset.asset_key 同形。三者都可选，是为了同时吃下
     * 「/uploads/k」「/k」「k」三种，也就是两个 provider 的四个出口。
     */
    private static final Pattern KEY = Pattern.compile(
            "/?((?:uploads/|media/)?)([A-Za-z0-9_\\-/]+\\.(?:jpg|jpeg|png|webp|gif))",
            Pattern.CASE_INSENSITIVE);

    private MediaKeys() {
    }

    /** 抠出全部 key（与 {@code sys_media_asset.asset_key} 同形，不带任何前缀）。 */
    public static Set<String> extract(String text) {
        Set<String> keys = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return keys;
        }
        Matcher m = KEY.matcher(SCHEME_HOST.matcher(text).replaceAll(""));
        while (m.find()) {
            keys.add(m.group(2));
            /*
             * 前缀被剥掉时，**带前缀的那一种也收进来**。
             *
             * 「剥掉 uploads/」这一步本身是个赌注：赌没有哪个 provider 的 key
             * 真的以 uploads/ 开头。赌错的后果落在危险的那一侧 —— 抠出来的是
             * 错 key，对不上记账行，那张图被判成孤儿。多收一条就把这个赌注取消了，
             * 代价只是多比一次字符串。
             */
            if (!m.group(1).isEmpty()) {
                keys.add(m.group(1) + m.group(2));
            }
        }
        return keys;
    }
}
