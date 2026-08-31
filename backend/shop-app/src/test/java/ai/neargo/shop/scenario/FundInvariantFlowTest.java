package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.event.AfterCommit;
import ai.neargo.shop.idem.EventIdempotency;
import ai.neargo.shop.settle.entity.StlBill;
import ai.neargo.shop.settle.mapper.SettleMappers;
import ai.neargo.shop.settle.service.FundInvariantService;
import ai.neargo.shop.spi.settle.PointsPort;
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
    private PointsPort pointsPort;
    @Autowired
    private SettleMappers.BillMapper billMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private org.springframework.transaction.support.TransactionTemplate txTemplate;

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
            jdbc.update("DELETE FROM pts_user_ledger WHERE sub_order_no LIKE 'SUB-INV-%'");
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

    @Test
    @DisplayName("跨域写推迟到提交之后 · 业务回滚时对面一个字都没写")
    void crossDomainWriteNeverRunsOnRollback() {
        AtomicInteger ran = new AtomicInteger();

        /*
         * 这一条断言的是**我们做不到某件事**：业务事务回滚时，
         * 推迟的跨域动作根本没有被执行过。
         *
         * 它测的是 AfterCommit 这个机制本身。**「调用点真的用了它」是另一回事**，
         * 由 CrossDomainWriteConventionTest 那道源码闸门看着 ——
         * 有人把 AfterCommit.run(...) 换回直连的话，本条照样绿，那条会红。
         * 两条缺一不可：机制对不代表用上了，用上了不代表机制对。
         */
        try {
            txTemplate.executeWithoutResult(status -> {
                AfterCommit.run("test", ran::incrementAndGet);
                assertThat(ran.get()).as("提交之前不该执行").isZero();
                status.setRollbackOnly();
            });
        } catch (RuntimeException ignored) {
            // 回滚本身可能抛，与本条断言无关
        }
        assertThat(ran.get()).as("业务回滚了，跨域动作一次都不该跑").isZero();

        // 反向对照：正常提交时它必须真的跑 —— 否则上面那句「0 次」毫无意义
        txTemplate.executeWithoutResult(status -> AfterCommit.run("test", ran::incrementAndGet));
        assertThat(ran.get()).as("提交之后必须执行").isEqualTo(1);
    }

    @Test
    @DisplayName("跨域写失败不能把已经提交的业务变成失败")
    void crossDomainFailureDoesNotFailTheCaller() {
        /*
         * 到 afterCommit 这一步业务事务已经提交了。往上抛的话，
         * 支付回调看到的是失败 → 通道重发 → markPaid 幂等直接返回 →
         * **那个动作再也不会被执行，而异常每次都抛**。
         * 症状是「回调一直报错、订单却是好的」，排查方向会指向回调链路。
         *
         * 所以它必须被吞掉并打 error，由不变式巡检补做。
         */
        txTemplate.executeWithoutResult(status ->
                AfterCommit.run("test-boom", () -> {
                    throw new IllegalStateException("对面挂了");
                }));
        // 没有异常传出来就是这条断言的全部内容 —— 走到这里即通过
    }

    @Test
    @DisplayName("I3 · 标记说发过积分而没有流水 —— 把标记清掉，让下一轮能重发")
    void grantedFlagWithoutLedgerIsCleared() {
        long paidAt = System.currentTimeMillis() - 3_600_000L;
        givenPaidOrder(paidAt);
        // 造出那个不一致：标记为真，而 pts_user_ledger 里一条 EARN 都没有
        DataScopeContext.executeWithoutScope(() -> jdbc.update(
                "UPDATE ord_sub_order SET points_granted = 1 WHERE sub_order_no = ?", SUB));

        assertThat(grantedFlagOf(SUB)).as("前置：标记确实被设成了已发").isTrue();
        assertThat(earnLedgerCount(SUB)).as("前置：确实没有发分流水").isZero();

        FundInvariantService.ScanResult r = invariants.scan(paidAt - 60_000L, 500);

        assertThat(r.scannedGranted()).as("对照量：标着已发的那一侧真的扫到了").isPositive();
        assertThat(r.grantedNoLedger()).as("这条应当被算作 I3 违反").isPositive();
        assertThat(r.clearedFlags()).as("修复动作要真的落库").isPositive();
        /*
         * **最要紧的一条**：标记被清回 false。
         * grantOnPay 的幂等原本就是靠这个标记 —— 不清掉的话它永远重发不了，
         * 而用户一分都没拿到，且没有任何地方会再提起这件事。
         */
        assertThat(grantedFlagOf(SUB)).as("标记要被改回未发，下一轮才能重发").isFalse();
    }

    @Test
    @DisplayName("发分自己按流水幂等 —— 不再只靠调用方的标记")
    void grantIsIdempotentByLedger() {
        long now = System.currentTimeMillis();
        givenPaidOrder(now - 60_000L);
        DataScopeContext.executeWithoutScope(() -> jdbc.update(
                "INSERT INTO pts_user_ledger (ledger_no, user_no, biz_type, points,"
                        + " balance_after, sub_order_no, status, tenant_no,"
                        + " created_at, updated_at, version, deleted)"
                        + " VALUES ('PL-INV-1', ?, 'EARN', 12, 0, ?, 'PENDING', 'MAIN',"
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)", USER, SUB));

        assertThat(earnLedgerCount(SUB)).as("前置：已经有一条发分流水").isEqualTo(1);

        /*
         * 再调一次发分。**标记是 false**（模拟「流水写成了、标记没写上」那一刻），
         * 此前这种情况会再发一次 —— 而那个窗口恰恰是把发分推迟到提交之后才出现的。
         */
        var again = pointsPort.grant(USER, ENTITY, List.of(
                new PointsPort.EarnLine("G-INV", "C-INV", 1000L)), SUB);

        /*
         * **判据是返回值，不是流水条数。**
         *
         * 「流水还是 1 条」这句话看着对，其实什么都不证明：这个夹具里的商家
         * 并不存在，grant 走到 pointsDenyReason 也会返回 none() 而不写任何流水 ——
         * 于是把幂等整段关掉，那句断言照样绿（实测过一次，正是这样）。
         *
         * 返回值能区分两者：幂等命中时返回既有流水的分数（12），
         * 没命中时一路走到 none()（0）。而返回值本身也是要紧的 ——
         * 调用方拿它写回子单的 points_fee_minor，那笔钱结算时真的要扣。
         */
        assertThat(again.points())
                .as("幂等命中时要返回既有流水的分数，而不是 none()").isEqualTo(12L);
        assertThat(earnLedgerCount(SUB)).as("同一个子单不能有第二条发分流水").isEqualTo(1);
    }

    @Test
    @DisplayName("退分的幂等在数据里，不在标记上 —— 所以推迟到事务之外是安全的")
    void reverseIsIdempotentByLedgerState() {
        long now = System.currentTimeMillis();
        givenPaidOrder(now - 60_000L);
        // 造一条「已用积分」的流水：退分认的就是它
        DataScopeContext.executeWithoutScope(() -> jdbc.update(
                "INSERT INTO pts_user_ledger (ledger_no, user_no, biz_type, points,"
                        + " balance_after, sub_order_no, status, tenant_no,"
                        + " created_at, updated_at, version, deleted)"
                        + " VALUES ('PL-INV-U', ?, 'USE', -20, 0, ?, 'PENDING', 'MAIN',"
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)", USER, SUB));

        assertThat(refundLedgerCount(SUB)).as("前置：还没有退分流水").isZero();

        pointsPort.reverse(SUB, "测试退分");
        assertThat(refundLedgerCount(SUB))
                .as("第一次要真的退 —— 没有这句，下面「还是 1 条」什么都不证明").isEqualTo(1);

        /*
         * 再退一次。推迟到事务之外意味着**重试会真的发生**（AfterCommit 失败后由巡检补做、
         * 或者同一条链路被重放），所以这一条必须成立。
         *
         * 它靠的不是调用方的任何标记，而是 USE 流水自己的状态：
         * 第一次把它从 PENDING 翻成 REVERSED，第二次就找不到了。
         * <b>这正是 grant 与 reverse 的关键差别</b> —— grant 的幂等原本挂在
         * ord_sub_order.points_granted 上，移出事务就失效，所以那一处要先补流水幂等。
         */
        pointsPort.reverse(SUB, "测试重复退分");
        assertThat(refundLedgerCount(SUB)).as("重复退不能退两次").isEqualTo(1);
    }

    private int refundLedgerCount(String subOrderNo) {
        Integer n = DataScopeContext.executeWithoutScope(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM pts_user_ledger WHERE sub_order_no = ? AND biz_type = 'REFUND'",
                Integer.class, subOrderNo));
        return n == null ? 0 : n;
    }

    private boolean grantedFlagOf(String subOrderNo) {
        Integer v = DataScopeContext.executeWithoutScope(() -> jdbc.queryForObject(
                "SELECT points_granted FROM ord_sub_order WHERE sub_order_no = ?",
                Integer.class, subOrderNo));
        return v != null && v == 1;
    }

    private int earnLedgerCount(String subOrderNo) {
        Integer n = DataScopeContext.executeWithoutScope(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM pts_user_ledger WHERE sub_order_no = ? AND biz_type = 'EARN'",
                Integer.class, subOrderNo));
        return n == null ? 0 : n;
    }

    private List<StlBill> billsOf(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() ->
                billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getSubOrderNo, subOrderNo)));
    }
}
