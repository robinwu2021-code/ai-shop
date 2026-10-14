package ai.neargo.shop.platform;

import ai.neargo.shop.platform.config.BootstrapConfigService.MerchantApp;
import ai.neargo.shop.platform.config.ShopProperties;
import ai.neargo.shop.platform.config.impl.BootstrapConfigServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商家版 App 的下载地址与版本号：<b>以发版脚本写的清单为准，读不到就回落配置</b>。
 *
 * <p>为什么要有清单：在这之前版本号写死在三处 —— 官网 {@code site.config.ts}、
 * 服务器 env {@code SHOP_MERCHANT_APP_ANDROID}、以及人的记性。每处都要手工跟，
 * 于是每处都会掉队：2026-09-30 查出 env 那处停在 <b>0.4.98</b>，而官网已经发到
 * <b>0.5.21</b>，差了二十多个版本。
 *
 * <p><b>掉队时没有任何信号</b>：旧包还在 /dl/ 下，HTTP 200、下得动、装得上，
 * 只是功能旧。店主从小程序「复制 App 下载地址」拿到的就是它。
 * 这类「配了等于没配」只能靠对账发现，所以判据要落在这儿。
 */
class MerchantAppManifestTest {

    @TempDir
    Path tmp;

    private static ShopProperties propsWith(String android, String ios, String manifestFile) {
        var props = new ShopProperties();
        props.getMerchantApp().setAndroid(android);
        props.getMerchantApp().setIos(ios);
        props.getMerchantApp().setManifestFile(manifestFile);
        return props;
    }

    private static MerchantApp appOf(ShopProperties props) {
        return new BootstrapConfigServiceImpl(props).get().merchantApp();
    }

    @Test
    @DisplayName("★★★ 清单在，地址与版本号都以它为准 —— env 里那条旧的不再生效")
    void manifestWins() throws Exception {
        Path f = tmp.resolve("latest.json");
        Files.writeString(f, """
                {"version":"0.5.21","versionCode":254,
                 "url":"https://www.hxmall.top/dl/hxmall-merchant-latest.apk",
                 "file":"hxmall-merchant-0.5.21.apk","size":55246938,
                 "md5":"37398012257142ecb810fc2e1babc380","releasedAt":"2026-09-30T02:16:00Z"}
                """);

        // env 里放一条**过时**的地址 —— 线上真实的样子（它停在 0.4.98）
        var app = appOf(propsWith("https://www.hxmall.top/dl/hxmall-merchant-0.4.98.apk", "", f.toString()));

        assertThat(app.androidVersion())
                .as("版本号要从清单来 —— 这是「最新版是哪个」的唯一真源")
                .isEqualTo("0.5.21");
        assertThat(app.android())
                .as("★ 地址也要从清单来。还是 0.4.98 的话，店主下到的仍是二十多个版本前的包，"
                        + "而它 200、装得上、没有任何报错")
                .endsWith("hxmall-merchant-latest.apk");
    }

    @Test
    @DisplayName("★★ 没配清单（本机 / 切片测试）行为与改造前逐字相同")
    void noManifestKeepsOldBehaviour() {
        var app = appOf(propsWith("https://example.test/a.apk", "https://example.test/i", ""));

        assertThat(app.android()).isEqualTo("https://example.test/a.apk");
        assertThat(app.ios()).isEqualTo("https://example.test/i");
        assertThat(app.androidVersion())
                .as("没有清单就没有版本号 —— 空串让端上不显示，而不是显示一个猜的值")
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 清单不存在或坏了，回落 env —— 它不该变成「坏了就冷启动挂掉」的新依赖")
    void brokenManifestFallsBack() throws Exception {
        var missing = appOf(propsWith("https://example.test/a.apk", "", tmp.resolve("nope.json").toString()));
        assertThat(missing.android()).as("文件不存在时回落").isEqualTo("https://example.test/a.apk");
        assertThat(missing.androidVersion()).isEmpty();

        Path bad = tmp.resolve("bad.json");
        Files.writeString(bad, "{ 这不是 JSON");
        var broken = appOf(propsWith("https://example.test/a.apk", "", bad.toString()));
        assertThat(broken.android())
                .as("解析失败也要回落 —— /mp/config/bootstrap 是 C 端冷启动的第一跳，不能因此 500")
                .isEqualTo("https://example.test/a.apk");
        assertThat(broken.androidVersion()).isEmpty();
    }

    @Test
    @DisplayName("★★★ 清单下发的地址要带版本号 —— 固定文件名会被缓存着当新包给出去")
    void manifestUrlCarriesVersion() throws Exception {
        Path f = tmp.resolve("latest.json");
        Files.writeString(f, """
                {"version":"0.5.21",
                 "url":"https://www.hxmall.top/dl/hxmall-merchant-0.5.21.apk"}
                """);

        var app = appOf(propsWith("https://example.test/a.apk", "", f.toString()));

        /*
         * 这一条钉的是**发版脚本写清单的方式**，不是这段 Java 的分支。
         *
         * 第一版我让清单指 `hxmall-merchant-latest.apk`（那个软链），
         * site 的 constraints.test.ts 当场拦下来，理由写得很清楚：
         * 「文件名要带版本，否则覆盖同名文件时，浏览器与 CDN 会把旧包
         * 缓存着当新包给出去」。软链名字固定而内容会变，正好踩中。
         *
         * 分工是：**动态由清单负责，防缓存由文件名负责**。
         * latest 软链仍留着，给读不到清单的那条兜底路径用。
         */
        assertThat(app.android())
                .as("清单里的地址要指带版本号的真实文件，不是 latest 软链")
                .matches(".*hxmall-merchant-\\d+\\.\\d+\\.\\d+\\.apk$");
        assertThat(app.android()).contains(app.androidVersion());
    }

    @Test
    @DisplayName("★★ 清单里是半截路径就不认 —— 小程序打不开，而端上看不出它是坏的")
    void relativeUrlIsRejected() throws Exception {
        Path f = tmp.resolve("rel.json");
        // 第一版脚本写的就是 "/dl/..."，这条用例钉住那次改正
        Files.writeString(f, "{\"version\":\"0.5.21\",\"url\":\"/dl/hxmall-merchant-latest.apk\"}");

        var app = appOf(propsWith("https://example.test/a.apk", "", f.toString()));

        assertThat(app.android())
                .as("★ 半截路径「看起来是有值的」，端上不报错、只是点了没反应 —— 宁可用旧地址")
                .isEqualTo("https://example.test/a.apk");
        assertThat(app.androidVersion())
                .as("版本号照旧取得到：地址坏了不代表版本号也不可信")
                .isEqualTo("0.5.21");
    }
}
