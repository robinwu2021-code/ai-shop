package ai.neargo.shop.pay.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.entity.StlReconDiff;
import ai.neargo.shop.pay.mapper.SettleMappers;
import ai.neargo.shop.pay.service.ReconService;
import ai.neargo.shop.pay.service.recon.ReconAxis;
import ai.neargo.shop.spi.pay.PayQueryPort;
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
                            org.springframework.beans.factory.ObjectProvider<ReconAxis> axes,
                            @Value("${shop.recon.stale-minutes:20}") int staleMinutes) {
        this.paymentMapper = paymentMapper;
        this.diffMapper = diffMapper;
        this.payQueryPort = payQueryPort;
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
    public List<ReconService.Finding> checkStalePayments(long now) {
        long cutoff = now - staleMinutes * 60_000L;
        List<StlPayment> stale = DataScopeContext.executeWithoutScope(() ->
                paymentMapper.selectList(Wrappers.<StlPayment>lambdaQuery()
                        /*
                         * **收款与退款一起扫**（S8 · 2026-09-02）。
                         *
                         * 此前只扫 PAY —— 而退款流水从 2026-09-02 起才开始落，
                         * 在那之前「只扫 PAY」与「扫全部」结果一样，
                         * 所以这个限制<b>看起来一直是对的</b>。
                         *
                         * 现在退款有流水了：一笔停在 PENDING 的退款
                         * 意味着「钱可能已经退出去而我方不知道」，
                         * 与掉单同样严重，而且方向相反 —— 掉单是钱没进来，
                         * 这是钱可能出去了。两者都要回查通道。
                         *
                         * 其余三个方向（补差 / 补差回退 / 打款）还没有流水，
                         * 等它们开始落时，这里的清单要跟着加 ——
                         * 而<b>覆盖范围那句话必须同步改</b>，否则「零差异」是句假话。
                         */
                        .in(StlPayment::getDirection, StlPayment.PAY, StlPayment.REFUND)
                        .in(StlPayment::getStatus, StlPayment.INIT, StlPayment.PENDING)
                        .le(StlPayment::getCreatedAt,
                                java.time.LocalDateTime.ofInstant(
                                        Instant.ofEpochMilli(cutoff), ZoneId.systemDefault()))));
        String day = DAY.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()));

        return stale.stream().map(p -> {
            /*
             * **按方向选查询接口。**收款查收款单，退款查退款单 ——
             * 通道侧这是两套单据（微信查退款用 refund_id，路径都不同）。
             *
             * 混用的后果是单向的、且很重：拿退款单号去查收款接口，
             * 通道会说「没有这笔」，而对账把「通道说没有」当作
             * <b>可以安全关单</b>的依据 —— 于是待确认的退款被批量关掉，
             * 而钱可能真的已经退出去了。
             *
             * 反过来（拿收款单号查退款接口）也一样错，只是今天不会发生。
             */
            PayQueryPort.Result r = StlPayment.REFUND.equals(p.getDirection())
                    ? payQueryPort.queryRefund(p.getPayChannel(), p.getOutTradeNo())
                    : payQueryPort.query(p.getPayChannel(), p.getOutTradeNo());
            return new ReconService.Finding(p.getPaymentNo(), p.getOrderNo(), p.getPayChannel(),
                    p.getOutTradeNo(), p.getAmountMinor(),
                    r.ok() && r.paid(), r.ok() && !r.paid() && !r.found(), !r.ok(),
                    r.amountMinor(), r.tradeNo(), day);
        }).toList();
    }

    @Override
    public void recordFinding(ReconService.Finding f, String diffType, String note) {
        StlPayment p = DataScopeContext.executeWithoutScope(() ->
                paymentMapper.selectOne(Wrappers.<StlPayment>lambdaQuery()
                        .eq(StlPayment::getPaymentNo, f.paymentNo()).last("LIMIT 1")));
        if (p == null) {
            // 流水在这中间被处理掉了 —— 差异行没有依附对象，记了也无从回溯
            log.warn("[recon] 记差异时找不到流水 paymentNo={}", f.paymentNo());
            return;
        }
        recordDiff(f.day(), p,
                new PayQueryPort.Result(true, f.paidOnChannel(), !f.notFound(),
                        f.channelAmountMinor(), f.channelTradeNo()),
                diffType, note);
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
    @Transactional("payTxManager")
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
        /*
         * **覆盖范围要把「哪些方向没有账可对」也说出来。**
         *
         * 此前这句话只提渠道账单，而 stl_payment 有五个方向 ——
         * 补差、补差回退、打款三个方向<b>连流水都还没有</b>，
         * 也就谈不上对账。不说的话，运营看到「零差异」会以为账是平的，
         * 而实际上有三类资金动作根本不在视野里。
         *
         * 退款是 2026-09-02（S8）才开始落流水并纳入扫描的 ——
         * 每补一个方向，这句话都要跟着改。
         */
        return new Coverage(false,
                "渠道账单未接入：本列表只覆盖平台侧可自查的部分"
                        + "（超时未终态的**收款与退款**）。"
                        + "「渠道扣了钱而平台没有记录」这一类差异现在看不见，需接入对账单后才会出现。"
                        + "另外，补差 / 补差回退 / 打款三个方向尚无资金流水，"
                        + "因而也不在对账范围内 —— 「零差异」不代表这三类是平的。");
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
