package ai.neargo.shop.scenario;

import ai.neargo.shop.payclient.OpsPayChannelAppService;
import ai.neargo.shop.spi.pay.PayChannelMasterPort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

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

    @AfterEach
    void restore() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM sys_pay_channel_market");
        jdbc.update(INSERT, "WECHAT", "CN");
        jdbc.update(INSERT, "ALIPAY", "CN");
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
                .contains("INSERT INTO sys_pay_channel_market")
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
    @DisplayName("★★★ 无行按不限市场 —— TEST 通道刻意留空，它要能在任何市场的链路上验证")
    void noRowsMeansAllMarkets() {
        assertThat(marketsOf("TEST")).as("种子里 TEST 本来就不该有行").isEmpty();
        assertThat(masterPort.enabledChannels("TW"))
                .as("无行被当成「哪个市场都不可用」的话，TEST 会一夜消失，"
                        + "而 30 个走支付链路的场景测试跟着全红")
                .contains("TEST");
        assertThat(masterPort.enabledChannels("CN")).contains("TEST");
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
        assertThat(test.markets())
                .as("无行要派生成 null（= 不限市场），派生成 [] 的话页面会显示「一个市场都不可用」")
                .isNull();
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
