package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.channel.entity.SysPayChannel;
import ai.neargo.shop.pay.channel.master.PayChannelMasterService;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlWithdraw;
import ai.neargo.shop.pay.mapper.SettleMappers;
import ai.neargo.shop.pay.service.WithdrawService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 国际化的渠道筛选 · 测试渠道 · 提现申请（V288 三件事）。
 */
@SpringBootTest
@ActiveProfiles("test")
class MarketChannelAndWithdrawTest {

    @Autowired
    private PayChannelMasterService channels;
    @Autowired
    private TestPayGateway testGateway;
    @Autowired
    private WithdrawService withdraws;
    @Autowired
    private MerchantMappers.MchEntityMapper entityMapper;
    @Autowired
    private SettleMappers.BillMapper billMapper;

    private static int seq = 0;

    /**
     * ⚠️ <b>改过的共享种子必须还原。</b>
     *
     * 本类为了验市场筛选改了 WECHAT 的 markets、为了验「不限市场」开了 TEST ——
     * 两者都是<b>全局主数据</b>。不还原的话，
     * {@code PayChannelDefaultResolveTest} 的 {@code containsExactly("WECHAT")}
     * 会因为多出一条 TEST 而红，<b>而它的报错与本类毫不相干</b>，
     * 表现是「单独跑绿、全量跑红」。撞过一次才加的。
     */
    @org.junit.jupiter.api.AfterEach
    void restoreChannelSeeds() {
        channels.updateSettings("WECHAT", true, "[\"CN\"]", "CNY", "T1");
        /*
         * ⚠️ TEST 还原成**开着**，不是关着。
         *
         * 上一版这里写的是 false，理由是「默认关是刻意的」——
         * <b>那句话说的是生产</b>（V288 种的 enabled=0，不能让假通道
         * 出现在真实渠道列表里）。而测试库的初始状态是<b>开着</b>：
         * schema-test-extra.sql 为 S4 专门开了它，因为下单要取
         * 「运营开着的 ∩ 网关有实现的」，而只有 TEST 两头都占。
         *
         * 还原到生产默认值 = 把后面所有走支付链路的用例的地基抽掉。
         * 症状是本类之后的某个用例报「$.data 是 null」，
         * <b>而报错与支付通道看不出任何关系</b>。
         *
         * 还原的目标是<b>「我进来时的样子」，不是「产品的默认值」</b>。
         */
        channels.updateSettings("TEST", true, "", "CNY", "T1");
    }

    // ─────────────────────────── ① 渠道按区域筛

    /** 用种子里已有的渠道改市场标签 —— updateSettings 只改已有，不建新 */
    @Test
    @DisplayName("★★★ 只在大陆开的渠道，台湾商家看不到 —— 否则点进去进件必被拒")
    void channelFilteredByMarket() {
        channels.updateSettings("WECHAT", true, "[\"CN\"]", "CNY", "T1");

        assertThat(channels.enabled("CN")).extracting(SysPayChannel::getPayChannel)
                .as("大陆商家应当看到只在 CN 开的渠道").contains("WECHAT");
        assertThat(channels.enabled("TW")).extracting(SysPayChannel::getPayChannel)
                .as("台湾商家不该看到只在 CN 开的渠道 —— 这正是 V288 之前的实况："
                        + "两处取渠道都传 null，一律按默认市场算").doesNotContain("WECHAT");
    }

    @Test
    @DisplayName("★★ 不限区域的渠道，哪个市场都看得到 —— 空 markets 是「不限」不是「都不给」")
    void unrestrictedChannelVisibleEverywhere() {
        // 测试渠道（V288 种的）markets 为空 = 不限市场，但默认 enabled=0，先开它
        channels.updateSettings("TEST", true, "", "CNY", "T1");

        assertThat(channels.enabled("CN")).extracting(SysPayChannel::getPayChannel).contains("TEST");
        assertThat(channels.enabled("TW")).extracting(SysPayChannel::getPayChannel).contains("TEST");
        assertThat(channels.enabled("AE")).extracting(SysPayChannel::getPayChannel).contains("TEST");
        // 不在这里关：测试库里它本来就是开的（见 restoreChannelSeeds 的说明）
    }

    @Test
    @DisplayName("★★ 主体挂了市场，取渠道时才有输入 —— 字段读不出来等于没加")
    void entityCarriesMarket() {
        String no = "M-MKT-" + (++seq);
        MchEntity e = new MchEntity();
        e.setEntityNo(no);
        e.setName("测试主体");
        e.setMarket("TW");
        e.setStatus(MchEntity.ACTIVE);
        DataScopeContext.executeWithoutScope(() -> entityMapper.insert(e));

        MchEntity loaded = DataScopeContext.executeWithoutScope(() ->
                entityMapper.selectOne(Wrappers.<MchEntity>lambdaQuery().eq(MchEntity::getEntityNo, no)));
        assertThat(loaded.getMarket())
                .as("加了列不补实体字段的话这里读出 null，而库里其实有值 —— 不报错，只是永远读不到")
                .isEqualTo("TW");
    }

    // ─────────────────────────── ② 测试渠道

    @Test
    @DisplayName("★★★ 测试渠道记得自己发生过什么 —— 恒成功的桩证明不了链路通")
    void testChannelRemembersState() {
        String out = "OT-TC-" + (++seq);

        // 没下过单：通道说「没有这笔」，而不是假装已付
        PayGateway.QueryResult before = testGateway.query(out);
        assertThat(before.ok()).as("查询本身是成功的").isTrue();
        assertThat(before.found()).as("没下过单就该说没有 —— 这是关单能不能安全做的依据").isFalse();

        testGateway.placeOrder(out, 12_345L);
        PayGateway.QueryResult placed = testGateway.query(out);
        assertThat(placed.found()).isTrue();
        assertThat(placed.paid()).as("下了单还没付，就该是未付 —— 恒成功的桩在这里会返回已付").isFalse();
        assertThat(placed.amountMinor()).as("金额按下单时记的返回，不是回声").isEqualTo(12_345L);

        assertThat(testGateway.markPaid(out)).isTrue();
        assertThat(testGateway.query(out).paid()).isTrue();
    }

    @Test
    @DisplayName("★★ 没付过的单不能退 —— 恒成功的桩会让这种错在联调里永不暴露")
    void testChannelRefundChecksOriginal() {
        String out = "OT-TCR-" + (++seq);
        var ctx = new PayGateway.TxContext("SUB1", "TX1", out, 10_000L);

        assertThat(testGateway.refund(ctx, 100L, "R1", "试退").success())
                .as("原单都不存在还能退成功？真通道一定会拒").isFalse();

        testGateway.placeOrder(out, 10_000L);
        assertThat(testGateway.refund(ctx, 100L, "R2", "试退").success())
                .as("单还没付就退成功？").isFalse();

        testGateway.markPaid(out);
        assertThat(testGateway.refund(ctx, 100L, "R3", "试退").success()).isTrue();
        assertThat(testGateway.refund(ctx, 99_999L, "R4", "超额").success())
                .as("退款超过原单金额还能成功？").isFalse();
    }

    // ─────────────────────────── ③ 提现

    /** 造一笔已到账的结算款 */
    private void received(String entityNo, long netMinor) {
        StlBill b = new StlBill();
        b.setSettleNo("STL-WD-" + (++seq));
        b.setSubOrderNo("SUB-WD-" + seq);
        b.setOrderNo("OD-WD-" + seq);
        b.setEntityNo(entityNo);
        b.setNetMinor(netMinor);
        b.setGrossMinor(netMinor);
        b.setStatus(StlBill.SPLIT_CONFIRMED);
        b.setCurrency("CNY");
        DataScopeContext.executeWithoutScope(() -> billMapper.insert(b));
    }

    @Test
    @DisplayName("★★★ 提现走通一遍：有到账的钱才能提，提完可提余额要减掉")
    void withdrawHappyPath() {
        String me = "M-WD-" + (++seq);
        assertThat(withdraws.withdrawableMinor(me))
                .as("一分钱没到账就能提？").isZero();

        received(me, 50_000L);
        assertThat(withdraws.withdrawableMinor(me)).isEqualTo(50_000L);

        var vo = withdraws.apply(me, 20_000L, me);
        assertThat(vo.status()).isEqualTo(StlWithdraw.PENDING);
        assertThat(vo.amount()).isEqualTo(20_000L);

        assertThat(withdraws.withdrawableMinor(me))
                .as("申请了 200 元，可提余额没减 —— 商家连点两次就能把同一笔钱提两遍，"
                        + "而两张单各自看都是合规的")
                .isEqualTo(30_000L);
    }

    @Test
    @DisplayName("★★★ 在途的钱不能提 —— SPLIT 是「已发起等回执」，钱还在路上")
    void inFlightMoneyIsNotWithdrawable() {
        String me = "M-WD-" + (++seq);
        StlBill b = new StlBill();
        b.setSettleNo("STL-WD-" + (++seq));
        b.setSubOrderNo("SUB-WD-" + seq);
        b.setOrderNo("OD-WD-" + seq);
        b.setEntityNo(me);
        b.setNetMinor(99_999L);
        b.setGrossMinor(99_999L);
        b.setStatus(StlBill.SPLIT);   // 已发起，未确认到账
        DataScopeContext.executeWithoutScope(() -> billMapper.insert(b));

        assertThat(withdraws.withdrawableMinor(me))
                .as("把在途的钱算进可提额，商家能提走一笔尚未确认到账的钱")
                .isZero();
    }

    @Test
    @DisplayName("★★ 三道校验各拦一类，错误信息不能混")
    void threeGuards() {
        String me = "M-WD-" + (++seq);
        received(me, 100_000L);

        assertThatThrownBy(() -> withdraws.apply(me, 100L, me))
                .as("低于下限 10 元也能提？渠道手续费比本金还贵").isNotNull();

        withdraws.apply(me, 10_000L, me);

        /*
         * **两条都抛 BAD_REQUEST，靠异常类型分不开。**
         *
         * BizException 的 message 恒为错误码名，人看的那句话在 args 里 ——
         * 所以这里只能断言「确实被拦住了」，
         * 而「商家看到的是哪一句」要到端上才验得了。
         *
         * 记在这里是因为它是个真实的短板：<b>三道校验各有各的话要说，
         * 而异常层把它们压成了同一个码</b>。前端因此没法区别对待。
         * 要改的话是给 ErrorCode 加两个专门的码，不是在这里加断言。
         */
        assertThatThrownBy(() -> withdraws.apply(me, 10_000L, me))
                .as("已有一笔在审还能再提 —— 那商家连点两次就提了两笔")
                .isNotNull();

        // 反向控制量：把在审那笔驳掉之后，应当又能提了
        var pending = withdraws.list(StlWithdraw.PENDING, me, 1, 10).records().getFirst();
        withdraws.decide(pending.withdrawNo(), false, "测试驳回", "OPS");
        assertThat(withdraws.withdrawableMinor(me))
                .as("驳回之后那笔钱应当回到可提额里 —— 不回的话商家的钱就卡死了")
                .isEqualTo(100_000L);
    }
}
