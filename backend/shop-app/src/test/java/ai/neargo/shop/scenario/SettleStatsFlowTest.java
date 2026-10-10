package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.SettleStatsService;
import ai.neargo.shop.pay.SettleStatsService.Dim;
import ai.neargo.shop.pay.SettleStatsService.StatRow;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结算口径的多维统计（TDD-供应商结算与双轨资金 §2.1 · AC-13~17）。
 *
 * <p><b>为什么是场景测试而不是 Mockito</b>：这个服务的全部价值在那条
 * {@code GROUP BY} —— 区间取的是哪一列、空值归不归组、SUM 回来的类型。
 * 用替身把 {@code selectMaps} 换掉，剩下的只是一次 map 转换，
 * 而那不是会出错的地方。
 *
 * <p><b>刻意只插结算单，不插门店与主体的主数据</b>：如果实现偷偷 join 回去拿名字，
 * 这些行会因为 join 不上而消失，下面的合计断言立刻红 ——
 * 这是「按快照聚合」那条规则的可证伪判据，不是靠读代码保证的。
 */
@SpringBootTest
/*
 * **只要 test，不要 ops。**
 *
 * 这个类用的全是没有 @Profile 限制的 bean（service / mapper / cipher），
 * 不碰任何 ops controller —— 加上 ops 只会多一个 context key，
 * 而多一个 context 在这套测试里是有代价的：H2 是
 * `jdbc:h2:mem:shop;DB_CLOSE_DELAY=-1`，库在 context 关掉之后还活着，
 * 于是第二个 context 起来时 sql-init 会把 schema-test.sql 的种子再插一遍，
 * 撞 sys_industry 的主键 —— 症状是「Failed to load ApplicationContext」，
 * 与这个类本身毫无关系，而且**单独跑永远复现不了**。
 */
@ActiveProfiles("test")
class SettleStatsFlowTest {

    /** 本用例专用前缀。**插进去的行必须在 AfterEach 删掉** —— 留下的话，
     *  别的用例按主体/门店聚合时会多出几行，而报错永远不指向这里 */
    private static final String E1 = "E-STAT-1";
    private static final String E2 = "E-STAT-2";
    private static final long T0 = 1_790_000_000_000L;
    /** 区间外那一笔，比 T0 早一天 */
    private static final long OUTSIDE = T0 - 86_400_000L;

    @Autowired
    private SettleStatsService stats;
    @Autowired
    private BillMapper bills;

    /**
     * 每次 seed 用一串唯一后缀。
     *
     * <p><b>不能固定单号</b>：AfterEach 的 delete 是 MyBatis-Plus 的**逻辑删除**
     * （只置 deleted=1），行还在库里占着 {@code uk_settle_no}，
     * 第二个用例再插同一个号就撞唯一索引 —— 而报出来的是
     * 「ApplicationContext 加载失败」，与真因隔着三层。
     * 逻辑删除对统计本身无害：查询不会捞到 deleted=1 的行。
     */
    private String run;

    @BeforeEach
    void seed() {
        run = Long.toString(System.nanoTime());
        // 同一主体 E1 下：S1 两笔、S2 一笔、**一笔没有门店**（存量的主体级流水）
        insert("SB-" + run + "-1", E1, "S-STAT-1", 10_000, 1_000, 500, 200, 8_300, T0);
        insert("SB-" + run + "-2", E1, "S-STAT-1", 20_000, 2_000, 0, 400, 17_600, T0);
        insert("SB-" + run + "-3", E1, "S-STAT-2", 30_000, 3_000, 0, 600, 26_400, T0);
        insert("SB-" + run + "-4", E1, null, 40_000, 4_000, 0, 800, 35_200, T0);
        // 另一个主体，且**落在区间之外** —— 用来钉「区间取的是 accrued_at」
        insert("SB-" + run + "-5", E2, "S-STAT-3", 50_000, 5_000, 0, 1_000, 44_000, OUTSIDE);
    }

    @AfterEach
    void cleanup() {
        bills.delete(Wrappers.<StlBill>lambdaQuery().in(StlBill::getEntityNo, E1, E2));
    }

    @Test
    @DisplayName("门店维度：空门店单独成一行，不被丢掉")
    void unassignedStoreKeepsItsOwnRow() {
        Map<String, StatRow> byStore = index(stats.stats(Dim.STORE, T0, T0, null));

        assertThat(byStore).containsKeys("S-STAT-1", "S-STAT-2", SettleStatsService.UNASSIGNED);
        // S1 两笔合并
        assertThat(byStore.get("S-STAT-1").netMinor()).isEqualTo(8_300 + 17_600);
        assertThat(byStore.get("S-STAT-1").billCount()).isEqualTo(2);
        // 没有门店的那一笔照样有自己的一行
        assertThat(byStore.get(SettleStatsService.UNASSIGNED).netMinor()).isEqualTo(35_200);
    }

    @Test
    @DisplayName("★ 门店合计 == 主体合计 —— 丢掉空门店那一组就会不等")
    void storeTotalEqualsEntityTotal() {
        long byStore = stats.stats(Dim.STORE, T0, T0, null).stream()
                .mapToLong(StatRow::netMinor).sum();
        long byEntity = stats.stats(Dim.ENTITY, T0, T0, null).stream()
                .mapToLong(StatRow::netMinor).sum();

        assertThat(byStore).isEqualTo(byEntity);
        // 并且等于四笔之和 —— 只断言两者相等的话，「两边都少算同一笔」会一起漏过去
        assertThat(byStore).isEqualTo(8_300 + 17_600 + 26_400 + 35_200);
    }

    @Test
    @DisplayName("区间按 accrued_at（成交日）取，区间外的不计入")
    void rangeFiltersByAccruedAt() {
        Map<String, StatRow> in = index(stats.stats(Dim.ENTITY, T0, T0, null));
        assertThat(in).containsKey(E1).doesNotContainKey(E2);

        // 把区间放宽到包含那一天，E2 就该出现 —— 否则「过滤掉了」可能只是因为数据没插进去
        Map<String, StatRow> wide = index(stats.stats(Dim.ENTITY, OUTSIDE, T0, null));
        assertThat(wide).containsKeys(E1, E2);
        assertThat(wide.get(E2).netMinor()).isEqualTo(44_000);
    }

    @Test
    @DisplayName("各项金额分别汇总，不是只把净额加一遍")
    void sumsEveryAmountColumn() {
        StatRow r = index(stats.stats(Dim.ENTITY, T0, T0, null)).get(E1);

        assertThat(r.grossMinor()).isEqualTo(10_000 + 20_000 + 30_000 + 40_000);
        assertThat(r.commissionMinor()).isEqualTo(1_000 + 2_000 + 3_000 + 4_000);
        assertThat(r.serviceFeeMinor()).isEqualTo(500);
        assertThat(r.channelFeeMinor()).isEqualTo(200 + 400 + 600 + 800);
        assertThat(r.billCount()).isEqualTo(4);
    }

    private static Map<String, StatRow> index(List<StatRow> rows) {
        return rows.stream().collect(Collectors.toMap(StatRow::dimKey, Function.identity()));
    }

    private void insert(String no, String entityNo, String storeNo, long gross,
                        long commission, long serviceFee, long channelFee,
                        long net, long accruedAt) {
        StlBill b = new StlBill();
        b.setSettleNo(no);
        b.setSubOrderNo("SUB-" + no);
        b.setOrderNo("SO-" + no);
        b.setEntityNo(entityNo);
        b.setStoreNo(storeNo);
        b.setGrossMinor(gross);
        b.setCommissionMinor(commission);
        b.setServiceFeeMinor(serviceFee);
        b.setChannelFeeMinor(channelFee);
        b.setNetMinor(net);
        b.setAccruedAt(accruedAt);
        b.setStatus(StlBill.PENDING_RECON);
        b.setBusinessMode(MerchantQueryPort.MODE_SELF_OPERATED);
        b.setTenantNo("MAIN");
        b.setCreatedAt(LocalDateTime.now());
        b.setUpdatedAt(LocalDateTime.now());
        bills.insert(b);
    }
}
