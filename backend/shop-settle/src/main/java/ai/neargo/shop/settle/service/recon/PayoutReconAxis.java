package ai.neargo.shop.settle.service.recon;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.settle.entity.StlBill;
import ai.neargo.shop.settle.entity.StlReconDiff;
import ai.neargo.shop.settle.mapper.SettleMappers;
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

    private final SettleMappers.BillMapper billMapper;
    private final SettleMappers.ReconDiffMapper diffMapper;

    public PayoutReconAxis(SettleMappers.BillMapper billMapper,
                           SettleMappers.ReconDiffMapper diffMapper) {
        this.billMapper = billMapper;
        this.diffMapper = diffMapper;
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
        return new ScanOutcome(paid.size(), 0, opened, 0);
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

    @Override
    public Coverage coverage() {
        return new Coverage(false,
                "只有平台侧自查：查「已付款却没有流水号」与「同一个流水号出现在多张单上」。"
                + "**「银行到底有没有划出这笔」看不见** —— 要等银行流水接入。"
                + "所以这条轴为空不代表钱都到了供应商账上。");
    }
}
