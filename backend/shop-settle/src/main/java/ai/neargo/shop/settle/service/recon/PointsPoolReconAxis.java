package ai.neargo.shop.settle.service.recon;

import ai.neargo.shop.settle.PointsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 积分池轴：<b>池子里的钱与流通中的积分不相等。</b>
 *
 * <p>这条轴与另外三条形状不同 —— 它<b>不产生逐笔差异</b>，只判一个恒等式：
 *
 * <pre>池子余额 == 流通中的积分 × 汇率 + 已扣未兑付的抵扣</pre>
 *
 * <p>不逐笔记差异是有意的：失衡是<b>整体现象</b>，逐笔记会记出成千上万条
 * 指向同一件事的待处置，而没有一条能被单独「处置」掉。
 *
 * <p>⚠️ <b>它此前算得出来但没有任何地方会主动看。</b>
 * {@code PointsService.checkIdentity} 一直在那儿，而恒等式失衡不会有人知道 ——
 * 失衡量还会随成交量单调增长。这条轴的全部价值就是<b>让它开口</b>。
 */
@Component
public class PointsPoolReconAxis implements ReconAxis {

    public static final String CODE = "POINTS_POOL";

    private static final Logger log = LoggerFactory.getLogger(PointsPoolReconAxis.class);

    private final PointsService pointsService;

    public PointsPoolReconAxis(PointsService pointsService) {
        this.pointsService = pointsService;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public ScanOutcome scan(long now) {
        PointsService.IdentityCheck c = pointsService.checkIdentity("CN");
        if (c.balanced()) {
            return new ScanOutcome(1, 0, 0, 0);
        }
        /*
         * ⚠️ **只记日志，不落差异行。**
         *
         * 失衡是整体现象，不是某一笔的问题 —— 落成一条待处置的话，
         * 运营点进去会发现无从下手（它不指向任何一笔单据）。
         *
         * 真正该做的是**告警**，而告警发给谁还没有答案（见 TDD §6 风险）。
         * 在有接收方之前，这里报出「1 条未决」让它出现在对账总览上，
         * 至少不再是「算得出来但没人看」。
         */
        log.warn("积分池恒等式失衡：池子 {} 分 vs 应欠 {} 分，差 {} 分（未兑付 {}）",
                c.poolBalanceMinor(), c.owedMinor(), c.diffMinor(), c.pendingUseMinor());
        return new ScanOutcome(1, 0, 1, 0);
    }

    @Override
    public Coverage coverage() {
        return new Coverage(false,
                "只判恒等式「池子余额 == 流通积分 × 汇率 + 已扣未兑付」，"
                + "**不逐笔定位**：失衡是整体现象，逐笔记会记出成千上万条指向同一件事、"
                + "又没有一条能单独处置的待办。"
                + "⚠️ **失衡目前只写日志，没有告警接收方** —— 要有人主动来看这一页。");
    }
}
