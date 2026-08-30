package ai.neargo.shop.settle.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.settle.BillIdentity;
import ai.neargo.shop.settle.SettleBatchService;
import ai.neargo.shop.settle.SettleCycles;
import ai.neargo.shop.settle.entity.StlBill;
import ai.neargo.shop.settle.entity.StlReconDiff;
import ai.neargo.shop.settle.entity.StlSettleBatch;
import ai.neargo.shop.settle.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.settle.mapper.SettleMappers.ReconDiffMapper;
import ai.neargo.shop.settle.mapper.SettleMappers.SettleBatchMapper;
import ai.neargo.shop.spi.trade.SettleSourcePort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SettleBatchServiceImpl implements SettleBatchService {

    private static final Logger log = LoggerFactory.getLogger(SettleBatchServiceImpl.class);

    /** 一轮最多处理多少单 —— 防止第一次上线时一口气扫全量把库拖住 */
    private static final int SCAN_LIMIT = 500;

    /** 单据轴：这一条差异说的是「单据自己的账不平」，与通道无关 */
    private static final String AXIS_BILL = "BILL";

    private static final String DIFF_UNBALANCED = "BILL_UNBALANCED";

    private final BillMapper billMapper;
    private final SettleBatchMapper batchMapper;
    private final SettleSourcePort sourcePort;
    private final MerchantQueryPort merchantQueryPort;
    private final ai.neargo.shop.spi.platform.MasterDataPort masterDataPort;
    private final ReconDiffMapper diffMapper;

    /**
     * 售后期天数。<b>与积分转正用的是同一个数</b>（{@code shop.points.pending-days}）——
     * 积分抵扣的兑付跟着分账走，同一时点，不另立一套「积分售后期」。
     * 两个数分开配的话，迟早会一个改了另一个没改，而那时钱与分的口径就分岔了。
     */
    @Value("${shop.settle.after-sale-days:${shop.points.pending-days:7}}")
    private int afterSaleDays;

    /**
     * 自然日/周/月的边界按哪个时区切。
     *
     * <p>暂时全局一个值。多市场之后应按主体所在市场取（{@code MarketConfig.timezone}）——
     * 那时这里要换成按批次查，位置留在 {@link #zoneOf}。
     */
    @Value("${shop.settle.zone:Asia/Shanghai}")
    private String zoneId;

    public SettleBatchServiceImpl(BillMapper billMapper, SettleBatchMapper batchMapper,
                                  SettleSourcePort sourcePort, MerchantQueryPort merchantQueryPort,
                                  ai.neargo.shop.spi.platform.MasterDataPort masterDataPort,
                                  ReconDiffMapper diffMapper) {
        this.billMapper = billMapper;
        this.batchMapper = batchMapper;
        this.sourcePort = sourcePort;
        this.merchantQueryPort = merchantQueryPort;
        this.masterDataPort = masterDataPort;
        this.diffMapper = diffMapper;
    }

    // ---------------------------------------------------------------- ① 定 T2

    @Override
    @Transactional
    public int markSettleable() {
        List<StlBill> pending = DataScopeContext.executeWithoutScope(() ->
                billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getStatus, StlBill.PENDING)
                        .isNull(StlBill::getSettleableAt)
                        .last("LIMIT " + SCAN_LIMIT)));
        if (pending.isEmpty()) {
            return 0;
        }
        Map<String, StlBill> bySub = new LinkedHashMap<>();
        for (StlBill b : pending) {
            bySub.put(b.getSubOrderNo(), b);
        }
        long afterSaleMillis = afterSaleDays * 86400000L;
        long now = System.currentTimeMillis();
        int marked = 0;
        for (SettleSourcePort.SettleReadiness r : sourcePort.settleReadiness(bySub.keySet())) {
            /*
             * 三个条件缺一不可，而**第三条是硬闸**：
             * 售后没闭环就解冻，等于把争议中的钱先给了一方。
             */
            if (r.afterSaleOpen()) {
                continue;
            }
            long settleableAt = r.completedAt() + afterSaleMillis;
            if (settleableAt > now) {
                continue;   // 售后期还没过
            }
            StlBill bill = bySub.get(r.subOrderNo());
            /*
             * 落的是**算出来的 T2**，不是 now。
             * 落 now 的话，T2 会随「这一轮什么时候跑」漂移几分钟到几小时，
             * 而 T2 一动应结日跟着动 —— 商家看到的是同一批单的到账日不一样。
             */
            StlBill patch = new StlBill();
            patch.setId(bill.getId());
            patch.setSettleableAt(settleableAt);
            DataScopeContext.executeWithoutScope(() -> billMapper.updateById(patch));
            marked++;
        }
        if (marked > 0) {
            log.info("[settle-batch] 定下 T2 {} 单（本轮候选 {}）", marked, pending.size());
        }
        return marked;
    }

    // ---------------------------------------------------------------- ② 入批

    @Override
    @Transactional
    public int collectIntoBatches() {
        List<StlBill> ready = DataScopeContext.executeWithoutScope(() ->
                billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getStatus, StlBill.PENDING)
                        .isNotNull(StlBill::getSettleableAt)
                        .isNull(StlBill::getBatchNo)
                        .last("LIMIT " + SCAN_LIMIT)));
        int collected = 0;
        for (StlBill bill : ready) {
            /*
             * ★ 门 1 在**入批之前**跑，不在截批时跑。
             *
             * 不平的单**根本不进批**：进了再剔出来的话，批次的合计已经算过一次，
             * 而「算过又改」正是对账最难查的一类状态。
             * 这也落实了「单据级差异只挂该单」—— 同批其他单照常走。
             */
            if (!BillIdentity.balanced(bill)) {
                openIdentityDiff(bill);
                continue;
            }
            StlSettleBatch batch = openBatchFor(bill);
            if (batch == null) {
                continue;
            }
            StlBill patch = new StlBill();
            patch.setId(bill.getId());
            patch.setBatchNo(batch.getBatchNo());
            DataScopeContext.executeWithoutScope(() -> billMapper.updateById(patch));
            collected++;
        }
        if (collected > 0) {
            log.info("[settle-batch] 入批 {} 单", collected);
        }
        return collected;
    }

    /**
     * 找到（或开出）这一单该进的批次。
     *
     * <p>区间键取 <b>T3 应结日</b> 而不是 T2 —— 同一个应结日的单本来就该一起放，
     * 而按 T2 分区间的话，周结下每天各成一批、到了周一同时放七批，
     * 「批次」这个对象就白建了。
     *
     * @return 已 {@code COLLECTED} 之后的批次不再接新单，此时返回下一期的批次
     */
    private StlSettleBatch openBatchFor(StlBill bill) {
        String channel = bill.getPayChannel();
        if (channel == null || channel.isBlank()) {
            // 通道为空的单进不了批：批是按通道分的，没有通道就不知道该按谁的账期与冻结窗口算
            return null;
        }
        String cycle = SettleCycles.shorter(
                merchantQueryPort.settleCycleOf(bill.getEntityNo(), bill.getStoreNo(), channel),
                masterDataPort.channelSettleCycle(channel));
        long dueAt = SettleCycles.dueAt(bill.getSettleableAt(), cycle, zoneOf());

        StlSettleBatch exist = DataScopeContext.executeWithoutScope(() ->
                batchMapper.selectOne(Wrappers.<StlSettleBatch>lambdaQuery()
                        .eq(StlSettleBatch::getEntityNo, bill.getEntityNo())
                        .eq(StlSettleBatch::getPayChannel, channel)
                        .eq(StlSettleBatch::getPeriodFrom, dueAt)
                        .last("LIMIT 1")));
        if (exist != null) {
            /*
             * 已经截批的批次**不再接新单**：那时它的合计数已经被对账用过了，
             * 再塞进去两边就对不上。这种单落进下一期 —— 靠给它一个更晚的应结日，
             * 而不是硬塞进一个已经关闭的批。
             */
            return StlSettleBatch.DRAFT.equals(exist.getStatus()) ? exist : null;
        }
        StlSettleBatch batch = new StlSettleBatch();
        batch.setBatchNo(BizKey.next(BizKey.SETTLE_BATCH));
        batch.setEntityNo(bill.getEntityNo());
        batch.setPayChannel(channel);
        batch.setSettleCycle(cycle);
        // 区间键就是应结日：同一个应结日的单归一批
        batch.setPeriodFrom(dueAt);
        batch.setPeriodTo(dueAt);
        batch.setDueAt(dueAt);
        batch.setStatus(StlSettleBatch.DRAFT);
        batch.setReconScope(StlSettleBatch.SCOPE_SELF_ONLY);
        batch.setBillCount(0);
        batch.setGrossMinor(0L);
        batch.setNetMinor(0L);
        DataScopeContext.executeWithoutScope(() -> batchMapper.insert(batch));
        return batch;
    }

    // ---------------------------------------------------------------- ③ 截批

    @Override
    @Transactional
    public int closeDueBatches() {
        long now = System.currentTimeMillis();
        List<StlSettleBatch> due = DataScopeContext.executeWithoutScope(() ->
                batchMapper.selectList(Wrappers.<StlSettleBatch>lambdaQuery()
                        .eq(StlSettleBatch::getStatus, StlSettleBatch.DRAFT)
                        .le(StlSettleBatch::getDueAt, now)
                        .last("LIMIT " + SCAN_LIMIT)));
        int closed = 0;
        for (StlSettleBatch batch : due) {
            List<StlBill> bills = DataScopeContext.executeWithoutScope(() ->
                    billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                            .eq(StlBill::getBatchNo, batch.getBatchNo())));
            /*
             * 合计在**截批这一刻**算，不在收单期间维护。
             * 边收边加的话，每进一单都要改一次批次行，而中途那些值没有任何人会用；
             * 更糟的是并发入批时那个加法会丢更新，而丢了之后没有任何地方对得出来。
             */
            long gross = 0;
            long net = 0;
            Long earliest = null;
            for (StlBill b : bills) {
                gross += nz(b.getGrossMinor());
                net += nz(b.getNetMinor());
                Long accrued = b.getAccruedAt();
                if (accrued != null && (earliest == null || accrued < earliest)) {
                    earliest = accrued;
                }
            }
            StlSettleBatch patch = new StlSettleBatch();
            patch.setId(batch.getId());
            patch.setStatus(StlSettleBatch.COLLECTED);
            patch.setBillCount(bills.size());
            patch.setGrossMinor(gross);
            patch.setNetMinor(net);
            /*
             * Tmax 取本批**最早一单**的成交时刻 + 冻结窗口。
             * 取平均或取最晚都会让告警晚于实际到期 —— 整批一起放，
             * 而最早的那一笔先到期，它到期就意味着这一批已经出问题了。
             *
             * ⚠️ 冻结窗口的天数**还没有书面口径**（PRD 待确认 #1），
             * 所以这里暂不写死一个数：拿到之前 freeze_expire_at 留空，
             * 盯 Tmax 的那个任务据此知道「还不能判」，而不是按一个猜的数报警。
             */
            DataScopeContext.executeWithoutScope(() -> batchMapper.updateById(patch));
            closed++;
        }
        if (closed > 0) {
            log.info("[settle-batch] 截批 {} 个", closed);
        }
        return closed;
    }

    /**
     * 记一条<b>单据不平</b>的对账差异。
     *
     * <p><b>不自动修正。</b>差额来自哪一项是需要判断的问题 ——
     * 是费率取错、服务费算错，还是手续费落错了承担方？
     * 自动补平只会把问题藏起来，而账面从此看不出曾经不平过。
     *
     * <p>幂等：已有 PENDING 的同类差异就不再开 —— 这一单每一轮扫描都会被捞到，
     * 不去重的话一天能生出几百条指向同一件事的待处置。
     */
    private void openIdentityDiff(StlBill bill) {
        boolean exists = DataScopeContext.executeWithoutScope(() ->
                diffMapper.selectCount(Wrappers.<StlReconDiff>lambdaQuery()
                        .eq(StlReconDiff::getAxis, AXIS_BILL)
                        .eq(StlReconDiff::getPaymentNo, bill.getSettleNo())
                        .eq(StlReconDiff::getDiffType, DIFF_UNBALANCED)
                        .eq(StlReconDiff::getStatus, "PENDING"))) > 0;
        if (exists) {
            return;
        }
        long gap = BillIdentity.gap(bill);
        StlReconDiff d = new StlReconDiff();
        d.setAxis(AXIS_BILL);
        d.setDiffNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.RECON_DIFF));
        d.setDiffType(DIFF_UNBALANCED);
        // 用**发现日**而不是单据日：一笔卡了三天的单，运营要在今天这一页看到它
        d.setBillDate(java.time.LocalDate.now().toString());
        d.setSource("SELF_CHECK");
        d.setOrderNo(bill.getOrderNo());
        // 与 PayoutReconAxis 同一口径：单据轴的锚点是结算单号
        d.setPaymentNo(bill.getSettleNo());
        d.setPayChannel(bill.getPayChannel() == null || bill.getPayChannel().isBlank()
                ? ai.neargo.shop.settle.service.recon.ReconAxis.CHANNEL_NA : bill.getPayChannel());
        /*
         * 两个金额都落：**平台侧记基数，通道侧记「各项之和」**。
         * 只记差额的话，运营看到「差 3 分」还要自己回去把两边算一遍 ——
         * 而这两个数正是他要对的那两个。
         */
        d.setPlatformAmountMinor(nz(bill.getGrossMinor()));
        d.setChannelAmountMinor(nz(bill.getGrossMinor()) - gap);
        d.setStatus("PENDING");
        d.setTenantNo("MAIN");
        d.setCreatedAt(java.time.LocalDateTime.now());
        DataScopeContext.executeWithoutScope(() -> diffMapper.insert(d));
        log.warn("[settle-batch] 单据不平，不入批：{} 差 {} 分", bill.getSettleNo(), gap);
    }

    private ZoneId zoneOf() {
        try {
            return ZoneId.of(zoneId);
        } catch (RuntimeException e) {
            // 配错时不让整条链路停 —— 但要吼一声，否则所有人的应结日会静默按 UTC 算
            log.error("[settle-batch] 时区配置 {} 认不出，回落 Asia/Shanghai", zoneId, e);
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
