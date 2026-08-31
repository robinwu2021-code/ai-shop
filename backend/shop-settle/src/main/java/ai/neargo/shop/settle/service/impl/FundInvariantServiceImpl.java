package ai.neargo.shop.settle.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.settle.SettleService;
import ai.neargo.shop.settle.entity.PtsUserLedger;
import ai.neargo.shop.settle.entity.StlBill;
import ai.neargo.shop.settle.mapper.SettleMappers;
import ai.neargo.shop.settle.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.settle.service.FundInvariantService;
import ai.neargo.shop.spi.trade.SettleSourcePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class FundInvariantServiceImpl implements FundInvariantService {

    private static final Logger log = LoggerFactory.getLogger(FundInvariantServiceImpl.class);

    private final BillMapper billMapper;
    private final SettleMappers.PointsLedgerMapper ledgerMapper;
    private final SettleSourcePort sourcePort;
    private final SettleService settleService;
    private final ai.neargo.shop.settle.PointsService pointsService;

    public FundInvariantServiceImpl(BillMapper billMapper,
                                    SettleMappers.PointsLedgerMapper ledgerMapper,
                                    SettleSourcePort sourcePort,
                                    SettleService settleService,
                                    ai.neargo.shop.settle.PointsService pointsService) {
        this.billMapper = billMapper;
        this.ledgerMapper = ledgerMapper;
        this.sourcePort = sourcePort;
        this.settleService = settleService;
        this.pointsService = pointsService;
    }

    @Override
    public ScanResult scan(long since, int limit) {
        // ── I1：每个已支付子单必有结算单
        List<SettleSourcePort.PaidSubOrder> paid = sourcePort.paidSubOrdersSince(since, limit);
        Set<String> withBill = paid.isEmpty() ? Set.of() : billSubOrderNos(
                paid.stream().map(SettleSourcePort.PaidSubOrder::subOrderNo).toList());

        List<SettleSourcePort.PaidSubOrder> missing = paid.stream()
                .filter(p -> !withBill.contains(p.subOrderNo()))
                .toList();

        /*
         * **按主单去重再补。** generateForOrder 是按主单做的：
         * 一个主单缺三个子单的结算单时，调三次和调一次结果相同（它幂等），
         * 但日志里会显示「补了 3 张」而实际只补了一轮 —— 那个数会误导下一个人。
         */
        Set<String> orderNos = new LinkedHashSet<>(
                missing.stream().map(SettleSourcePort.PaidSubOrder::orderNo).toList());
        int repaired = 0;
        for (String orderNo : orderNos) {
            /*
             * 逐单独立 try：一个主单补不出来（数据本身有问题）不能把整轮带走。
             * 补生成是**幂等且只增不减**的动作，所以自动执行是安全的 ——
             * 这一点与下面的 I2 刚好相反。
             */
            try {
                repaired += settleService.generateForOrder(orderNo);
            } catch (RuntimeException e) {
                log.warn("[fund-invariant] I1 补生成失败 orderNo={}：{}", orderNo, e.getMessage());
            }
        }

        long oldestMissingAt = missing.stream()
                .mapToLong(SettleSourcePort.PaidSubOrder::paidAt)
                .filter(x -> x > 0)
                .min().orElse(0L);

        // ── I2：每张结算单必有对应的已支付子单
        List<StlBill> bills = DataScopeContext.executeWithoutScope(() ->
                billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                        .ge(StlBill::getAccruedAt, since)
                        .orderByAsc(StlBill::getAccruedAt)
                        .last("limit " + Math.max(1, limit))));
        List<String> orphan = bills.isEmpty() ? List.of()
                : sourcePort.notPaidAmong(bills.stream().map(StlBill::getSubOrderNo).toList());

        if (!orphan.isEmpty()) {
            /*
             * **只告警，绝不自动删。**
             *
             * 删账是不可逆动作，而这一类的成因不止一种：子单被平台强制取消、
             * 数据修复留下的残行、也可能是**这个巡检自己的查询窗口算错了**。
             * 前两种要人判断，第三种要改的是巡检不是数据 ——
             * 而自动删会把三种一起变成「账少了几行」，事后谁也说不清是哪一种。
             */
            log.error("[fund-invariant] **I2 违反 {} 条**：结算单对不上已支付子单，"
                            + "需人工判断（不自动处理）。样本：{}",
                    orphan.size(), orphan.subList(0, Math.min(5, orphan.size())));
        }

        // ── I3：标着「已发过积分」的子单必有发分流水
        List<String> granted = sourcePort.pointsGrantedSince(since, limit);
        // 先把「有流水的」一次查回来 —— 写在 filter 里就是每个元素查一次库
        Set<String> earned = granted.isEmpty() ? Set.of() : earnedSubOrderNos(granted);
        List<String> noLedger = granted.stream().filter(no -> !earned.contains(no)).toList();

        /*
         * **把标记改回未发，让下一轮重发。**
         *
         * 这一条能自动修，是因为方向安全：标记为真而没有流水，说明用户一分没拿到，
         * 而 grantOnPay 的幂等**就是靠这个标记** —— 不清掉的话它永远重发不了。
         * 清掉之后最坏是多发一次，而那由 canEarn 与流水唯一性挡着。
         *
         * <p><b>反方向（有流水而标记为假）刻意不动</b>：那说明发分那边多跑了一次，
         * 补上标记等于把多发的一次盖掉，而账上那笔分还在用户手里 —— 要人看。
         */
        int cleared = noLedger.isEmpty() ? 0 : sourcePort.clearPointsGranted(noLedger);
        if (!noLedger.isEmpty()) {
            log.error("[fund-invariant] **I3 违反 {} 条**：标记说发过积分而没有流水，"
                            + "已把标记改回未发（{} 行），下一轮会重发。样本：{}",
                    noLedger.size(), cleared, noLedger.subList(0, Math.min(5, noLedger.size())));
        }

        return new ScanResult(paid.size(), missing.size(), repaired,
                bills.size(), orphan.size(), oldestMissingAt,
                granted.size(), noLedger.size(), cleared);
    }

    @Override
    public ReleaseResult releaseDeadHolds(long olderThan, int limit) {
        java.time.LocalDateTime cutoff = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(olderThan), java.time.ZoneId.systemDefault());
        List<PtsUserLedger> holds = DataScopeContext.executeWithoutScope(() ->
                ledgerMapper.selectList(Wrappers.<PtsUserLedger>lambdaQuery()
                        .eq(PtsUserLedger::getBizType, PtsUserLedger.USE)
                        .eq(PtsUserLedger::getStatus, PtsUserLedger.PENDING)
                        .lt(PtsUserLedger::getCreatedAt, cutoff)
                        .orderByAsc(PtsUserLedger::getCreatedAt)
                        .last("limit " + Math.max(1, limit))));
        if (holds.isEmpty()) {
            return new ReleaseResult(0, 0, 0);
        }
        List<String> dead = sourcePort.subOrdersNotAlive(
                holds.stream().map(PtsUserLedger::getSubOrderNo).distinct().toList());

        int released = 0;
        for (String subOrderNo : dead) {
            /*
             * 逐条独立 try：一条退不掉不能把整轮带走。
             * {@code reverse} 幂等且只认 PENDING —— 与正常取消链路并发跑到同一条上，
             * 先到的那次生效，后到的静默返回。
             */
            try {
                pointsService.reverse(subOrderNo, "订单未成交，释放预占的积分");
                released++;
            } catch (RuntimeException e) {
                log.warn("[fund-invariant] I6 释放失败 subOrderNo={}：{}", subOrderNo, e.getMessage());
            }
        }
        if (!dead.isEmpty()) {
            log.warn("[fund-invariant] **I6 释放 {} 条预占积分**（扫 {} 条）—— "
                            + "这些单的积分已经扣走而订单不可能成交；持续不为零要查下单链路",
                    released, holds.size());
        }
        return new ReleaseResult(holds.size(), dead.size(), released);
    }

    /** 这批子单里**已经有发分流水**的。一次查回来，不逐个 exists */
    private Set<String> earnedSubOrderNos(List<String> subOrderNos) {
        List<PtsUserLedger> rows = DataScopeContext.executeWithoutScope(() ->
                ledgerMapper.selectList(Wrappers.<PtsUserLedger>lambdaQuery()
                        .eq(PtsUserLedger::getBizType, PtsUserLedger.EARN)
                        .in(PtsUserLedger::getSubOrderNo, subOrderNos)));
        return rows.stream().map(PtsUserLedger::getSubOrderNo)
                .collect(java.util.stream.Collectors.toSet());
    }

    /** 这批子单里**已经有结算单**的。一次查回来，不逐个 exists —— 那是 N 次往返 */
    private Set<String> billSubOrderNos(List<String> subOrderNos) {
        List<StlBill> rows = DataScopeContext.executeWithoutScope(() ->
                billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                        .in(StlBill::getSubOrderNo, subOrderNos)));
        return rows.stream().map(StlBill::getSubOrderNo)
                .collect(java.util.stream.Collectors.toSet());
    }
}
