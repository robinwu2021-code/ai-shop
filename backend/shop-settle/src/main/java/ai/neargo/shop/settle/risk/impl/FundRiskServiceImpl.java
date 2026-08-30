package ai.neargo.shop.settle.risk.impl;

import ai.neargo.shop.settle.risk.FundRisk;
import ai.neargo.shop.settle.risk.FundRiskService;
import ai.neargo.shop.settle.risk.FundRules;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.settle.entity.PayRiskShadowLog;
import ai.neargo.shop.settle.entity.StlBill;
import ai.neargo.shop.settle.entity.StlSettleBatch;
import ai.neargo.shop.settle.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.settle.mapper.SettleMappers.RiskShadowLogMapper;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link FundRiskService} 实现。
 *
 * <p><b>影子与真拦共用 {@link #decide} 这一个方法。</b>两套代码的话，
 * 切真拦那天的行为与影子期观察到的<b>不是同一个东西</b> ——
 * 而影子模式的全部价值就在于「观察到的就是将来会发生的」。
 */
@Service
public class FundRiskServiceImpl implements FundRiskService {

    private static final Logger log = LoggerFactory.getLogger(FundRiskServiceImpl.class);

    private final BillMapper billMapper;
    private final RiskShadowLogMapper shadowMapper;
    private final MerchantQueryPort merchantQueryPort;
    private final FundRules rules;

    /** true = 只记录不拦截。**默认 true** —— 默认关着的那一半才是上线第一天的样子 */
    @Value("${shop.risk.fund.shadow:true}")
    private boolean shadowMode;

    /** 退款率的统计窗口（天） */
    @Value("${shop.risk.fund.window-days:7}")
    private int windowDays;

    public FundRiskServiceImpl(BillMapper billMapper, RiskShadowLogMapper shadowMapper,
                           MerchantQueryPort merchantQueryPort, FundRules rules) {
        this.billMapper = billMapper;
        this.shadowMapper = shadowMapper;
        this.merchantQueryPort = merchantQueryPort;
        this.rules = rules;
    }

    @Override
    public FundRisk.Verdict decide(StlSettleBatch batch) {
        FundRisk.Facts facts = factsOf(batch);
        List<FundRisk.Verdict> hits = new ArrayList<>();
        for (FundRisk.Rule rule : rules.rules()) {
            FundRisk.Verdict v = rule.evaluate(facts);
            if (!FundRisk.PASS.equals(v.result())) {
                hits.add(v);
            }
        }
        String hitCodes = hits.stream().map(FundRisk.Verdict::code)
                .reduce((a, b) -> a + "," + b).orElse(null);
        String explain = hits.stream().map(FundRisk.Verdict::explain)
                .reduce((a, b) -> a + "；" + b).orElse(null);
        String verdict = hits.isEmpty() ? FundRisk.PASS : FundRisk.HOLD;

        writeShadow(batch, facts, verdict, hitCodes, explain);

        if (shadowMode) {
            if (!hits.isEmpty()) {
                // 影子期也要能看见：只写库不打日志的话，跑了两周才有人想起去查那张表
                log.info("[fund-risk][影子] 批次 {} 本可拦下 {} 分：{}",
                        batch.getBatchNo(), batch.getNetMinor(), explain);
            }
            return FundRisk.Verdict.pass("SHADOW");
        }
        return hits.isEmpty() ? FundRisk.Verdict.pass("ALL")
                : FundRisk.Verdict.hold(hitCodes, explain);
    }

    /**
     * 采集事实。
     *
     * <p>退款率在支付库内算完；保证金与欠款问一次主库（{@code fundRiskFacts} 一次取齐）。
     */
    private FundRisk.Facts factsOf(StlSettleBatch batch) {
        MerchantQueryPort.FundRiskFacts main = merchantQueryPort.fundRiskFacts(batch.getEntityNo());
        return new FundRisk.Facts(batch.getEntityNo(), batch.getBatchNo(),
                nz(batch.getNetMinor()), refundRateBp(batch.getEntityNo()), windowDays,
                main.depositAvailableMinor(), main.debtBalanceMinor());
    }

    /**
     * 近 N 天退款率（万分比）。
     *
     * <p><b>分母为零返回 -1，不返回 0</b>：刚开店的商家没有成交，
     * 退款率既不是 0% 也不是 100%，是<b>无意义</b>。返回 0 的话
     * 「没有成交」与「一笔都没退」在下游看来一样，而它们是完全不同的两件事。
     */
    private int refundRateBp(String entityNo) {
        long since = System.currentTimeMillis() - windowDays * 86400000L;
        List<StlBill> window = DataScopeContext.executeWithoutScope(() ->
                billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getEntityNo, entityNo)
                        .ge(StlBill::getAccruedAt, since)));
        long total = 0;
        long refunded = 0;
        for (StlBill b : window) {
            long gross = nz(b.getGrossMinor());
            total += gross;
            if (StlBill.REVERSED.equals(b.getStatus())) {
                refunded += gross;
            }
        }
        if (total <= 0) {
            return -1;
        }
        return (int) (refunded * 10000 / total);
    }

    private void writeShadow(StlSettleBatch batch, FundRisk.Facts facts, String verdict,
                             String hitCodes, String explain) {
        // 一批只记一条：截批任务重跑时不该把同一批记两遍，
        // 否则「命中率」这个复盘判据会被重复计数撑大
        boolean exists = DataScopeContext.executeWithoutScope(() ->
                shadowMapper.selectCount(Wrappers.<PayRiskShadowLog>lambdaQuery()
                        .eq(PayRiskShadowLog::getBatchNo, batch.getBatchNo()))) > 0;
        if (exists) {
            return;
        }
        PayRiskShadowLog row = new PayRiskShadowLog();
        row.setLogNo(BizKey.next(BizKey.RISK_SHADOW));
        row.setEntityNo(batch.getEntityNo());
        row.setBatchNo(batch.getBatchNo());
        row.setVerdict(verdict);
        row.setHitRules(hitCodes);
        row.setExplainText(explain);
        // 只有真会被拦时才记「本可拦下多少」—— PASS 也记的话，
        // 复盘时那个总额里混着根本不会被拦的钱
        row.setWouldHoldMinor(FundRisk.HOLD.equals(verdict) ? nz(batch.getNetMinor()) : 0L);
        row.setRefundRateBp(facts.refundRateBp());
        row.setDebtMinor(facts.debtBalanceMinor());
        row.setDepositMinor(facts.depositAvailableMinor());
        DataScopeContext.executeWithoutScope(() -> shadowMapper.insert(row));
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
