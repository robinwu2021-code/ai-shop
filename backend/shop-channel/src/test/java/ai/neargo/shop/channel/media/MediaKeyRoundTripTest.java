package ai.neargo.shop.channel.media;

import ai.neargo.shop.media.MediaKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>每个 provider 的每个出口，吐出来的地址都必须能被 {@link MediaKeys#extract} 抠回原 key。</b>
 *
 * <h2>这道闸补的是什么窟窿</h2>
 * <p>2026-08-30 生产上连着三天报「239 张图里 238 张被判为可回收」。
 * 根因：{@code MediaKeys} 的正则只认 {@code /uploads/|/media/} 两个前缀，
 * 而生产是 {@code shop.media.provider=cos} —— {@code publicUrl} 给的是 COS 域名、
 * {@code privatePath} 直接就是裸 key，<b>两个前缀一个都不含</b>，
 * 于是 {@code extract()} 恒返回空集，每张图都「没有任何引用」。
 *
 * <p>挡住误删的只有扫描器里那道「超过 50% 就中止」的阈值。
 *
 * <h2>为什么已有的守卫拦不住</h2>
 * <p>{@code MediaRefCoverageTest} 守的是「<b>装图的列都登记成了引用来源</b>」，
 * 它答不了「<b>那一列里的地址抠不抠得出来</b>」。
 * <b>声明齐全 ≠ 能被解析</b> —— 那道闸全绿的同时，回收扫描可以把 238/239 判成孤儿。
 *
 * <p>而所有 media 场景测试都跑在默认 provider 上
 * （{@code @ConditionalOnProperty(matchIfMissing = true)} → 本地盘），
 * 往列里写的正是 {@code "/uploads/" + key}。<b>被测的是本地盘那一半，生产跑的是另一半。</b>
 *
 * <h2>为什么对着 provider 验，而不是写死几种地址形态</h2>
 * <p>写死形态的断言只能证明「今天想到的那几种能抠出来」。
 * 这里调的是 <b>provider 自己的出口方法</b>，所以再加一个 provider、
 * 或者给 COS 挂上 CDN 域名、或者有人改了 {@code shop.upload.public-prefix}，
 * 这道闸都会自己红 —— 它钉的是不变量，不是当前实现。
 */
class MediaKeyRoundTripTest {

    /** 逐字取自 {@code LocalDiskMediaStore} 的类注释：entity/store/用途/yyyymm/名。 */
    private static final String KEY = "E0001/S0003/goods/202608/9f2c1a4b.jpg";

    /** 私有段（非 {@code goods}）走 {@code privatePath}，形态与公读段不同。 */
    private static final String PRIVATE_KEY = "E0001/S0003/license/202608/7d1e.png";

    private record Exit(String what, String address) {
    }

    /** 每个 provider 的每个出口各一条。**新增 provider 时这里要跟着加。** */
    private static List<Exit> allExits() {
        LocalDiskMediaStore local = new LocalDiskMediaStore(
                "./target/test-uploads", "/uploads", "/media", "test-secret");
        CosMediaStore cos = new CosMediaStore(
                "test-id", "test-key", "ap-guangzhou", "hxmall-test-1300000000", "");
        CosMediaStore cosCdn = new CosMediaStore(
                "test-id", "test-key", "ap-guangzhou", "hxmall-test-1300000000",
                "https://img.example.com/");

        return List.of(
                new Exit("本地盘 publicUrl", local.publicUrl(KEY)),
                new Exit("本地盘 privatePath", local.privatePath(PRIVATE_KEY)),
                new Exit("COS publicUrl（默认域名）", cos.publicUrl(KEY)),
                new Exit("COS privatePath（裸 key —— 08-30 栽的就是它）",
                        cos.privatePath(PRIVATE_KEY)),
                new Exit("COS publicUrl（挂了 CDN 域名）", cosCdn.publicUrl(KEY)));
    }

    private static String keyOf(Exit e) {
        return e.what().contains("private") ? PRIVATE_KEY : KEY;
    }

    @Test
    @DisplayName("★★★ 每个 provider 的每个出口都必须抠得回原 key —— 抠不回来，那张图就会被判成孤儿")
    void everyProviderExitRoundTrips() {
        for (Exit e : allExits()) {
            assertThat(MediaKeys.extract(e.address()))
                    .as("%s 吐出的地址 %s 抠不回 key。\n"
                            + "后果不是报错，是**回收扫描把这个 provider 下的图全判成没人引用**"
                            + "（2026-08-30 生产 238/239）。\n"
                            + "修 MediaKeys 让它认得这种形态，别改这条断言",
                            e.what(), e.address())
                    .contains(keyOf(e));
        }
    }

    @Test
    @DisplayName("★★ 嵌在 JSON 数组与富文本里也要抠得出来 —— 图片列有一半是这两种")
    void embeddedInJsonAndRichTextRoundTrips() {
        for (Exit e : allExits()) {
            String json = "[\"" + e.address() + "\",\"" + e.address() + "\"]";
            String html = "<p>看图</p><img src=\"" + e.address() + "\" />详情见上。";

            assertThat(MediaKeys.extract(json))
                    .as("JSON 数组里的 %s 抠不出来", e.what()).contains(keyOf(e));
            assertThat(MediaKeys.extract(html))
                    .as("富文本里的 %s 抠不出来", e.what()).contains(keyOf(e));
        }
    }

    /**
     * <b>对照：这条不通过，上面两条就不可证伪。</b>
     *
     * <p>一个「把 text 里所有子串都当 key 返回」的实现能让上面全绿。
     * 2026-08-28 在别处栽过一次：补的对照量恒为 0 而我没查，
     * 于是那条对照什么都没证明。
     */
    @Test
    @DisplayName("★ 对照：不是图片的东西不能被抠成 key")
    void doesNotExtractNonImages() {
        Set<String> keys = MediaKeys.extract(
                "订单 O20260830001 备注：见 https://example.com/help/faq.html 与 a/b.txt");
        assertThat(keys)
                .as("扩展名不在白名单里的一律不算图片 —— 抠出来的每一条都会去比一次记账行")
                .isEmpty();
    }

    /**
     * 剥前缀是个赌注：赌没有哪个 provider 的 key 真以 {@code uploads/} 开头。
     * {@code extract} 把带前缀的那一种也收进来，等于取消这个赌注。
     * <b>赌错的后果落在危险那侧</b>（抠出错 key → 对不上 → 判孤儿），所以要钉住。
     */
    @Test
    @DisplayName("★ key 本身以 uploads/ 开头时，两种形态都要收 —— 否则剥前缀会剥错")
    void keepsBothFormsWhenPrefixStripped() {
        /*
         * 地址 /uploads/uploads/x.jpg 有两种合理解读，取决于真实 key 是哪一个：
         *   - key = uploads/x.jpg  → 前缀只有外面那层（provider 拼上去的）
         *   - key = uploads/uploads/x.jpg → 前缀一层都没有（裸 key 就长这样）
         * 两种都收，比对时哪个对得上算哪个。**「x.jpg」不在其中** —— 那要剥两层，
         * 而没有任何 provider 会拼两层前缀。写这条断言时我先写成了 x.jpg，
         * 被这个测试自己抓住了。
         */
        Function<String, Set<String>> x = MediaKeys::extract;
        assertThat(x.apply("/uploads/uploads/x.jpg"))
                .as("剥掉一层之后，带前缀的原样也要在集合里")
                .contains("uploads/x.jpg", "uploads/uploads/x.jpg");
    }
}
