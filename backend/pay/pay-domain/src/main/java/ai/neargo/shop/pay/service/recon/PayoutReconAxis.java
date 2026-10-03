package ai.neargo.shop.pay.service.recon;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.pay.entity.StlBankFlow;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlReconDiff;
import ai.neargo.shop.pay.mapper.SettleMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 出款轴：自营应付登记了付款，而<b>凭据本身有问题</b>。
 *
 * <p>这条轴今天只有 A 侧（我方自查），因为没有银行流水的接入 ——
 * 「银行到底有没有划出这笔」看不见。但 A 侧能查的两件事都很实在：
 *
 * <ul>
 *   <li><b>已付款却没有流水号</b>　付款凭据缺失，事后对不上是必然的</li>
 *   <li><b>同一个流水号出现在多张单上</b>　要么是复制粘贴填错了，
 *       要么是一笔钱被记成了两笔付出 —— 后者是真金白银的重复付款</li>
 * </ul>
 *
 * <p>第二条尤其值得自查：它<b>不需要任何外部数据</b>就能发现一类资损，
 * 而人工登记流水号的场景下复制粘贴出错是常态。
 */
@Component
public class PayoutReconAxis implements ReconAxis {

    public static final String CODE = "PAYOUT";

    private static final String DIFF_NO_REF = "PAYOUT_NO_REF";
    private static final String DIFF_DUP_REF = "PAYOUT_DUP_REF";

    /** 银行流水里没有这笔（**已登记付款，而银行没划出去**）。B 侧 */
    private static final String DIFF_NO_BANK_FLOW = "PAYOUT_NO_BANK_FLOW";
    /** 银行划出去了，而系统里没有对应的付款登记。B 侧的反向 */
    private static final String DIFF_BANK_UNMATCHED = "PAYOUT_BANK_UNMATCHED";

    private final SettleMappers.BillMapper billMapper;
    private final SettleMappers.ReconDiffMapper diffMapper;
    private final SettleMappers.BankFlowMapper bankFlowMapper;

    public PayoutReconAxis(SettleMappers.BillMapper billMapper,
                           SettleMappers.ReconDiffMapper diffMapper,
                           SettleMappers.BankFlowMapper bankFlowMapper) {
        this.billMapper = billMapper;
        this.diffMapper = diffMapper;
        this.bankFlowMapper = bankFlowMapper;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public ScanOutcome scan(long now) {
        List<StlBill> paid = DataScopeContext.executeWithoutScope(() ->
                billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getStatus, StlBill.PAID)));

        // 流水号 → 用了它的单。**先全量归并再判**：逐条查「有没有别的单用了同一个号」
        // 是 N 次往返，而这张表会一直长
        Map<String, List<StlBill>> byRef = new HashMap<>();
        int opened = 0;
        for (StlBill b : paid) {
            String ref = b.getPaymentRef();
            if (ref == null || ref.isBlank()) {
                opened += open(b, DIFF_NO_REF, null);
                continue;
            }
            byRef.computeIfAbsent(ref.trim(), k -> new java.util.ArrayList<>()).add(b);
        }
        for (var e : byRef.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            // 一个号多张单：**每张都记一条** —— 只记一条的话，
            // 处置的人看不出另外几张是哪些，还得自己去查
            for (StlBill b : e.getValue()) {
                opened += open(b, DIFF_DUP_REF, e.getKey());
            }
        }
        /*
         * ── B 侧：与银行流水对勾 ──
         *
         * ⚠️ **「查不到流水」不等于「银行没划」。** 某一天的流水还没导入时，
         * 那天的付款单在这里一条都找不到 —— 把它们记成差异，等于把一批
         * 真实付过的款报成「银行没划出去」，而运营会真的去查银行。
         *
         * 所以只比对**流水已经覆盖到的日期区间**，区间之外的计入 deferred
         * （ReconAxis 的注释写着：把「查不到」当成「没有」是这类任务最容易犯的错）。
         */
        List<StlBankFlow> flows = DataScopeContext.executeWithoutScope(() ->
                bankFlowMapper.selectList(Wrappers.<StlBankFlow>lambdaQuery()
                        .eq(StlBankFlow::getDirection, StlBankFlow.OUT)));
        if (flows.isEmpty()) {
            // 一条流水都没有 = B 侧还没数据。全部 deferred，一条差异都不记
            return new ScanOutcome(paid.size(), 0, opened, paid.size());
        }

        String minDate = flows.stream().map(StlBankFlow::getTradeDate)
                .filter(java.util.Objects::nonNull).min(String::compareTo).orElse(null);
        String maxDate = flows.stream().map(StlBankFlow::getTradeDate)
                .filter(java.util.Objects::nonNull).max(String::compareTo).orElse(null);
        Map<String, StlBankFlow> flowByNo = new HashMap<>();
        for (StlBankFlow f : flows) {
            if (f.getFlowNo() != null) {
                flowByNo.put(f.getFlowNo().trim(), f);
            }
        }

        int deferred = 0;
        java.util.Set<String> hitFlowNos = new java.util.HashSet<>();
        for (StlBill b : paid) {
            String ref = b.getPaymentRef();
            if (ref == null || ref.isBlank()) {
                continue;   // A 侧已经记过「没有流水号」，不重复记
            }
            String payDay = b.getPaidAt() == null ? null
                    : java.time.Instant.ofEpochMilli(b.getPaidAt())
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();
            // 付款日落在流水覆盖区间之外 —— 判不了，留到下一轮
            if (payDay == null || minDate == null
                    || payDay.compareTo(minDate) < 0 || payDay.compareTo(maxDate) > 0) {
                deferred++;
                continue;
            }
            StlBankFlow f = flowByNo.get(ref.trim());
            if (f == null) {
                opened += open(b, DIFF_NO_BANK_FLOW, ref);
                continue;
            }
            hitFlowNos.add(f.getFlowNo().trim());
            // 勾上了就把关系落下来 —— 下次人工复核时不用再算一遍
            if (!b.getSettleNo().equals(f.getMatchedSettleNo())) {
                f.setMatchedSettleNo(b.getSettleNo());
                DataScopeContext.executeWithoutScope(() -> bankFlowMapper.updateById(f));
            }
        }

        /*
         * 反向：银行划出去了，而系统里没有任何单登记着这个流水号。
         * **这个方向才是真金白银的风险** —— 多付、重复付、或者付给了不该付的人，
         * 而正向（登记了没划）最多是钱还没走。
         */
        for (StlBankFlow f : flows) {
            String no = f.getFlowNo() == null ? null : f.getFlowNo().trim();
            if (no == null || hitFlowNos.contains(no) || f.getMatchedSettleNo() != null) {
                continue;
            }
            opened += openBankSide(f);
        }
        return new ScanOutcome(paid.size() + flows.size(), 0, opened, deferred);
    }

    private int open(StlBill b, String type, String ref) {
        boolean exists = DataScopeContext.executeWithoutScope(() ->
                diffMapper.selectCount(Wrappers.<StlReconDiff>lambdaQuery()
                        .eq(StlReconDiff::getAxis, CODE)
                        .eq(StlReconDiff::getPaymentNo, b.getSettleNo())
                        .eq(StlReconDiff::getDiffType, type)
                        .eq(StlReconDiff::getStatus, "PENDING"))) > 0;
        if (exists) {
            return 0;   // 幂等：连跑两轮差异不翻倍
        }
        StlReconDiff d = new StlReconDiff();
        d.setAxis(CODE);
        d.setDiffNo(BizKey.next(BizKey.RECON_DIFF));
        d.setDiffType(type);
        // bill_date 必填：对账是**按天组织**的，运营按日期核。
        // 用「发现日」而不是单据日 —— 一笔卡了三天的单，运营要在今天这一页看到它
        d.setBillDate(java.time.LocalDate.now().toString());
        d.setSource("SELF_CHECK");
        d.setOrderNo(b.getOrderNo());
        d.setPaymentNo(b.getSettleNo());
        // 出款走网银，没有支付通道 —— 用显式的「不适用」，见 ReconAxis.CHANNEL_NA
        d.setPayChannel(CHANNEL_NA);
        d.setChannelTxnNo(ref);
        d.setPlatformAmountMinor(b.getNetMinor());
        d.setStatus("PENDING");
        d.setTenantNo("MAIN");
        d.setCreatedAt(LocalDateTime.now());
        DataScopeContext.executeWithoutScope(() -> diffMapper.insert(d));
        return 1;
    }

    /**
     * 银行侧的孤儿流水：钱划出去了，而系统里没有任何单登记着这个流水号。
     *
     * <p><b>与正向差异分开记</b>：两者的处置完全不同 ——
     * 正向（登记了没划）是去查银行为什么没走；
     * 这一条是去查**这笔钱到底是谁付的、付给了谁**，而它可能是多付或重复付。
     */
    private int openBankSide(StlBankFlow f) {
        boolean exists = DataScopeContext.executeWithoutScope(() ->
                diffMapper.selectCount(Wrappers.<StlReconDiff>lambdaQuery()
                        .eq(StlReconDiff::getAxis, CODE)
                        .eq(StlReconDiff::getChannelTxnNo, f.getFlowNo())
                        .eq(StlReconDiff::getDiffType, DIFF_BANK_UNMATCHED)
                        .eq(StlReconDiff::getStatus, "PENDING"))) > 0;
        if (exists) {
            return 0;
        }
        StlReconDiff d = new StlReconDiff();
        d.setAxis(CODE);
        d.setDiffNo(BizKey.next(BizKey.RECON_DIFF));
        d.setDiffType(DIFF_BANK_UNMATCHED);
        d.setBillDate(java.time.LocalDate.now().toString());
        // 判据来自银行那边，不是我方自查 —— 处置的人要知道这条是谁说的
        d.setSource("BANK_FLOW");
        d.setPayChannel(CHANNEL_NA);
        d.setChannelTxnNo(f.getFlowNo());
        // 金额记在「通道侧」那一列：这笔数是银行给的，不是我方算的
        d.setChannelAmountMinor(f.getAmountMinor());
        d.setStatus("PENDING");
        d.setTenantNo("MAIN");
        d.setCreatedAt(LocalDateTime.now());
        DataScopeContext.executeWithoutScope(() -> diffMapper.insert(d));
        return 1;
    }

    @Override
    public Coverage coverage() {
        /*
         * **这句话是算出来的，不是写死的。**
         *
         * ReconAxis 的注释写着「不在端上写死 —— 写死的话能力补上之后页面还在说看不见」。
         * 同理也不能写死在这里：B 侧接上之后那句「看不见」就成了假话，
         * 而页面照样显示，没有任何东西会提醒。
         */
        List<StlBankFlow> flows = DataScopeContext.executeWithoutScope(() ->
                bankFlowMapper.selectList(Wrappers.<StlBankFlow>lambdaQuery()
                        .eq(StlBankFlow::getDirection, StlBankFlow.OUT)));
        if (flows.isEmpty()) {
            return new Coverage(false,
                    "只有平台侧自查：查「已付款却没有流水号」与「同一个流水号出现在多张单上」。"
                    + "**「银行到底有没有划出这笔」看不见** —— 还没有导入过银行流水。"
                    + "所以这条轴为空不代表钱都到了供应商账上。");
        }
        String min = flows.stream().map(StlBankFlow::getTradeDate)
                .filter(java.util.Objects::nonNull).min(String::compareTo).orElse("?");
        String max = flows.stream().map(StlBankFlow::getTradeDate)
                .filter(java.util.Objects::nonNull).max(String::compareTo).orElse("?");
        /*
         * **仍然 complete=false。** 人工上传天然不完整：只覆盖传过的那几天，
         * 而「哪几天没传」这件事本身没人盯着。说成完整的话，
         * 「这条轴为空」会被读成「所有付款都对上了」。
         */
        return new Coverage(false,
                "平台侧自查 + 银行流水比对，已覆盖 %s 至 %s。".formatted(min, max)
                + "**这个区间之外的付款仍然看不见** —— 银行流水是人工上传的，"
                + "没传的日期不会报差异，只会被跳过（计入 deferred）。");
    }
}
