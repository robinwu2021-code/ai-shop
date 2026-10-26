package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.channel.entity.SysPayChannel;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelMapper;
import ai.neargo.shop.spi.platform.MasterDataPort;
import ai.neargo.common.data.scope.DataScopeContext;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 默认支付通道来自注册表，不是写死的。
 *
 * <p><b>此前 {@code MerchantPaymentServiceImpl} 写着「没指定通道就用 WECHAT」。</b>
 * 对只做支付宝的商家、以及将来任何非中国市场的商家，那个默认都是错的 ——
 * 而它错得没有声音：开出来的是一个商家根本没打算开的通道，
 * 要到第一笔订单收不到钱才看得出来。
 *
 * <p>同时钉住通道 × 市场的筛选。它此前是 {@code sys_pay_channel.markets}
 * 一列 JSON 文本，V295 起是 {@code sys_pay_channel_market} 关系表。这一列从基线起就在
 * （注释写着「该通道在哪些市场可用」），但在此之前<b>没有任何地方读它</b> ——
 * 一列没人读的数据不是扩展点，是一句承诺。
 *
 * <p><b>本类会改 {@code sys_pay_channel} 这张共享种子表，每个用例结束必须还原</b>。
 * 不还原的话：单独跑绿、全量跑红，而报错会落在一个与支付毫无关系的用例上。
 */
@SpringBootTest
@ActiveProfiles("test")
class PayChannelDefaultResolveTest {

    @Autowired
    /** 通道属性 2026-09-01 归 pay，这里改用 PayChannelMasterPort */
    private ai.neargo.shop.spi.pay.PayChannelMasterPort masterData;
    @Autowired
    private PayChannelMapper channelMapper;
    @Autowired
    private ai.neargo.shop.merchant.service.MerchantPaymentService paymentService;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String MARKET_ROWS =
            "SELECT pay_channel, market FROM sys_pay_channel_market ORDER BY pay_channel, market";
    private static final String ENTITY = "E-PCD-TEST";
    private static final String STORE = "ST-PCD-TEST";

    /** 用例前的原样：每个通道的开关、全部市场行（W2 S8：种子里有停用的新渠道，不能「一律还原成启用」） */
    private Map<String, Boolean> enabledBefore;
    private List<Map<String, Object>> marketsBefore;

    @BeforeEach
    void snapshotSeeds() {
        enabledBefore = new LinkedHashMap<>();
        DataScopeContext.executeWithoutScope(() -> {
            channelMapper.selectList(Wrappers.emptyWrapper())
                    .forEach(r -> enabledBefore.put(r.getPayChannel(), r.getEnabled()));
            return null;
        });
        marketsBefore = jdbc.queryForList(MARKET_ROWS);
    }

    @AfterEach
    void restoreSeeds() {
        /*
         * **必须 executeWithoutScope。** 这几张表带数据域，直查会被过滤器改写 ——
         * SELECT 读出空集、UPDATE 静默 0 行，于是「还原」这一步<b>什么都没干</b>，
         * 而它不会报错。症状是本类单独跑绿、全量跑红，且红的是 StoreSettleFlowTest
         * 这种与支付通道毫无关系的用例（它拿不到可用通道，报 70045）。
         *
         * 还原成<b>用例前的原样</b>，不是「全部启用」：V12 起种子里有三条默认停用的新渠道（蓝新、LINE Pay、PayBy），
         * 一律启用等于把它们在测试库里开出来；测试渠道 TEST 则由 schema-test-extra.sql 开着，原样还原它仍开着。
         */
        DataScopeContext.executeWithoutScope(() -> {
            channelMapper.selectList(Wrappers.emptyWrapper()).forEach(r -> {
                r.setEnabled(enabledBefore.get(r.getPayChannel()));
                channelMapper.updateById(r);
            });
            return null;
        });
        jdbc.update("DELETE FROM sys_pay_channel_market");
        for (Map<String, Object> row : marketsBefore) {
            jdbc.update("INSERT INTO sys_pay_channel_market"
                    + " (pay_channel, market, tenant_no, created_at, created_by,"
                    + "  updated_at, updated_by, version, deleted)"
                    + " VALUES (?, ?, 'MAIN', '2026-09-02 00:00:00', 'SYSTEM', '2026-09-02 00:00:00', 'SYSTEM', 0, 0)",
                    row.get("pay_channel"), row.get("market"));
        }
        /*
         * **还原失败要当场变红。** 不断言的话，还原悄悄没生效 → 下一个测试类
         * 拿不到可用通道 → 报错落在一个与支付通道毫无关系的用例上
         * （实测就是 StoreSettleFlowTest 的 70045）。
         * 让噪音落在制造它的那个类里，别让下一个人去考古。
         */
        Map<String, Boolean> enabledAfter = new LinkedHashMap<>();
        DataScopeContext.executeWithoutScope(() -> {
            channelMapper.selectList(Wrappers.emptyWrapper())
                    .forEach(r -> enabledAfter.put(r.getPayChannel(), r.getEnabled()));
            return null;
        });
        assertThat(enabledAfter).as("还原之后必须还看得见通道行").isNotEmpty();
        assertThat(enabledAfter).as("通道开关没被还原").isEqualTo(enabledBefore);
        assertThat(jdbc.queryForList(MARKET_ROWS)).as("通道的市场行没被还原").isEqualTo(marketsBefore);

        // 自己造的行自己收拾：留在共享库里会让别的用例莫名其妙多出一个主体
        jdbc.update("DELETE FROM mch_payment_merchant WHERE entity_no = ?", ENTITY);
        jdbc.update("DELETE FROM mch_store WHERE entity_no = ?", ENTITY);
        jdbc.update("DELETE FROM mch_entity WHERE entity_no = ?", ENTITY);
    }

    /** 一个主体 + 一个门店 + 一条该通道的主体级收款行（openForStore 要拿它当模板） */
    private void fixture(String baseChannel) {
        jdbc.update("INSERT INTO mch_entity (entity_no, name, created_at, updated_at) VALUES (?,?,NOW(),NOW())",
                ENTITY, "通道默认值测试主体");
        jdbc.update("INSERT INTO mch_store (entity_no, store_no, created_at, updated_at) VALUES (?,?,NOW(),NOW())",
                ENTITY, STORE);
        jdbc.update("INSERT INTO mch_payment_merchant (entity_no, store_no, pay_channel, legal_form,"
                        + " apply_status, created_at, updated_at) VALUES (?,?,?,?,?,NOW(),NOW())",
                ENTITY, "", baseChannel, "MICRO", "ACTIVE");
    }

    private SysPayChannel row(String channel) {
        return DataScopeContext.executeWithoutScope(() -> channelMapper.selectOne(Wrappers.<SysPayChannel>lambdaQuery()
                .eq(SysPayChannel::getPayChannel, channel).last("LIMIT 1")));
    }

    @Test
    @DisplayName("★★★ 停用微信之后，默认通道跟着变 —— 不再写死 WECHAT")
    void defaultFollowsRegistry() {
        /*
         * 断言**顺序与包含**，不断言总数 —— 测试库里还有一条 TEST（S4 起必须开着）。
         * 写死总数的话，每加一个渠道这一条就红一次，而它测的根本不是渠道有几个。
         */
        assertThat(masterData.enabledChannels("CN"))
                .as("基线：微信与支付宝都启用，且微信在支付宝前面")
                .containsSubsequence("WECHAT", "ALIPAY");

        SysPayChannel wechat = row("WECHAT");
        wechat.setEnabled(false);
        DataScopeContext.executeWithoutScope(() -> channelMapper.updateById(wechat));

        assertThat(masterData.enabledChannels("CN"))
                .as("停用之后它就不该再被选中 —— 此前无论如何都是 WECHAT")
                .doesNotContain("WECHAT").contains("ALIPAY");
    }

    @Test
    @DisplayName("★★★ markets 不覆盖本市场的通道不出现 —— 这一列终于有人读了")
    void marketsFiltersChannels() {
        jdbc.update("DELETE FROM sys_pay_channel_market WHERE pay_channel = 'ALIPAY'");
        jdbc.update("INSERT INTO sys_pay_channel_market"
                + " (pay_channel, market, tenant_no, created_at, created_by,"
                + "  updated_at, updated_by, version, deleted)"
                + " VALUES ('ALIPAY','AE','MAIN','2026-09-02 00:00:00','SYSTEM',"
                + "         '2026-09-02 00:00:00','SYSTEM',0,0)");
        assertThat(marketsOf("ALIPAY")).as("先确认真的改成 AE 了").containsExactly("AE");

        // 只看支付宝在哪个市场出现 —— 这一条测的是 markets 筛选，不是渠道总数
        assertThat(masterData.enabledChannels("CN"))
                .doesNotContain("ALIPAY").contains("WECHAT");
        assertThat(masterData.enabledChannels("AE"))
                .contains("ALIPAY").doesNotContain("WECHAT");
    }

    @Test
    @DisplayName("★★★ W2 S8 没有市场行 = 哪个市场都不可用 —— 新渠道没配市场就不该在大陆出现")
    void noMarketRowsMeansUnavailable() {
        /*
         * V12 之前的语义正相反（「无行 = 不限市场」）：那时存量通道的行是空的，按「空 = 不可用」会一夜消失。
         * V12 把存量的「不限」搬成了显式行（TEST 每个市场一行），语义才能翻过来 ——
         * 否则一个没配市场的新渠道（蓝新 / LINE Pay / PayBy）会在所有市场出现，包括大陆。
         *
         * 关系表没有 updateById 跳过 null 的坑：<b>删掉行就是删掉行</b>。
         */
        jdbc.update("DELETE FROM sys_pay_channel_market WHERE pay_channel = 'WECHAT'");
        assertThat(marketsOf("WECHAT")).as("先确认真的删空了").isEmpty();

        assertThat(masterData.enabledChannels("CN")).doesNotContain("WECHAT").contains("ALIPAY");
        assertThat(masterData.enabledChannels("AE")).doesNotContain("WECHAT");
    }

    /** 从关系表读一个通道的市场，排序后返回 —— 断言要稳定 */
    private List<String> marketsOf(String payChannel) {
        return jdbc.queryForList(
                "SELECT market FROM sys_pay_channel_market WHERE pay_channel = ? ORDER BY market",
                String.class, payChannel);
    }

    @Test
    @DisplayName("★★★ 一个通道都没有时返回空 —— 不许兜一个默认，兜底就是把钱发到没开户的通道")
    void noChannelReturnsEmptyNotFallback() {
        DataScopeContext.executeWithoutScope(() -> {
            channelMapper.selectList(Wrappers.emptyWrapper()).forEach(r -> {
                r.setEnabled(false);
                channelMapper.updateById(r);
            });
            return null;
        });

        List<String> available = masterData.enabledChannels("CN");
        assertThat(available).as("空列表是合法结果，调用方自己报错").isEmpty();
    }

    @Test
    @DisplayName("市场码大小写不同不算命中 —— \"CN\" 不该匹配 [\"CNY\"] 这种前缀重名")
    void marketMatchIsExact() {
        /*
         * 这一条在 JSON 文本时代防的是「包含匹配」—— 一段文本里找 CN，
         * ["CNY"] 会命中。关系表里 market 是一个离散值，比的是相等，
         * 结构上就不会出这个错。
         *
         * **仍然留着**：V295 的<b>回填</b>用的正是朴素子串匹配
         * （理由写在迁移里：那句只跑一次，跑的时候库里是什么我查过）。
         * 万一将来有人把那套匹配挪到运行时来，这一条会当场变红。
         */
        jdbc.update("DELETE FROM sys_pay_channel_market WHERE pay_channel = 'WECHAT'");
        jdbc.update("INSERT INTO sys_pay_channel_market"
                + " (pay_channel, market, tenant_no, created_at, created_by,"
                + "  updated_at, updated_by, version, deleted)"
                + " VALUES ('WECHAT','CNY','MAIN','2026-09-02 00:00:00','SYSTEM',"
                + "         '2026-09-02 00:00:00','SYSTEM',0,0)");

        assertThat(masterData.enabledChannels("CN"))
                .as("CN 不该命中 CNY").doesNotContain("WECHAT");
    }

    @Test
    @DisplayName("★★★ 给门店开户不传通道时，开出来的是注册表里可用的那个 —— 不是写死的微信")
    void openForStoreUsesRegistryNotHardcodedWechat() {
        // 只留支付宝可用
        SysPayChannel wechat = row("WECHAT");
        wechat.setEnabled(false);
        DataScopeContext.executeWithoutScope(() -> channelMapper.updateById(wechat));
        fixture("ALIPAY");

        var vo = paymentService.openForStore(ENTITY, STORE, null);

        /*
         * 这一条才是那次修复的判据。上面几条验的是 enabledChannels 这个新方法，
         * **验的是过程不是结果** —— 方法对了，而调用点仍然写死 WECHAT 的话，
         * 那几条照样全绿。
         */
        assertThat(vo.payChannel()).as("不许再回退到 WECHAT").isEqualTo("ALIPAY");
        Integer wechatRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mch_payment_merchant WHERE entity_no = ? AND pay_channel = 'WECHAT'",
                Integer.class, ENTITY);
        assertThat(wechatRows).as("更不许顺手插一条微信的行").isZero();
    }

    @Test
    @DisplayName("★★★ 「我还能开什么」：没开过的通道也要给一行 NONE，不是不返回")
    void availableChannelsIncludesUnopened() {
        fixture("WECHAT");   // 只开了微信

        var all = paymentService.availableChannels(ENTITY);

        assertThat(all).extracting(v -> v.payChannel())
                .as("启用中的通道都要在列表里（只开了微信的进件，支付宝应显示为未进件）")
                .contains("WECHAT", "ALIPAY");
        assertThat(all).filteredOn(v -> "ALIPAY".equals(v.payChannel()))
                .singleElement()
                .satisfies(v -> assertThat(v.applyStatus())
                        .as("没开过的给 NONE 占位 —— 不返回的话页面永远长不出「去开通支付宝」")
                        .isEqualTo("NONE"));
    }

    @Test
    @DisplayName("★★★ 停用的通道不出现在「能开什么」里 —— 让人去开一个已停用的通道是死路")
    void availableChannelsSkipsDisabled() {
        SysPayChannel alipay = row("ALIPAY");
        alipay.setEnabled(false);
        DataScopeContext.executeWithoutScope(() -> channelMapper.updateById(alipay));
        fixture("WECHAT");

        /*
         * 停用 ALIPAY 之后剩 WECHAT 与 TEST。
         *
         * TEST 是 S4 之后测试库里必须开着的那个（唯一有网关实现的通道）——
         * 期望值里写上它，而不是把它从 setup 里排除掉：
         * 排除会让「还原成启用」与「保持关闭」两句话互相打架，撞过一次。
         */
        assertThat(paymentService.availableChannels(ENTITY))
                .extracting(v -> v.payChannel()).containsExactlyInAnyOrder("WECHAT", "TEST");
    }
}
