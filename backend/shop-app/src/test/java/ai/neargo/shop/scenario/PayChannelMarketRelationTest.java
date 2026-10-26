package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.payclient.OpsPayChannelAppService;
import ai.neargo.shop.spi.pay.PayChannelMasterPort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 通道 × 市场关系表（S12 · V295）。
 *
 * <h2>这组最容易假绿的地方</h2>
 * 「无行 = 不限市场」意味着<b>一张空表让所有断言都通过</b>。
 * 而 V295 的回填是 {@code INSERT … SELECT}，schema-test 生成器<b>刻意丢掉</b>
 * 这类语句（它的注释写着「那是数据回填，不是种子」）——
 * 于是测试库里这张表<b>本来就是空的</b>。
 *
 * <p>所以第一条用例先证明表里真的有行。它不通过的话，
 * 后面所有「筛对了」的断言都不成立 —— 它们只是没被筛而已。
 *
 * <p><b>本类改共享种子表，每个用例结束必须还原。</b>
 */
@SpringBootTest
@ActiveProfiles("test")
class PayChannelMarketRelationTest {

    private static final String INSERT =
            "INSERT INTO sys_pay_channel_market"
                    + " (pay_channel, market, tenant_no, created_at, created_by,"
                    + "  updated_at, updated_by, version, deleted)"
                    + " VALUES (?, ?, 'MAIN', '2026-09-02 00:00:00', 'SYSTEM',"
                    + "         '2026-09-02 00:00:00', 'SYSTEM', 0, 0)";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PayChannelMasterPort masterPort;
    @Autowired
    private OpsPayChannelAppService opsChannels;

    /** 改通道要留痕，而留痕要有操作人 —— 没登录态时 update 直接抛 unauthorized */
    private void asOps() {
        var u = new ai.neargo.shop.auth.LoginUser(
                ai.neargo.shop.auth.Realm.OPERATOR, ai.neargo.auth.store.SubjectKind.USR,
                "OPS-TEST", "测试运营", List.of(), List.of(), null, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }

    /** 用例前的全部市场行（W2 S8 起 TEST 与三个新渠道也有行，还原要按原样，不是只补 WECHAT / ALIPAY） */
    private List<Map<String, Object>> before;

    @BeforeEach
    void snapshot() {
        before = jdbc.queryForList("SELECT pay_channel, market FROM sys_pay_channel_market ORDER BY pay_channel, market");
    }

    @AfterEach
    void restore() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM sys_pay_channel_market");
        before.forEach(r -> jdbc.update(INSERT, r.get("pay_channel"), r.get("market")));
    }

    private List<String> marketsOf(String channel) {
        return jdbc.queryForList(
                "SELECT market FROM sys_pay_channel_market WHERE pay_channel = ? ORDER BY market",
                String.class, channel);
    }

    /**
     * ⚠️ 这一条<b>查种子文件，不查运行时</b>。
     *
     * 查运行时的话 {@code @AfterEach} 的还原会把行补回去 ——
     * 只有<b>最先跑到的那个用例</b>看得见空表。判据依赖执行顺序，
     * 等于没有判据：抽掉种子只红一条，而换个顺序就一条都不红。
     *
     * 这是本轮第二次踩同一个坑（S11 的市场种子是第一次）：
     * <b>清理逻辑会掩盖它自己要保护的那件事</b>。
     */
    @Test
    @DisplayName("★★★ 测试种子里有回填结果 —— 表空的话「无行=不限市场」会让整组假绿")
    void testSeedCarriesBackfillResult() throws IOException {
        Path extra = Path.of("src", "test", "resources", "schema-test-extra.sql").toRealPath();
        String seed = Files.readString(extra);
        assertThat(seed)
                .as("schema-test-extra.sql 里没有通道×市场的种子。"
                        + "V295 的回填是 INSERT…SELECT，schema-test 生成器<b>刻意丢掉</b>这类语句，"
                        + "于是测试库里这张表是空的 —— 而空表恰好命中「无行 = 不限市场」，"
                        + "本组每一条断言都会通过，通过的原因是「谁都没被筛」")
                .contains("INSERT IGNORE INTO sys_pay_channel_market")
                .contains("'WECHAT', 'CN'")
                .contains("'ALIPAY', 'CN'");

        Path mig = Path.of("src", "main", "resources", "db", "migration",
                "V295__pay_channel_market.sql").toRealPath();
        String sql = Files.readString(mig);
        assertThat(sql)
                .as("迁移里的回填没了 —— 那样生产上这张表也是空的，"
                        + "所有通道会在所有市场可用，而没有任何东西会报")
                .contains("INSERT IGNORE INTO sys_pay_channel_market")
                .contains("FROM sys_pay_channel c");

        /*
         * W2 S8：V12 把「无行 = 不限市场」翻成「无行 = 不可用」，TEST 的显式行必须是<b>常量种子</b>（INSERT … VALUES）——
         * 写成 INSERT … SELECT 的话 schema-test 生成器当回填丢掉，测试库里 TEST 一行都没有，30 个走支付链路的场景全红。
         */
        String v12 = Files.readString(Path.of("..", "pay", "pay-domain", "src", "main", "resources", "db", "pay",
                "V12__pay_channel_market_explicit.sql").toRealPath());
        assertThat(v12).contains("('TEST', 'CN'").contains("('TEST', 'TW'").contains("('TEST', 'AE'")
                .contains("('NEWEBPAY', 'TW'").contains("('LINEPAY', 'TW'").contains("('PAYBY', 'AE'");
    }

    @Test
    @DisplayName("★★ 运行时也确认一次种子生效了")
    void tableIsActuallyPopulated() {
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_pay_channel_market", Integer.class);
        assertThat(rows).isNotNull().isGreaterThan(0);
        assertThat(marketsOf("WECHAT")).containsExactly("CN");
        assertThat(marketsOf("ALIPAY")).containsExactly("CN");
    }

    @Test
    @DisplayName("★★★ 取可用通道按市场筛 —— 台湾商家不该看到只在大陆开的通道")
    void enabledChannelsFilteredByMarket() {
        assertThat(masterPort.enabledChannels("CN")).contains("WECHAT", "ALIPAY");
        assertThat(masterPort.enabledChannels("TW"))
                .as("WECHAT 只配了 CN，却在 TW 出现了 —— 商家会选到一个下单必失败的通道")
                .doesNotContain("WECHAT", "ALIPAY");
    }

    @Test
    @DisplayName("★★★ W2 S8 无行 = 不可用；TEST 靠显式行在每个市场可用（V12 把它的「不限」搬成了显式行）")
    void noRowsMeansUnavailableAndTestIsExplicit() {
        assertThat(marketsOf("TEST")).as("V12 给 TEST 每个市场一行")
                .containsExactly("AE", "CN", "HK", "SA", "SG", "TW");
        assertThat(masterPort.enabledChannels("TW")).contains("TEST");
        assertThat(masterPort.enabledChannels("CN")).contains("TEST");

        jdbc.update("DELETE FROM sys_pay_channel_market WHERE pay_channel = 'TEST'");
        assertThat(masterPort.enabledChannels("CN"))
                .as("无行还按「不限市场」的话，一个没配市场的新渠道会在大陆出现")
                .doesNotContain("TEST");
    }

    @Test
    @DisplayName("★★★ W2 S8 新渠道行在库里、默认停用、只在自己的市场")
    void newChannelsSeededDisabled() {
        var byChannel = opsChannels.channels().stream()
                .collect(Collectors.toMap(c -> c.payChannel(), c -> c));
        assertThat(byChannel).containsKeys("NEWEBPAY", "LINEPAY", "PAYBY");
        assertThat(byChannel.get("NEWEBPAY").enabled()).isFalse();
        assertThat(byChannel.get("LINEPAY").enabled()).isFalse();
        assertThat(byChannel.get("PAYBY").enabled()).isFalse();
        assertThat(marketsOf("NEWEBPAY")).containsExactly("TW");
        assertThat(marketsOf("LINEPAY")).containsExactly("TW");
        assertThat(marketsOf("PAYBY")).containsExactly("AE");
        assertThat(masterPort.enabledChannels("TW")).doesNotContain("NEWEBPAY", "LINEPAY");
    }

    @Test
    @DisplayName("★★★ W2 S8 运营把市场清空 = 拒绝（不是「全部市场」）—— 清空后通道在哪儿都不可用，要停用请用开关")
    void emptyMarketUpdateIsRejected() {
        asOps();
        assertThatThrownBy(() -> opsChannels.update("ALIPAY", null, "", null, null))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> opsChannels.update("ALIPAY", null, "[]", null, null))
                .isInstanceOf(BizException.class);
        assertThat(marketsOf("ALIPAY")).as("被拒的那次不能把行删了").containsExactly("CN");
    }

    @Test
    @DisplayName("★★★ 运营改市场是整体覆盖 —— 取消一个市场要真的取消，不能只增不减")
    void updateReplacesRatherThanAppends() {
        asOps();
        opsChannels.update("ALIPAY", null, "[\"CN\",\"TW\"]", null, null);
        assertThat(marketsOf("ALIPAY")).containsExactly("CN", "TW");
        assertThat(masterPort.enabledChannels("TW")).contains("ALIPAY");

        // 收回台湾
        opsChannels.update("ALIPAY", null, "[\"CN\"]", null, null);
        assertThat(marketsOf("ALIPAY"))
                .as("只增不减的话，运营在页面上取消一个市场<b>看起来成功而实际没变</b>")
                .containsExactly("CN");
        assertThat(masterPort.enabledChannels("TW")).doesNotContain("ALIPAY");
    }

    @Test
    @DisplayName("★★★ 运营端看到的 markets 形状没变 —— 换存储不该让 ops-web 跟着改")
    void opsContractShapeUnchanged() {
        var wechat = opsChannels.channels().stream()
                .filter(c -> "WECHAT".equals(c.payChannel())).findFirst().orElseThrow();
        assertThat(wechat.markets())
                .as("ops-web 那一页按这个字符串渲染；派生错了页面会显示空市场")
                .isEqualTo("[\"CN\"]");

        var test = opsChannels.channels().stream()
                .filter(c -> "TEST".equals(c.payChannel())).findFirst().orElseThrow();
        assertThat(test.markets()).as("TEST 的显式行照原样派生").isEqualTo("[\"AE\",\"CN\",\"HK\",\"SA\",\"SG\",\"TW\"]");

        // W2 S8：无行 = 不可用，派生成 [] 而不是 null —— null 在页面上一直显示成「全部市场」，那正是语义翻转后会说反的那句
        jdbc.update("DELETE FROM sys_pay_channel_market WHERE pay_channel = 'ALIPAY'");
        var alipay = opsChannels.channels().stream()
                .filter(c -> "ALIPAY".equals(c.payChannel())).findFirst().orElseThrow();
        assertThat(alipay.markets()).isEqualTo("[]");
    }

    @Test
    @DisplayName("★★ 那一列不再映射 —— 留着字段就会有人读到陈旧值，那正是 S1 缺陷的形状")
    void staleColumnIsNotMapped() throws IOException {
        Path entity = Path.of("..", "pay", "pay-channel", "src", "main", "java", "ai",
                "neargo", "shop", "pay", "channel", "entity", "SysPayChannel.java").toRealPath();
        String src = Files.readString(entity);

        assertThat(src)
                .as("SysPayChannel 又映射了 markets 那一列。V295 之后它不再被写入，"
                        + "读它拿到的是陈旧值 —— 运营改了、领域读不到、且不报错")
                .doesNotContain("private String markets;");
        // 扫描面断言：文件没读到的话上面那句什么都没证明
        assertThat(src).contains("class SysPayChannel", "private String payChannel;");
    }
}
