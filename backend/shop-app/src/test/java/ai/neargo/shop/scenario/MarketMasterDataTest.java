package ai.neargo.shop.scenario;

import ai.neargo.shop.platform.PlatformConfigService;
import ai.neargo.shop.spi.pay.MarketPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 市场主数据（S11 · V294）—— 从一段 JSON 升格成表。
 *
 * <h2>为什么值得单独测</h2>
 * 换存储最容易出的错不是「读不出来」，是<b>读出来了但形状变了</b>：
 * ops-web 那一页按 {@code MarketVO} 渲染，少一个字段就是一列空白，
 * 而接口返回 200、日志一行不报。
 *
 * <p>所以这组既测新的表接口，也测<b>旧的调用方看到的东西没变</b>。
 */
@SpringBootTest
@ActiveProfiles("test")
class MarketMasterDataTest {

    @Autowired
    private MarketPort marketPort;
    @Autowired
    private PlatformConfigService platformConfig;

    /** ⚠️ 市场是全局主数据，改了要还原 —— 留着会让别的用例看到一个开着的台湾市场 */
    @AfterEach
    void restore() {
        marketPort.saveRate("TW", 4.4d, false, "TEST-RESTORE");
        marketPort.saveRate("CN", 1.0d, true, "TEST-RESTORE");
    }

    /**
     * ⚠️ 这一条<b>直接查种子文件</b>，不查运行时状态。
     *
     * 查运行时的话，{@code @AfterEach} 的还原会把 TW 关回去 ——
     * 于是「种子默认全开」这个错<b>测不出来</b>：消融把种子里的 TW 改成 enabled=1，
     * 测试照样全绿，因为还原逻辑替它擦了屁股。
     *
     * <b>判据被自己的清理逻辑掩盖</b>，是这类用例最隐蔽的一种假绿。
     */
    @Test
    @DisplayName("★★★ 种子里只有大陆是开的 —— 查迁移文件，不查运行时（还原会掩盖它）")
    void seedEnablesOnlyCn() throws java.io.IOException {
        java.nio.file.Path sql = java.nio.file.Path.of("..", "shop-app", "src", "main",
                "resources", "db", "migration", "V294__sys_market.sql").toRealPath();
        String src = java.nio.file.Files.readString(sql);

        java.util.List<String> enabledInSeed = new java.util.ArrayList<>();
        for (var m : java.util.regex.Pattern
                .compile("\\(\\d+, '([A-Z]{2})',[^)]*?,\\s*(\\d),\\s*\\d+, 'MAIN'")
                .matcher(src).results().toList()) {
            if ("1".equals(m.group(2))) {
                enabledInSeed.add(m.group(1));
            }
        }
        assertThat(enabledInSeed)
                .as("种子里开了不止大陆 —— 上线当天商家能选到一个通道都没配的市场，"
                        + "而下单时才失败。开市场是运营动作，不是上线动作")
                .containsExactly("CN");
        // 扫描面断言：一条都没解析出来的话，上面那句什么都没证明
        assertThat(src).contains("'TW'", "'HK'", "'SA'");
    }

    @Test
    @DisplayName("★★★ 六个市场都在，且大陆是启用的")
    void seedHasSixMarketsOnlyCnEnabled() {
        var all = marketPort.all();

        assertThat(all).extracting(MarketPort.MarketBrief::market)
                .contains("CN", "TW", "HK", "SG", "AE", "SA");
        assertThat(all).filteredOn(MarketPort.MarketBrief::enabled)
                .extracting(MarketPort.MarketBrief::market)
                .as("大陆没开的话整个平台都下不了单")
                .contains("CN");
    }

    @Test
    @DisplayName("★★★ 小数位是每个市场自己的 —— 端上写死 2 会让日元差 100 倍")
    void currencyScaleIsPerMarket() {
        assertThat(marketPort.find("CN")).isPresent();
        assertThat(marketPort.find("CN").get().currencyScale())
                .as("人民币是 2 位").isEqualTo(2);
        assertThat(marketPort.find("CN").get().currency()).isEqualTo("CNY");
        assertThat(marketPort.find("TW").get().currency()).isEqualTo("TWD");
    }

    @Test
    @DisplayName("★★★ 查不到的市场返回空，不兜底成大陆 —— 兜底会让「码写错了」看起来正常")
    void unknownMarketReturnsEmpty() {
        assertThat(marketPort.find("NO_SUCH_MARKET"))
                .as("兜底成 CN 的话，一个写错的市场码会静默地按大陆算账")
                .isEmpty();
        assertThat(marketPort.find(null)).isEmpty();
        assertThat(marketPort.find("")).isEmpty();
    }

    @Test
    @DisplayName("★★★ 平台配置那一页看到的形状一个字段都没变 —— 换存储不该让调用方跟着改")
    void platformConfigContractUnchanged() {
        var vos = platformConfig.markets();

        assertThat(vos).isNotEmpty();
        var cn = vos.stream().filter(v -> "CN".equals(v.code())).findFirst();
        assertThat(cn).as("平台配置里读不到大陆 —— ops-web 那一页会是空的").isPresent();
        assertThat(cn.get().name()).isNotBlank();
        assertThat(cn.get().currency()).isEqualTo("CNY");
        assertThat(cn.get().timezone()).as("时区为空的话账期按哪儿切天就没有依据").isNotBlank();
        assertThat(cn.get().rate()).isEqualTo(1.0d);
        assertThat(cn.get().enabled()).isTrue();
    }

    @Test
    @DisplayName("★★ 改汇率与启停能落库；基准货币的汇率仍然只能是 1")
    void saveRateWorksAndKeepsGuards() {
        platformConfig.saveMarketRate("TW", 4.5d, true, "OPS1");

        var tw = marketPort.find("TW").orElseThrow();
        assertThat(tw.displayRate()).isEqualTo(4.5d);
        assertThat(tw.enabled()).isTrue();

        // 两条既有校验换存储之后仍在 —— 换存储不该顺手放宽规则
        assertThatThrownBy(() -> platformConfig.saveMarketRate("CN", 1.2d, true, "OPS1"))
                .as("基准货币的汇率是换算原点，改了整套价格都失去参照")
                .isNotNull();
        assertThatThrownBy(() -> platformConfig.saveMarketRate("TW", 0d, true, "OPS1"))
                .as("汇率为 0 会让折算出来的价格是 0")
                .isNotNull();
    }

    @Test
    @DisplayName("★★★ 币种与小数位没有修改入口 —— 改它等于换账本，而历史账不会跟着变")
    void currencyIsNotEditable() {
        String before = marketPort.find("TW").orElseThrow().currency();
        int scaleBefore = marketPort.find("TW").orElseThrow().currencyScale();

        platformConfig.saveMarketRate("TW", 4.6d, true, "OPS1");

        var after = marketPort.find("TW").orElseThrow();
        assertThat(after.currency())
                .as("保存汇率把币种也改了 —— 那意味着这个市场的历史账全部失去含义")
                .isEqualTo(before);
        assertThat(after.currencyScale()).isEqualTo(scaleBefore);
    }
}
