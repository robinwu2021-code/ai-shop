package ai.neargo.shop.settle.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.settle.entity.StlPayment;
import ai.neargo.shop.settle.entity.StlReconDiff;
import ai.neargo.shop.settle.mapper.SettleMappers;
import ai.neargo.shop.settle.service.ReconService;
import ai.neargo.shop.settle.service.recon.ReconAxis;
import ai.neargo.shop.spi.pay.PayQueryPort;
import ai.neargo.shop.spi.trade.OrderRepairPort;
import ai.neargo.common.data.scope.DataScopeContext;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * {@link ReconService} 实现：平台侧自查。
 *
 * <p>渠道账单比对还没有产出方（{@code PayGateway} 没有账单下载），
 * 所以 {@link #coverage()} 恒定报「未接入」——<b>那句话要显示给运营</b>。
 */
@Service
public class ReconServiceImpl implements ReconService {

    private static final Logger log = LoggerFactory.getLogger(ReconServiceImpl.class);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SettleMappers.PaymentMapper paymentMapper;
    private final SettleMappers.ReconDiffMapper diffMapper;
    private final PayQueryPort payQueryPort;
    /**
     * 所有对账轴。
     *
     * ⚠️ **用 ObjectProvider 而不是直接注入 `List<ReconAxis>`** ——
     * `PaymentReconAxis` 反过来依赖本类（它是纯委托），直接注入会形成构造器环，
     * 而那个环报出来的是 `Requested bean is currently in creation`，
     * 与「对账」两个字毫无关系。延迟取解开它，代价只是一次 getBeanProvider。
     *
     * 新增一条轴只要加一个 @Component，不用改这里。
     */
    private final org.springframework.beans.factory.ObjectProvider<ReconAxis> axes;
    private final OrderRepairPort orderRepairPort;

    /**
     * 多久算「超时未终态」。
     *
     * <p>比关单时限（15 分钟）留一点余量：正好卡在 15 分的单可能正在回调路上，
     * 那种单查一次纯属浪费，还会与关单任务抢同一行。
     */
    private final int staleMinutes;

    public ReconServiceImpl(SettleMappers.PaymentMapper paymentMapper,
                            SettleMappers.ReconDiffMapper diffMapper,
                            PayQueryPort payQueryPort,
                            OrderRepairPort orderRepairPort,
                            org.springframework.beans.factory.ObjectProvider<ReconAxis> axes,
                            @Value("${shop.recon.stale-minutes:20}") int staleMinutes) {
        this.paymentMapper = paymentMapper;
        this.diffMapper = diffMapper;
        this.payQueryPort = payQueryPort;
        this.orderRepairPort = orderRepairPort;
        this.axes = axes;
        this.staleMinutes = staleMinutes;
    }

    /*
     * **整轮不加事务**，每一笔各自成事务（markPaid / closeUnpaid 内部各有）。
     *
     * 加了的话，一笔补回失败就把整批回滚 —— 已经补好的几十笔跟着退回去，
     * 而下一轮会再补一次。批量任务的第一原则是「一颗坏苹果不能毁掉整筐」。
     */
    /**
     * 跑所有轴。<b>逐条 try/catch</b> —— 一条轴炸了不该让另外三条也失去发现能力。
     *
     * <p>失败的那条把原因带回去而不是静默跳过：一条「今天没扫」的轴
     * 与一条「今天零差异」的轴在页面上长得一模一样，而它们的含义完全相反。
     */
    @Override
    public List<AxisReport> scanAllAxes(long now) {
        List<AxisReport> out = new java.util.ArrayList<>();
        for (ReconAxis axis : axes) {   // ObjectProvider 可迭代
            try {
                out.add(new AxisReport(axis.code(), axis.scan(now), axis.coverage(), null));
            } catch (Exception e) {
                log.error("对账轴跑失败：axis={}", axis.code(), e);
                out.add(new AxisReport(axis.code(), null, axis.coverage(), e.toString()));
            }
        }
        return out;
    }

    @Override
    public ScanResult scan(long now) {
        long cutoff = now - staleMinutes * 60_000L;
        List<StlPayment> stale = DataScopeContext.executeWithoutScope(() ->
                paymentMapper.selectList(Wrappers.<StlPayment>lambdaQuery()
                        .eq(StlPayment::getDirection, StlPayment.PAY)
                        .in(StlPayment::getStatus, StlPayment.INIT, StlPayment.PENDING)
                        .le(StlPayment::getCreatedAt,
                                java.time.LocalDateTime.ofInstant(
                                        Instant.ofEpochMilli(cutoff), ZoneId.systemDefault()))));

        int repaired = 0;
        int closed = 0;
        int deferred = 0;
        String day = DAY.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()));

        for (StlPayment p : stale) {
            PayQueryPort.Result r = payQueryPort.query(p.getPayChannel(), p.getOutTradeNo());
            if (!r.ok()) {
                /*
                 * 查询失败：**什么都不做**。
                 *
                 * 当成「通道没有这笔」去关单的话，一笔已付的单会被关掉 ——
                 * 用户的钱在通道那边，而我方订单已关闭，只能退款并道歉。
                 * 留到下一轮再查是唯一安全的选择。
                 */
                deferred++;
                continue;
            }
            if (r.paid()) {
                /*
                 * 通道说已付 —— 走**原本的支付成功链路**，不在这里补状态。
                 * 自己写一段「把 status 改成 SUCCESS」会漏掉发券、积分、通知、
                 * 结算单生成里的某一个，而漏掉哪个要等用户来问才知道。
                 */
                String note;
                try {
                    orderRepairPort.markPaid(p.getOrderNo(), p.getPayChannel(), r.tradeNo());
                    repaired++;
                    note = "自查发现通道已支付，已补回支付成功链路（通道单号 " + r.tradeNo() + "）";
                } catch (RuntimeException e) {
                    /*
                     * 补回失败**不能让整轮扫描炸掉**：一笔补不回来，后面几百笔就都不查了。
                     *
                     * 而且这种单恰恰**最需要被记下来** —— 通道收了钱，而我方连订单都推不动
                     * （订单不存在、或已经被关掉）。这是要人去处理的，不是重试能解决的。
                     */
                    deferred++;
                    note = "通道已支付但补回失败（" + e.getMessage() + "）—— 通道单号 "
                            + r.tradeNo() + "，需人工核对订单 " + p.getOrderNo();
                    log.warn("[recon] 补回失败 payment={} order={}：{}",
                            p.getPaymentNo(), p.getOrderNo(), e.toString());
                }
                recordDiff(day, p, r, StlReconDiff.PLATFORM_ONLY, note);
                if (r.amountMinor() > 0 && p.getAmountMinor() != null
                        && r.amountMinor() != p.getAmountMinor()) {
                    // 金额不符要单独记一条：补回支付不代表账对上了
                    recordDiff(day, p, r, StlReconDiff.AMOUNT_DIFF,
                            "通道 " + r.amountMinor() + " 与我方 " + p.getAmountMinor() + " 不符");
                }
            } else if (!r.found()) {
                // 通道根本没有这笔 = 我方发起失败，可以安全关单
                orderRepairPort.closeUnpaid(p.getOrderNo());
                closed++;
            } else {
                // 通道有这笔但没付：正常的用户放弃，交给关单任务，不算差异
                deferred++;
            }
        }
        log.info("[recon] 自查 {} 笔：补回 {} · 关单 {} · 留待下轮 {}",
                stale.size(), repaired, closed, deferred);
        return new ScanResult(stale.size(), repaired, closed, deferred);
    }

    /**
     * 落一条差异。
     *
     * <p>同一笔流水在同一账期只留一条（唯一键 {@code uk_recon_diff_payment}）——
     * 自查每小时跑一轮，不去重的话一笔掉单会在列表里堆成几十条，
     * 而运营要逐条点开才知道是同一笔。
     */
    private void recordDiff(String day, StlPayment p, PayQueryPort.Result r,
                            String type, String note) {
        boolean exists = DataScopeContext.executeWithoutScope(() -> diffMapper.exists(
                Wrappers.<StlReconDiff>lambdaQuery()
                        .eq(StlReconDiff::getBillDate, day)
                        .eq(StlReconDiff::getPayChannel, p.getPayChannel())
                        .eq(StlReconDiff::getPaymentNo, p.getPaymentNo())
                        .eq(StlReconDiff::getDiffType, type)));
        if (exists) {
            return;
        }
        StlReconDiff d = new StlReconDiff();
        d.setDiffNo(BizKey.next(BizKey.RECON_DIFF));
        d.setBillDate(day);
        d.setPayChannel(p.getPayChannel());
        d.setDiffType(type);
        d.setSource(StlReconDiff.SELF_CHECK);
        d.setPaymentNo(p.getPaymentNo());
        d.setOrderNo(p.getOrderNo());
        d.setChannelTxnNo(r.tradeNo());
        d.setChannelAmountMinor(r.amountMinor());
        d.setPlatformAmountMinor(p.getAmountMinor() == null ? 0L : p.getAmountMinor());
        d.setStatus(StlReconDiff.PENDING);
        d.setResolution(note);
        DataScopeContext.executeWithoutScope(() -> diffMapper.insert(d));
    }

    @Override
    public List<ReconDiffVO> diffs(String status) {
        return DataScopeContext.executeWithoutScope(() -> diffMapper.selectList(
                        Wrappers.<StlReconDiff>lambdaQuery()
                                .eq(status != null && !status.isBlank(), StlReconDiff::getStatus, status)
                                .orderByDesc(StlReconDiff::getId)))
                .stream().map(this::toVO).toList();
    }

    @Override
    @Transactional("settleTxManager")
    public ReconDiffVO decide(String diffNo, boolean ignore, String resolution, String operatorNo) {
        if (resolution == null || resolution.isBlank()) {
            // 没有结论的「已处理」等于没处理：下个月对账时没人知道当初为什么放过它
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        StlReconDiff d = DataScopeContext.executeWithoutScope(() -> diffMapper.selectOne(
                Wrappers.<StlReconDiff>lambdaQuery()
                        .eq(StlReconDiff::getDiffNo, diffNo).last("limit 1")));
        if (d == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        // 裁完就是终态：再裁一次意味着同一条差异有两个结论
        if (!StlReconDiff.PENDING.equals(d.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        d.setStatus(ignore ? StlReconDiff.IGNORED : StlReconDiff.RESOLVED);
        d.setResolution(resolution.trim());
        d.setResolvedAt(System.currentTimeMillis());
        d.setResolvedBy(operatorNo);
        DataScopeContext.executeWithoutScope(() -> diffMapper.updateById(d));
        return toVO(d);
    }

    @Override
    public Coverage coverage() {
        /*
         * 一期恒定 false。等 PayGateway 有了账单下载、且有 CHANNEL_BILL 来源的差异行，
         * 这里才改成 true。
         *
         * **不要因为「列表里已经有数据」就把它改成 true** —— 自查产出的数据
         * 与渠道侧差异是两回事，前者再多也不代表后者被覆盖到了。
         */
        return new Coverage(false,
                "渠道账单未接入：本列表只覆盖平台侧可自查的部分（超时未终态的收款）。"
                        + "「渠道扣了钱而平台没有记录」这一类差异现在看不见，需接入对账单后才会出现。");
    }

    private ReconDiffVO toVO(StlReconDiff d) {
        return new ReconDiffVO(d.getDiffNo(), d.getBillDate(), d.getPayChannel(), d.getDiffType(),
                d.getSource(), d.getPaymentNo(), d.getOrderNo(), d.getChannelTxnNo(),
                d.getChannelAmountMinor() == null ? 0L : d.getChannelAmountMinor(),
                d.getPlatformAmountMinor() == null ? 0L : d.getPlatformAmountMinor(),
                d.getStatus(), d.getResolution(), d.getRecoveredOrderNo(),
                d.getCreatedAt() == null ? 0L
                        : d.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                d.getResolvedAt(), d.getResolvedBy());
    }
}
