package ai.neargo.shop.settle.service.recon;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.settle.entity.StlBill;
import ai.neargo.shop.settle.entity.StlReconDiff;
import ai.neargo.shop.settle.mapper.SettleMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分账轴：<b>我方发出了分账指令，而通道那边迟迟不确认。</b>
 *
 * <p>这条轴今天只有 <b>A 侧</b>（我方自查）——「通道那边实际划走了多少」
 * 要等分账查询能力，见 {@link #coverage()}。
 *
 * <p><b>A 侧自查本身就有价值</b>，这一点收款轴已经证明过：
 * 「我方发出了而迟迟没有终态」这一类，不需要对方的账单就能发现。
 * 而在分账这条链上它尤其要紧 —— 今天底下调的是 {@code StubSplitGateway}，
 * <b>每一笔「已发出」实际上都不会有回执</b>。所以这条轴上线第一天就会报出
 * 全部在途单，而那正是它该说的实话。
 */
@Component
public class SplitReconAxis implements ReconAxis {

    public static final String CODE = "SPLIT";

    /** 差异类型：指令发出后超过阈值仍未收到确认 */
    private static final String DIFF_UNCONFIRMED = "SPLIT_UNCONFIRMED";

    private final SettleMappers.BillMapper billMapper;
    private final SettleMappers.ReconDiffMapper diffMapper;

    /**
     * 多久没确认算异常。默认 24 小时 —— 微信分账的回执通常在分钟级，
     * 一天还没到说明这笔要人去看了。
     *
     * <p>做成可配是因为**接通道之前这个数没有真实依据**：
     * 桩时代它永远不会有回执，阈值定多少都一样；接上之后要按真实回执时延重定。
     */
    private final int staleHours;

    public SplitReconAxis(SettleMappers.BillMapper billMapper,
                          SettleMappers.ReconDiffMapper diffMapper,
                          @Value("${shop.recon.split-stale-hours:24}") int staleHours) {
        this.billMapper = billMapper;
        this.diffMapper = diffMapper;
        this.staleHours = staleHours;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public ScanOutcome scan(long now) {
        long cutoff = now - staleHours * 3_600_000L;
        /*
         * ⚠️ **只捞 SPLIT，不捞 OFFLINE_SETTLED。**
         *
         * 线下单的钱从没进过通道，混进来就是**永久无解差异** ——
         * 没有任何人能把它「处置」掉，因为它本来就不该出现在通道流水里。
         * 而无解差异一多，对账页就没人看了，真差异跟着一起被埋掉。
         *
         * **判据用状态不用 pay_channel**：后者是 2026-08-26 那批才开始写的，
         * 存量行全是 null —— 按它筛会把存量的线下单全放进来。
         */
        List<StlBill> stale = DataScopeContext.executeWithoutScope(() ->
                billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getStatus, StlBill.SPLIT)
                        .isNull(StlBill::getSplitConfirmedAt)
                        .le(StlBill::getSplitAt, cutoff)));

        int opened = 0;
        for (StlBill b : stale) {
            if (alreadyOpen(b.getSettleNo())) {
                // 幂等：连跑两轮差异不该翻倍。**按结算单去重而不是按天** ——
                // 同一笔卡了三天不该变成三条待处置
                continue;
            }
            StlReconDiff d = new StlReconDiff();
            d.setAxis(CODE);
            d.setDiffNo(BizKey.next(BizKey.RECON_DIFF));
            d.setDiffType(DIFF_UNCONFIRMED);
            // bill_date 必填：对账是**按天组织**的，运营按日期核。
            // 用「发现日」而不是单据日 —— 一笔卡了三天的单，运营要在今天这一页看到它
            d.setBillDate(java.time.LocalDate.now().toString());
            d.setSource("SELF_CHECK");
            d.setOrderNo(b.getOrderNo());
            d.setPaymentNo(b.getSettleNo());
            d.setPlatformAmountMinor(b.getSplitAmountMinor());
            // 存量结算单的 pay_channel 是 null（那一列 2026-08-26 才开始写）——
            // 落 null 会撞 NOT NULL，所以兜到「不适用」
            d.setPayChannel(b.getPayChannel() == null || b.getPayChannel().isBlank()
                    ? CHANNEL_NA : b.getPayChannel());
            d.setStatus("PENDING");
            d.setTenantNo("MAIN");
            d.setCreatedAt(LocalDateTime.now());
            DataScopeContext.executeWithoutScope(() -> diffMapper.insert(d));
            opened++;
        }
        return new ScanOutcome(stale.size(), 0, opened, 0);
    }

    private boolean alreadyOpen(String settleNo) {
        return DataScopeContext.executeWithoutScope(() ->
                diffMapper.selectCount(Wrappers.<StlReconDiff>lambdaQuery()
                        .eq(StlReconDiff::getAxis, CODE)
                        .eq(StlReconDiff::getPaymentNo, settleNo)
                        .eq(StlReconDiff::getStatus, "PENDING"))) > 0;
    }

    @Override
    public Coverage coverage() {
        return new Coverage(false,
                "只有平台侧自查：扫我方已发出分账指令而超过 " + staleHours
                + " 小时未收到确认的结算单。**「通道那边实际划走了多少」现在看不见** —— "
                + "要等分账查询能力接入。⚠️ 另外，分账网关目前是桩实现，"
                + "所以每一笔已发出的单都不会有回执 —— 这条轴报出来的量反映的是这件事，"
                + "不是通道出了问题。");
    }
}
