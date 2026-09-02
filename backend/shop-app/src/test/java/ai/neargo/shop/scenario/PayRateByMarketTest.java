package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.channel.entity.SysPayChannelRate;
import ai.neargo.shop.pay.channel.master.PayChannelRateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通道费率按市场分档（S14 · V297）。
 *
 * <h2>为什么现在做</h2>
 * 2026-09-02 查过生产：{@code sys_pay_channel_rate} <b>0 行</b>。
 * 等有了行再加这一维，就要回答「这一行当时指的是哪个市场」——
 * 而那个问题问不出来：默认填 CN 会把一条本该全局通用的费率钉死在大陆，
 * 默认填通配又会让本该只适用大陆的费率漏到别的市场。
 * <b>两个方向都错，且错了不报。</b>
 *
 * <h2>这组真正守的是回退顺序</h2>
 * 八档回退里，市场必须是<b>最外层</b>。排在里层的话，
 * 一条「大陆 · 通用」会盖过「台湾 · 企业」——
 * 表现是按错误的国家费率结算，而金额看着完全正常。
 */
@SpringBootTest
@ActiveProfiles("test")
class PayRateByMarketTest {

    private static final String CH = "WECHAT";
    private static final long AT = 1_700_000_000_000L;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PayChannelRateService rates;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM sys_pay_channel_rate WHERE rate_no LIKE 'RT-MK-%'");
    }

    /** market 传 null 模拟 V297 之前写入的存量行 */
    private void rate(String no, String market, String method, String form, int bp) {
        jdbc.update("INSERT INTO sys_pay_channel_rate (rate_no, pay_channel, market, pay_method,"
                        + " legal_form, rate_bp, min_fee_minor, effective_from, enabled,"
                        + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,1,NOW(),NOW())",
                no, CH, market, method, form, bp, 0L, AT - 1000);
    }

    @Test
    @DisplayName("★★★ 市场是最外层 —— 「本市场的通用价」要压过「全球的专属价」")
    void marketOutranksOtherDimensions() {
        /*
         * ⚠️ 这一条第一版构造错了，消融时才发现：
         * 原来配的是「大陆·通用」与「台湾·JSAPI·企业」，查台湾 ——
         * <b>而一条 market=CN 的行在查 TW 时两种顺序下都匹配不上</b>
         * （市场那一维只会试 {TW, *}）。于是把市场挪到最里层，用例照样全绿。
         * 判据里没有任何东西与「顺序」有关。
         *
         * 真正分得开的是下面这组：两条都能匹配，而先取哪条取决于顺序。
         *   A 台湾 · 通用 · 通用      ← 市场最外层时取它
         *   B 全球 · JSAPI · 企业     ← 市场最里层时取它
         *
         * <b>A 才是对的</b>：市场是合同边界。「台湾 · 通用」是台湾那份合同里
         * 写明的价，「全球 · JSAPI · 企业」是没指定市场的兜底。
         * 取 B 意味着拿一个兜底价去覆盖一份签过字的合同价 ——
         * 而金额看着完全正常，要到对账时才发现收多了或收少了。
         */
        rate("RT-MK-TW-ANY", "TW", SysPayChannelRate.ANY, SysPayChannelRate.ANY, 38);
        rate("RT-MK-ANY-SPEC", SysPayChannelRate.ANY, "JSAPI", "ENTERPRISE", 60);

        var tw = rates.effective("TW", CH, "JSAPI", "ENTERPRISE", AT);
        assertThat(tw).isNotNull();
        assertThat(tw.rateBp())
                .as("取到了那条不分市场的专属价 —— 那是拿兜底价盖过了台湾合同里写明的价")
                .isEqualTo(38);

        // 正对照：换一个没单独配过的市场，这时才该落到那条全球专属价上
        assertThat(rates.effective("SG", CH, "JSAPI", "ENTERPRISE", AT).rateBp())
                .as("新加坡没配过自己的费率，理应回退到不分市场的那条")
                .isEqualTo(60);
    }

    @Test
    @DisplayName("★★★ 本市场没配就回退到通配 —— 不是取不到")
    void fallsBackToWildcardMarket() {
        rate("RT-MK-ANY", SysPayChannelRate.ANY, SysPayChannelRate.ANY,
                SysPayChannelRate.ANY, 55);

        var hk = rates.effective("HK", CH, "JSAPI", "MICRO", AT);
        assertThat(hk)
                .as("回退没生效的话，一个还没单独配费率的市场会取不到费率 ——"
                        + "而取不到是留空不兜 0，表现是手续费栏位空着")
                .isNotNull();
        assertThat(hk.rateBp()).isEqualTo(55);
    }

    @Test
    @DisplayName("★★ market 是空串时按通配读 —— null 被约束挡住了，空串没有")
    void blankMarketReadsAsWildcard() {
        /*
         * 这一条本来写的是「存量行 market 为 null」——<b>那个场景构造不出来</b>：
         * 列是 NOT NULL DEFAULT '*'，而 ADD COLUMN 带默认值时已有行会被填上。
         * 迁移里也跟着删掉了一句 `UPDATE ... WHERE market IS NULL`：
         * 那个条件永远不成立，SQL 一行都改不到，却看起来像是在兜底。
         *
         * 空串是<b>约束挡不住的那个退化值</b>，所以留这一条。
         */
        rate("RT-MK-BLANK", "", SysPayChannelRate.ANY, SysPayChannelRate.ANY, 42);

        var r = rates.effective("CN", CH, "JSAPI", "MICRO", AT);
        assertThat(r)
                .as("空串按「一个自成一格的市场」处理的话，这条费率谁都取不到 ——"
                        + "而结算侧取不到是留空不兜 0，表现是手续费栏位空着，"
                        + "且每一笔单看都算得对")
                .isNotNull();
        assertThat(r.rateBp()).isEqualTo(42);
    }

    @Test
    @DisplayName("★★ 不传市场按通配 —— 运营端今天没有市场选择器，默认不能是 CN")
    void addDefaultsToWildcardNotCn() {
        SysPayChannelRate r = new SysPayChannelRate();
        r.setRateNo("RT-MK-DEF");
        r.setPayChannel(CH);
        r.setRateBp(30);
        r.setMinFeeMinor(0L);
        r.setEffectiveFrom(AT - 1000);
        r.setEnabled(true);
        rates.add(r);

        String stored = jdbc.queryForObject(
                "SELECT market FROM sys_pay_channel_rate WHERE rate_no = 'RT-MK-DEF'",
                String.class);
        assertThat(stored)
                .as("默认成 CN 的话，一条本该全局通用的费率会被悄悄钉死在大陆，"
                        + "而运营在页面上根本没做过这个选择")
                .isEqualTo(SysPayChannelRate.ANY);

        assertThat(rates.effective("SG", CH, "JSAPI", "MICRO", AT).rateBp()).isEqualTo(30);
    }

    @Test
    @DisplayName("★★ 一条都没配时返回 null —— 不许兜 0：「没配过」与「配了 0%」事后必须分得开")
    void noRateReturnsNull() {
        assertThat(rates.effective("CN", "NO_SUCH_CHANNEL", "JSAPI", "MICRO", AT))
                .as("兜 0 之后没有任何人能回答「这笔手续费当时是免的，还是根本没人配」")
                .isNull();
    }
}
