package ai.neargo.shop.reportbridge;

import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.service.MerchantOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日结的聚合口径与现算必须一致（TDD-B端报表库与日结 §4 的 R4）。
 *
 * <p><b>这是口径分岔的唯一防线。</b>两套实现各自都说得通，分岔的表现是
 * 「总览说 3 单，点进去只有 2 单」—— {@code MerchantOrderServiceImpl.statsByStore}
 * 的注释里已经记着这句话。
 *
 * <p><b>比的是本月</b>：{@code stats()} 只给「今日」与「本月」两个定点数，
 * 而日结算的是 T-1 及以前 —— 两者没有重叠的窗口。
 * 唯一能对上的是把 {@code dailyStoreAggregates(月初, 今天)} 逐日合计起来，
 * 与 {@code stats().monthOrders/monthGmvMinor} 比：同一段时间、同一套过滤。
 *
 * <p><b>⚠️ 这一型断言最容易空转</b>：本地库当月没有任何单时，两边都是 0，
 * 断言照样通过而什么都没验。所以下面第一条是「对照量非零」，
 * 挑不到有单的商户就**让它红着说清楚**，不是悄悄放过。
 */
@SpringBootTest
@ActiveProfiles("test")
class ReportVsLiveConsistencyTest {

    /** 本测试自己造的探针单号前缀，清理时按它删 */
    private static final String PROBE = "SO-RPT-CONSIST-";

    @Autowired
    private MerchantOrderService orders;

    @Autowired
    private SubOrderMapper subOrders;

    private final List<Long> seeded = new ArrayList<>();

    /**
     * 造两单**今天**的成交单。
     *
     * <p>不造的话这个测试在本地库当月无单时只能红着 —— 而一条恒红的测试等于没有测试。
     * 造了必须还原：留下共享种子的下场是「单独跑绿、全量红」，
     * 而报错永远不指向真因。
     */
    @BeforeEach
    void seedTwoOrdersToday() {
        seeded.add(seed(12_300L));
        seeded.add(seed(4_500L));
    }

    @AfterEach
    void cleanUp() {
        DataScopeContext.executeWithoutScope(() -> {
            seeded.forEach(subOrders::deleteById);
            return null;
        });
        seeded.clear();
    }

    private Long seed(long payAmount) {
        OrdSubOrder o = new OrdSubOrder();
        long n = System.nanoTime();
        o.setSubOrderNo(PROBE + n);
        o.setOrderNo("O-RPT-CONSIST-" + n);
        o.setUserNo("U-RPT-CONSIST");
        o.setEntityNo(ENTITY);
        o.setStoreNo("S-RPT-CONSIST");
        // 必须是 TRANSACTED 里的一个 —— 两边都按它过滤，用别的值这个测试就比了个寂寞
        o.setStatus(OrdSubOrder.COMPLETED);
        o.setPayAmount(payAmount);
        o.setDeleted(0);
        DataScopeContext.executeWithoutScope(() -> subOrders.insert(o));
        return o.getId();
    }

    /** 探针商户。用一个真实存在的号，免得 stats() 那侧因为查不到商户而走别的分支 */
    private static final String ENTITY = "M0001";

    @Test
    @DisplayName("★★★ 同一个月：日结逐日合计 == 现算的本月 —— 口径分岔的唯一防线")
    void aggregatesMatchLiveStats() {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);

        List<MerchantOrderService.DailyAgg> aggs = orders.dailyStoreAggregates(monthStart, today);

        String subject = ENTITY;
        List<MerchantOrderService.DailyAgg> mine = aggs.stream()
                .filter(a -> subject.equals(a.entityNo())).toList();

        /*
         * **对照量本身要验非零。** 没有这一条的话，探针单没进去（或过滤条件写反）时
         * 「0 == 0」会让这个测试永远绿，而它一个字节都没比过。
         */
        assertThat(mine.stream().mapToInt(MerchantOrderService.DailyAgg::orders).sum())
                .as("探针单没被聚合到 —— 这个测试正在空转，先查 seed 有没有真的写进去")
                .isGreaterThanOrEqualTo(2);
        int aggOrders = mine.stream().mapToInt(MerchantOrderService.DailyAgg::orders).sum();
        long aggGmv = mine.stream().mapToLong(MerchantOrderService.DailyAgg::gmvMinor).sum();

        var live = orders.stats(subject, null);

        assertThat(aggOrders)
                .as("商户 %s：日结逐日合计 %d 单，现算说本月 %d 单 —— "
                        + "两边的过滤条件或时间轴分岔了（应共用 OrdSubOrder.TRANSACTED 与同一个月初）",
                        subject, aggOrders, live.monthOrders())
                .isEqualTo(live.monthOrders());

        assertThat(aggGmv)
                .as("商户 %s：金额对不上（日结 %d 分 / 现算 %d 分）。"
                        + "两边都应取 pay_amount，差额通常来自某一侧多算了未成交的单", subject, aggGmv, live.monthGmvMinor())
                .isEqualTo(live.monthGmvMinor());
    }
}
