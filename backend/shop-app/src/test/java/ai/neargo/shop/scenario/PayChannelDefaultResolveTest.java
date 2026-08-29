package ai.neargo.shop.scenario;

import ai.neargo.shop.platform.entity.SysPayChannel;
import ai.neargo.shop.platform.mapper.PlatformMappers.PayChannelMapper;
import ai.neargo.shop.spi.platform.MasterDataPort;
import ai.neargo.common.data.scope.DataScopeContext;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
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
 * <p>同时钉住 {@code sys_pay_channel.markets}。这一列从基线起就在
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
    private MasterDataPort masterData;
    @Autowired
    private PayChannelMapper channelMapper;
    @Autowired
    private ai.neargo.shop.merchant.service.MerchantPaymentService paymentService;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String ENTITY = "E-PCD-TEST";
    private static final String STORE = "ST-PCD-TEST";

    @AfterEach
    void restoreSeeds() {
        /*
         * **必须 executeWithoutScope。** 这几张表带数据域，直查会被过滤器改写 ——
         * SELECT 读出空集、UPDATE 静默 0 行，于是「还原」这一步<b>什么都没干</b>，
         * 而它不会报错。症状是本类单独跑绿、全量跑红，且红的是 StoreSettleFlowTest
         * 这种与支付通道毫无关系的用例（它拿不到可用通道，报 70045）。
         */
        DataScopeContext.executeWithoutScope(() -> {
            channelMapper.selectList(Wrappers.emptyWrapper()).forEach(r -> {
                r.setEnabled(true);
                r.setMarkets("[\"CN\"]");
                channelMapper.updateById(r);
            });
            return null;
        });
        /*
         * **还原失败要当场变红。** 不断言的话，还原悄悄没生效 → 下一个测试类
         * 拿不到可用通道 → 报错落在一个与支付通道毫无关系的用例上
         * （实测就是 StoreSettleFlowTest 的 70045）。
         * 让噪音落在制造它的那个类里，别让下一个人去考古。
         */
        List<SysPayChannel> after = DataScopeContext.executeWithoutScope(() ->
                channelMapper.selectList(Wrappers.emptyWrapper()));
        assertThat(after).as("还原之后必须还看得见通道行").isNotEmpty();
        assertThat(after).allSatisfy(r -> {
            assertThat(r.getEnabled()).as("通道 " + r.getPayChannel() + " 没被还原成启用").isTrue();
            assertThat(r.getMarkets()).as("通道 " + r.getPayChannel() + " 的 markets 没被还原").isEqualTo("[\"CN\"]");
        });

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
        assertThat(masterData.enabledChannels("CN"))
                .as("基线两个通道都启用，微信在前")
                .containsExactly("WECHAT", "ALIPAY");

        SysPayChannel wechat = row("WECHAT");
        wechat.setEnabled(false);
        DataScopeContext.executeWithoutScope(() -> channelMapper.updateById(wechat));

        assertThat(masterData.enabledChannels("CN"))
                .as("停用之后它就不该再被选中 —— 此前无论如何都是 WECHAT")
                .containsExactly("ALIPAY");
    }

    @Test
    @DisplayName("★★★ markets 不覆盖本市场的通道不出现 —— 这一列终于有人读了")
    void marketsFiltersChannels() {
        SysPayChannel alipay = row("ALIPAY");
        alipay.setMarkets("[\"AE\"]");
        DataScopeContext.executeWithoutScope(() -> channelMapper.updateById(alipay));

        assertThat(masterData.enabledChannels("CN")).containsExactly("WECHAT");
        assertThat(masterData.enabledChannels("AE")).containsExactly("ALIPAY");
    }

    @Test
    @DisplayName("★★★ markets 为空按全市场可用 —— 存量行都是空的，按「空=不可用」会让通道一夜消失")
    void blankMarketsMeansAllMarkets() {
        /*
         * **不能用 updateById 置 null**：MyBatis-Plus 默认跳过 null 字段，
         * 那一行会原样保留 ["CN"]，于是这个用例测的是「CN 的通道在 AE 不可见」——
         * 它会通过，但通过的原因与用例名毫无关系。写这个用例时就踩了一次。
         */
        DataScopeContext.executeWithoutScope(() -> channelMapper.update(null,
                Wrappers.<SysPayChannel>lambdaUpdate()
                        .eq(SysPayChannel::getPayChannel, "WECHAT")
                        .set(SysPayChannel::getMarkets, null)));
        assertThat(row("WECHAT").getMarkets()).as("先确认真的置空了").isNull();

        assertThat(masterData.enabledChannels("AE")).contains("WECHAT");
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
        SysPayChannel wechat = row("WECHAT");
        wechat.setMarkets("[\"CNY\"]");
        DataScopeContext.executeWithoutScope(() -> channelMapper.updateById(wechat));

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
}
