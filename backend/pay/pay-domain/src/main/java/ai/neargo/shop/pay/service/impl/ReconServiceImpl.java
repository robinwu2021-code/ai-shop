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
    private final RefundCapacityLedger capacity;
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
    /** 掉单巡检一页的行数 */
    static final int STALE_PAGE = 200;
    /** 一轮最多查多少笔（W3 E05：十万行积压时不能一轮全拉回来逐笔调渠道） */
    @Value("${shop.pay.recon.stale-scan.max-per-run:2000}")
    private int maxPerRun = 2000;
    /** 每个渠道一轮最多查多少笔：一个渠道积压不饿死别的渠道，也不把它的查单接口打满 */
    @Value("${shop.pay.recon.stale-scan.per-channel-per-run:500}")
    private int perChannelPerRun = 500;
    /** 游标：上一轮停在哪个 id。只在本进程内（多实例时巡检本就由调度锁保证单跑） */
    private volatile long staleCursor;

    long staleCursor() {
        return staleCursor;
    }

    /** 关单后观察窗（小时）：这段时间内关掉的收款尝试仍随掉单巡检回查（I3 S7） */
    @Value("${shop.pay.recon.closed-watch-hours:26}")
    private long closedWatchHours = 26;

    public ReconServiceImpl(SettleMappers.PaymentMapper paymentMapper,
                            SettleMappers.ReconDiffMapper diffMapper,
                            PayQueryPort payQueryPort,
                            org.springframework.beans.factory.ObjectProvider<ReconAxis> axes,
                            @Value("${shop.recon.stale-minutes:20}") int staleMinutes, RefundCapacityLedger capacity) {
        this.capacity = capacity;
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
        return checkStalePayments(now, new ReconService.ScanLimits(maxPerRun, perChannelPerRun));
    }

    @Override
    public List<ReconService.Finding> checkStalePayments(long now, ReconService.ScanLimits limits) {
        String day=DAY.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()));
        var findings=new java.util.ArrayList<ReconService.Finding>();
        java.util.Map<String,Integer> perChannel=new java.util.HashMap<>();
        long cursor=staleCursor;
        while(findings.size()<limits.maxPerRun()) {
            List<StlPayment> page=stalePage(now,cursor);
            for(var payment:page) {
                if(findings.size()>=limits.maxPerRun()) break;
                if(payment.getId()!=null) cursor=payment.getId();
                // 渠道份额用完的行这轮跳过：游标照样前移，扫到底从头时再轮到它
                if(perChannel.merge(String.valueOf(payment.getPayChannel()),1,Integer::sum)>limits.perChannelPerRun()) continue;
                findings.add(checkAndArchive(payment,day));
            }
            if(page.size()<STALE_PAGE) { cursor=0; break; }
        }
        staleCursor=cursor;
        return findings;
    }

    /** 一页待查：按 id 升序、从游标往后 */
    private List<StlPayment> stalePage(long now,long cursor) {
        long cutoff = now - staleMinutes * 60_000L;
        return DataScopeContext.executeWithoutScope(() ->
                paymentMapper.selectList(Wrappers.<StlPayment>lambdaQuery()
                    .and(q -> q.eq(StlPayment::getDirection,StlPayment.PAY)
                            .in(StlPayment::getStatus,StlPayment.INIT,StlPayment.PENDING)
                        // 关单后的观察窗（I3 S7）：关单关到了尝试这一层，而渠道没有关单接口、用户仍可能付成；
                        // 回调又丢了的话只能靠这里查到。窗口有界 —— 待查集合不会再只增不减（R18）
                        .or(c -> c.eq(StlPayment::getDirection,StlPayment.PAY).eq(StlPayment::getStatus,StlPayment.CLOSED)
                            .ge(StlPayment::getClosedAt, now - closedWatchHours * 3_600_000L))
                        .or(r -> r.eq(StlPayment::getDirection,StlPayment.REFUND)
                            .and(t -> t.in(StlPayment::getStatus,StlPayment.INIT,StlPayment.PENDING)
                                .or(u -> u.eq(StlPayment::getStatus,StlPayment.FAILED)
                                    .and(v -> v.isNull(StlPayment::getRefundTerminalProof)
                                        .or().eq(StlPayment::getRefundTerminalProof,"")))
                                .or().in(StlPayment::getRefundQueryState,"MANUAL_REQUIRED","LATE_SUCCESS","TERMINAL_FAILED"))))
                    .le(StlPayment::getCreatedAt,java.time.LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(cutoff),ZoneId.systemDefault()))
                    .gt(StlPayment::getId,cursor)
                    .orderByAsc(StlPayment::getId)
                    .last("LIMIT "+STALE_PAGE)));
    }

    /** 单笔核对并归档；一笔失败不挡下一笔 */
    private ReconService.Finding checkAndArchive(StlPayment payment,String day) {
        ReconService.Finding finding;
        try { finding=checkOne(payment,day); }
        catch(RuntimeException invalid) {
            String error=invalid instanceof IllegalArgumentException
                    ? "REFUND_RECON_RECEIPT_INVALID" : "REFUND_RECON_WRITE_FAILED";
            log.warn("[recon] 单笔核对失败 payment={} outRefundNo={} error={} exception={}",
                    payment.getPaymentNo(),payment.getOutTradeNo(),error,invalid.getClass().getSimpleName());
            finding=new ReconService.Finding(payment.getPaymentNo(),payment.getOrderNo(),payment.getPayChannel(),
                    payment.getOutTradeNo(),payment.getDirection(),payment.getAmountMinor(),false,false,true,
                    0,null,day,payment.getCurrency(),null,error);
        }
        if(finding.isRefund() && (finding.requiresManual() || finding.queryFailed())) {
            try { recordFinding(finding,refundIssueType(finding),
                    (finding.errorCode()==null?"REFUND_MANUAL_REQUIRED":finding.errorCode())
                            + " 原退款号="+finding.outTradeNo()+"，需人工核对；不得自动释放或重新付款"); }
            catch(RuntimeException archiveFailure) {
                log.error("[recon] 异常归档失败 payment={} exception={}",
                        payment.getPaymentNo(),archiveFailure.getClass().getSimpleName());
                finding=new ReconService.Finding(finding.paymentNo(),finding.orderNo(),finding.payChannel(),
                        finding.outTradeNo(),finding.direction(),finding.ourAmountMinor(),false,false,true,
                        finding.channelAmountMinor(),finding.channelTradeNo(),finding.day(),finding.channelCurrency(),
                        finding.refundOutcome(),"REFUND_RECON_ARCHIVE_FAILED");
            }
        }
        return finding;
    }

    private String refundIssueType(ReconService.Finding finding) {
        if("REFUND_LATE_SUCCESS_FROZEN".equals(finding.errorCode())) return StlReconDiff.REFUND_LATE_SUCCESS;
        if(finding.refundOutcome()==ai.neargo.shop.spi.pay.RefundQueryResult.Status.TERMINAL_FAILED) return StlReconDiff.REFUND_CLOSED;
        if(finding.refundOutcome()==ai.neargo.shop.spi.pay.RefundQueryResult.Status.MANUAL_REQUIRED) return StlReconDiff.REFUND_ABNORMAL;
        return StlReconDiff.PLATFORM_ONLY;
    }

    private ReconService.Finding checkOne(StlPayment p,String day) {
        PayQueryPort.Result r;
        ai.neargo.shop.spi.pay.RefundQueryResult.Status status=null;
        String error=null;
        if(StlPayment.REFUND.equals(p.getDirection())) {
            var stored=capacity.safety(p);
            if("LATE_SUCCESS".equals(p.getRefundQueryState())) {
                status=ai.neargo.shop.spi.pay.RefundQueryResult.Status.MANUAL_REQUIRED;
                error="REFUND_LATE_SUCCESS_FROZEN";
                r=new PayQueryPort.Result(true,false,true,0,null,p.getCurrency());
            } else if(stored!=null && stored.terminalVerified()) {
                status=ai.neargo.shop.spi.pay.RefundQueryResult.Status.TERMINAL_FAILED;
                error="REFUND_TERMINAL_CLOSED";
                r=new PayQueryPort.Result(true,false,true,0,p.getTradeNo(),p.getCurrency());
            } else if(p.getPayChannel()==null || p.getPayChannel().isBlank()
                    || p.getOutTradeNo()==null || p.getOutTradeNo().isBlank()) {
                status=ai.neargo.shop.spi.pay.RefundQueryResult.Status.MANUAL_REQUIRED;
                error="REFUND_QUERY_COORDINATES_REQUIRED";
                r=new PayQueryPort.Result(true,false,true,0,null,p.getCurrency());
            } else {
                var outcome=payQueryPort.queryRefundOutcome(p.getPayChannel(),p.getOutTradeNo());
                if(outcome==null)outcome=ai.neargo.shop.spi.pay.RefundQueryResult.unknown(p.getOutTradeNo());
                if(!java.util.Objects.equals(p.getOutTradeNo(),outcome.outRefundNo()))
                    throw new IllegalArgumentException("REFUND_QUERY_IDENTITY_MISMATCH");
                capacity.observe(p.getPaymentNo(),outcome);
                status=outcome.status();
                if(stored!=null && stored.outcome()==ai.neargo.shop.spi.pay.RefundQueryResult.Status.MANUAL_REQUIRED
                        && status!=ai.neargo.shop.spi.pay.RefundQueryResult.Status.SUCCEEDED
                        && status!=ai.neargo.shop.spi.pay.RefundQueryResult.Status.TERMINAL_FAILED)
                    status=ai.neargo.shop.spi.pay.RefundQueryResult.Status.MANUAL_REQUIRED;
                if(status==ai.neargo.shop.spi.pay.RefundQueryResult.Status.MANUAL_REQUIRED)error="REFUND_MANUAL_REQUIRED";
                if(status==ai.neargo.shop.spi.pay.RefundQueryResult.Status.TERMINAL_FAILED)error="REFUND_TERMINAL_CLOSED";
                r=new PayQueryPort.Result(status!=ai.neargo.shop.spi.pay.RefundQueryResult.Status.UNKNOWN,
                        status==ai.neargo.shop.spi.pay.RefundQueryResult.Status.SUCCEEDED,true,
                        outcome.amountMinor(),outcome.providerNo(),outcome.currency());
            }
        } else r=payQueryPort.query(p.getPayChannel(),p.getOutTradeNo());
        if(r==null)r=new PayQueryPort.Result(false,false,false,0,null,p.getCurrency());
        return new ReconService.Finding(p.getPaymentNo(),p.getOrderNo(),p.getPayChannel(),p.getOutTradeNo(),
                p.getDirection(),p.getAmountMinor(),r.ok()&&r.found()&&r.paid(),r.ok()&&!r.paid()&&!r.found(),
                !r.ok(),r.amountMinor(),r.tradeNo(),day,r.currency(),status,error);
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
                        f.channelAmountMinor(), f.channelTradeNo(), f.channelCurrency()),
                diffType, note, f.ourAmountMinor());
    }


    /**
     * 落一条差异。
     *
     * <p><b>同一个没解决的问题只有一条</b>，不论哪天发现的（F15 · Review R19）。
     * 此前去重条件带账期日：自查每天跑，一笔掉单每天新开一条，运营要逐条点开才知道是同一笔。
     * 已处置（非 PENDING）的不算 —— 同一笔处置后又出问题，是一件新的事，要新开。
     *
     * <p>系统生成的说明写 {@code note}；{@code resolution} 只放运营的处置结论（此前被说明占用）。
     */
    private void recordDiff(String day, StlPayment p, PayQueryPort.Result r,
                            String type, String note, Long expectedAmount) {
        String dedupKey = p.getPayChannel() + "|" + p.getPaymentNo() + "|" + type;
        boolean exists = DataScopeContext.executeWithoutScope(() -> diffMapper.exists(
                Wrappers.<StlReconDiff>lambdaQuery()
                        .eq(StlReconDiff::getPayChannel, p.getPayChannel())
                        .eq(StlReconDiff::getPaymentNo, p.getPaymentNo())
                        .eq(StlReconDiff::getDiffType, type)
                        .eq(StlReconDiff::getStatus, StlReconDiff.PENDING)));
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
        d.setPlatformAmountMinor(expectedAmount == null ? 0L : expectedAmount);
        d.setStatus(StlReconDiff.PENDING);
        d.setDedupKey(dedupKey);
        d.setNote(note == null ? null : note.substring(0, Math.min(note.length(), 255)));
        DataScopeContext.executeWithoutScope(() -> diffMapper.insert(d));
    }

    @Override public boolean isPayPayment(String no) {
        if(no==null)return false;
        var row=DataScopeContext.executeWithoutScope(() -> paymentMapper.selectOne(
                Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getPaymentNo,no)));
        return row!=null && StlPayment.PAY.equals(row.getDirection());
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
                d.getResolvedAt(), d.getResolvedBy(), d.getNote());
    }
}
