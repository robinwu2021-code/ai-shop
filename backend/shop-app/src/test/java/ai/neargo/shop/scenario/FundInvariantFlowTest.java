package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.idem.EventIdempotency;
import ai.neargo.shop.settle.entity.StlBill;
import ai.neargo.shop.settle.mapper.SettleMappers;
import ai.neargo.shop.settle.service.FundInvariantService;
import ai.neargo.shop.spi.trade.SettleSourcePort;
import ai.neargo.shop.trade.service.AfterSaleService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 A 的三件事：不变式巡检（I1/I2）· 卡住的退款能被扫出来（I5）· 事件级幂等。
 *
 * <h2>每条都带前置断言</h2>
 * 「注入一个违反 → 跑巡检 → 断言修好了」这个形状里，<b>中间那句前置断言不能省</b>：
 * 没有它，如果注入根本没生效（数据没造进去、查询条件不匹配），测试照样绿 ——
 * 而它证明的是「一切正常」，不是「巡检能发现问题」。
 *
 * <p>同理，每条扫描都要断言<b>对照量不为零</b>：
 * 「违反 0 条」与「一行都没扫到」在结果上一模一样，而后者才是最该红的那种。
 */
@SpringBootTest
@ActiveProfiles("test")
class FundInvariantFlowTest {

    private static final String ENTITY = "M-INV-T1";
    private static final String ORDER = "SO-INV-1";
    private static final String SUB = "SUB-INV-1";
    private static final String USER = "U-INV";

    @Autowired
    private FundInvariantService invariants;
    @Autowired
    private SettleSourcePort sourcePort;
    @Autowired
    private AfterSaleService afterSaleService;
    @Autowired
    private EventIdempotency events;
    @Autowired
    private SettleMappers.BillMapper billMapper;
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * ⚠️ <b>造的数据必须删干净。</b>订单、结算单、售后都是全局表 ——
     * 留一行在库里，此后每一轮巡检都会捞到它，
     * 而别的用例的失败信息里不会有一个字提到这个类。
     */
    @AfterEach
    void cleanUp() {
        DataScopeContext.executeWithoutScope(() -> {
            jdbc.update("DELETE FROM stl_bill WHERE sub_order_no LIKE 'SUB-INV-%'");
            jdbc.update("DELETE FROM ord_sub_order WHERE sub_order_no LIKE 'SUB-INV-%'");
            jdbc.update("DELETE FROM ord_order WHERE order_no LIKE 'SO-INV-%'");
            jdbc.update("DELETE FROM ord_after_sale WHERE sub_order_no LIKE 'SUB-INV-%'");
            jdbc.update("DELETE FROM sys_event_consumed WHERE event_no LIKE 'EV-INV-%'");
            return null;
        });
    }

    /** 造一个已支付的主单 + 子单。**不造结算单** —— 那正是 I1 要发现的缺口 */
    private void givenPaidOrder(long paidAt) {
        DataScopeContext.executeWithoutScope(() -> {
            jdbc.update("INSERT INTO ord_order (order_no, user_no, status, pay_amount, paid_at,"
                    + " tenant_no, created_at, updated_at, version, deleted)"
                    + " VALUES (?, ?, 'PAID', 1000, ?, 'MAIN',"
                    + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)", ORDER, USER, paidAt);
            jdbc.update("INSERT INTO ord_sub_order (sub_order_no, order_no, user_no, entity_no,"
                    + " status, pay_amount, tenant_no, created_at, updated_at, version, deleted)"
                    + " VALUES (?, ?, ?, ?, 'WAIT_FULFILL', 1000, 'MAIN',"
                    + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)", SUB, ORDER, USER, ENTITY);
            return null;
        });
    }

    @Test
    @DisplayName("I1 · 已支付但没有结算单 —— 巡检要发现它")
    void missingBillIsFound() {
        long paidAt = System.currentTimeMillis() - 3_600_000L;
        givenPaidOrder(paidAt);

        // 前置断言：确实没有结算单。少了这句，下面「发现了 1 条」也可能是别的单带来的
        assertThat(billsOf(SUB)).as("造数据时不该有结算单").isEmpty();

        FundInvariantService.ScanResult r = invariants.scan(paidAt - 60_000L, 500);

        // 对照量：真的扫到东西了 —— 否则「发现 0 条违反」毫无意义
        assertThat(r.scannedAnything()).as("一行都没扫到，这个断言就不说明任何问题").isTrue();
        assertThat(r.scannedPaid()).isPositive();
        assertThat(r.missingBill()).as("这个子单已支付却没有结算单，应当被算作违反").isPositive();
    }

    @Test
    @DisplayName("I2 · 结算单对不上已支付子单 —— 只报不动，账一行都不能少")
    void orphanBillIsReportedButNotDeleted() {
        long now = System.currentTimeMillis();
        // 只造结算单，**不造子单** —— 这就是「账挂在一个不存在的单上」
        StlBill orphan = new StlBill();
        orphan.setSettleNo("STL-INV-ORPHAN");
        orphan.setSubOrderNo(SUB);
        orphan.setOrderNo(ORDER);
        orphan.setEntityNo(ENTITY);
        orphan.setGrossMinor(1000L);
        orphan.setNetMinor(1000L);
        orphan.setStatus(StlBill.PENDING);
        orphan.setAccruedAt(now - 60_000L);
        DataScopeContext.executeWithoutScope(() -> billMapper.insert(orphan));

        assertThat(billsOf(SUB)).as("前置：孤儿结算单已经造进去了").hasSize(1);

        FundInvariantService.ScanResult r = invariants.scan(now - 3_600_000L, 500);

        assertThat(r.scannedBills()).as("对照量：结算单那一侧真的扫到了").isPositive();
        assertThat(r.orphanBill()).as("这张结算单对不上任何已支付子单").isPositive();
        /*
         * **最重要的一条断言：它还在。**
         * I2 的处置是「只告警不自动删」—— 删账不可逆，而成因不止一种
         * （含「巡检自己把时间窗算错了」）。哪天有人顺手加上自动删，这条会红。
         */
        assertThat(billsOf(SUB)).as("I2 绝不能自动删账").hasSize(1);
    }

    @Test
    @DisplayName("I5 · 卡在 REFUNDING 的售后单要被扫出来，且时间窗不能把新单也捞走")
    void stuckRefundIsFound() {
        DataScopeContext.executeWithoutScope(() -> {
            jdbc.update("INSERT INTO ord_after_sale (after_sale_no, order_no, sub_order_no,"
                    + " user_no, entity_no, type, reason, status, refund_minor,"
                    + " tenant_no, created_at, updated_at, version, deleted)"
                    + " VALUES ('AS-INV-1', ?, ?, ?, ?, 'REFUND_ONLY', '测试', 'REFUNDING', 100,"
                    + " 'MAIN', CURRENT_TIMESTAMP, DATEADD('MINUTE', -60, CURRENT_TIMESTAMP), 0, 0)",
                    ORDER, SUB, USER, ENTITY);
            return null;
        });

        long now = System.currentTimeMillis();
        List<String> stuck = afterSaleService.stuckRefundNos(now - 30 * 60_000L, 100);
        assertThat(stuck).as("卡了 60 分钟的单，30 分钟的门槛应当扫得到").contains("AS-INV-1");

        /*
         * **反向对照**：把门槛提到 2 小时，这条 60 分钟的就不该被捞走。
         * 没有这一条的话，一个「无条件返回全部 REFUNDING」的实现也会让上面那句通过 ——
         * 而那会让刚进 REFUNDING、正在被同步流程处理的单被重试插一脚。
         */
        List<String> tooNew = afterSaleService.stuckRefundNos(now - 120 * 60_000L, 100);
        assertThat(tooNew).as("时间窗要真的起作用，不能无条件全捞").doesNotContain("AS-INV-1");
    }

    @Test
    @DisplayName("事件级幂等 · 同一个事件投两次只生效一次")
    void eventOnlyConsumedOnce() {
        AtomicInteger runs = new AtomicInteger();

        boolean first = events.once("EV-INV-1", "test-handler", runs::incrementAndGet);
        boolean second = events.once("EV-INV-1", "test-handler", runs::incrementAndGet);

        assertThat(first).as("第一次应当真的执行").isTrue();
        assertThat(second).as("第二次应当被认出来并跳过").isFalse();
        assertThat(runs.get()).as("动作只能跑一遍").isEqualTo(1);

        /*
         * **同一个事件、不同消费者，各处理一次是正常的。**
         * 唯一键少了 handler 那一列的话，第二个消费者会被当成重复投递而静默跳过 ——
         * 表现是「发积分做了，生成结算单没做」，而两边都不报错。
         */
        boolean otherHandler = events.once("EV-INV-1", "another-handler", runs::incrementAndGet);
        assertThat(otherHandler).as("换一个消费者应当照常执行").isTrue();
        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("端口 · paidSubOrdersSince 与 notPaidAmong 的方向不能反")
    void portDirections() {
        long paidAt = System.currentTimeMillis() - 3_600_000L;
        givenPaidOrder(paidAt);

        List<SettleSourcePort.PaidSubOrder> paid =
                sourcePort.paidSubOrdersSince(paidAt - 60_000L, 500);
        assertThat(paid).extracting(SettleSourcePort.PaidSubOrder::subOrderNo).contains(SUB);

        /*
         * notPaidAmong 返回的是**异常的那些**（差集），不是正常的那些。
         * 写反的话，报出来的会是完全相反的一批单 —— 而两种结果都「有几条」，
         * 看日志分不出来。
         */
        assertThat(sourcePort.notPaidAmong(List.of(SUB)))
                .as("这个子单是已支付的，不该出现在「对不上」的结果里").isEmpty();
        assertThat(sourcePort.notPaidAmong(List.of("SUB-INV-NOT-EXIST")))
                .as("库里根本没有的子单，比状态不对更严重，必须报出来")
                .contains("SUB-INV-NOT-EXIST");
    }

    private List<StlBill> billsOf(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() ->
                billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getSubOrderNo, subOrderNo)));
    }
}
