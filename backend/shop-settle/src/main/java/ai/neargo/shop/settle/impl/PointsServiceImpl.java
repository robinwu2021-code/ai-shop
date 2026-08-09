package ai.neargo.shop.settle.impl;

import ai.neargo.shop.settle.PointsService;
import ai.neargo.shop.settle.dto.PointsVOs.MerchantPointAccountVO;
import ai.neargo.shop.settle.dto.PointsVOs.MerchantPointsRecordVO;
import ai.neargo.shop.settle.dto.PointsVOs.PointAccountVO;
import ai.neargo.shop.settle.dto.PointsVOs.PointRecordVO;
import ai.neargo.shop.settle.dto.PointsVOs.PointsDeductibleVO;
import ai.neargo.shop.settle.dto.PointsVOs.PointsOverviewVO;
import ai.neargo.shop.settle.dto.PointsVOs.PoolByChannelVO;
import ai.neargo.shop.settle.entity.PtsUserAccount;
import ai.neargo.shop.settle.entity.PtsUserLedger;
import ai.neargo.shop.settle.entity.StlBill;
import ai.neargo.shop.settle.entity.StlPointsPool;
import ai.neargo.shop.settle.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.settle.mapper.SettleMappers.PointsAccountMapper;
import ai.neargo.shop.settle.mapper.SettleMappers.PointsLedgerMapper;
import ai.neargo.shop.settle.mapper.SettleMappers.PointsPoolMapper;
import ai.neargo.shop.spi.user.MerchantAdminPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 积分域读侧实现。
 *
 * <p>三条口径在这里落地，改动前先读 docs/technical/积分域-完整方案.md：
 * <ul>
 *   <li><b>余额是派生的</b>，流水才是真源。这里读 account 表是走缓存，
 *       对账任务负责校验它与流水一致</li>
 *   <li><b>可用与待生效分开</b>。合成一个数用户就看不懂自己为什么花不出去</li>
 *   <li><b>商家不感知抵扣</b>。他的视图里只有自己发分的服务费</li>
 * </ul>
 */
@Service
public class PointsServiceImpl implements PointsService {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMM");

    /** 抵扣上限：券后金额的三成。运费不参与 —— 整单抵扣商家一分收不到。 */
    private static final double MAX_DEDUCT_RATIO = 0.30;

    /** 多少积分抵一个最小货币单位。与 shared 的 POINTS.perMinor 同值。 */
    private static final long POINTS_PER_MINOR = 1;

    private final PointsAccountMapper accountMapper;
    private final PointsLedgerMapper ledgerMapper;
    private final PointsPoolMapper poolMapper;
    private final BillMapper billMapper;
    private final MerchantQueryPort merchantQuery;
    private final MerchantAdminPort merchantAdmin;

    public PointsServiceImpl(PointsAccountMapper accountMapper,
                             PointsLedgerMapper ledgerMapper,
                             PointsPoolMapper poolMapper,
                             BillMapper billMapper,
                             MerchantQueryPort merchantQuery,
                             MerchantAdminPort merchantAdmin) {
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
        this.poolMapper = poolMapper;
        this.billMapper = billMapper;
        this.merchantQuery = merchantQuery;
        this.merchantAdmin = merchantAdmin;
    }

    @Override
    public PointAccountVO account(String userNo) {
        PtsUserAccount a = loadAccount(userNo);
        List<PtsUserLedger> pending = ledgerMapper.selectList(
                Wrappers.<PtsUserLedger>lambdaQuery()
                        .eq(PtsUserLedger::getUserNo, userNo)
                        .eq(PtsUserLedger::getBizType, PtsUserLedger.EARN)
                        .isNotNull(PtsUserLedger::getAvailableAt));
        Long nextActivate = pending.stream()
                .map(PtsUserLedger::getAvailableAt)
                .filter(t -> t != null && t > System.currentTimeMillis())
                .min(Comparator.naturalOrder())
                .orElse(null);

        // 到期是**账户级**的（V30 滚动到期）：到了就整个清零，
        // 所以「即将过期」要么是全部余额，要么是 0 —— 没有中间值
        long soon = a.getExpireAt() != null
                && a.getExpireAt() - System.currentTimeMillis() < 30L * 86_400_000L
                ? a.getBalance() : 0L;

        return new PointAccountVO(
                a.getBalance(), a.getPendingBalance(), nextActivate,
                a.getTotalEarn(), a.getTotalUse(), soon, a.getExpireAt());
    }

    @Override
    public List<PointRecordVO> records(String userNo, int page, int size) {
        List<PtsUserLedger> rows = ledgerMapper.selectList(
                Wrappers.<PtsUserLedger>lambdaQuery()
                        .eq(PtsUserLedger::getUserNo, userNo)
                        .orderByDesc(PtsUserLedger::getId)
                        .last("LIMIT " + offset(page, size) + ", " + capped(size)));
        List<PointRecordVO> out = new ArrayList<>(rows.size());
        for (PtsUserLedger r : rows) {
            out.add(new PointRecordVO(
                    r.getLedgerNo(), r.getBizType(), r.getPoints(), title(r),
                    r.getSubOrderNo(), millis(r), r.getBalanceAfter()));
        }
        return out;
    }

    @Override
    public PointsDeductibleVO deductible(String userNo, String merchantNo, long payableMinor) {
        String denied = pointsDenyReason(merchantNo);
        if (denied != null) {
            return new PointsDeductibleVO(0, 0, loadAccount(userNo).getBalance(), denied);
        }
        long balance = loadAccount(userNo).getBalance();
        // 三者取小，顺序与下单时一致：开关 → 上限 → 余额
        long capMinor = (long) Math.floor(payableMinor * MAX_DEDUCT_RATIO);
        long maxPoints = Math.min(balance, capMinor * POINTS_PER_MINOR);
        return new PointsDeductibleVO(maxPoints, maxPoints / POINTS_PER_MINOR, balance, null);
    }

    @Override
    public MerchantPointAccountVO merchantAccount(String merchantNo) {
        String period = currentPeriod();
        long expense = merchantBills(merchantNo, period).stream()
                .mapToLong(b -> b.getPointsFeeMinor() == null ? 0 : b.getPointsFeeMinor())
                .sum();
        String denied = pointsDenyReason(merchantNo);
        return new MerchantPointAccountVO(
                expense, period, denied == null, denied, isForced(merchantNo));
    }

    @Override
    public List<MerchantPointsRecordVO> merchantRecords(String merchantNo, String period,
                                                        int page, int size) {
        List<StlBill> bills = merchantBills(merchantNo, period == null ? currentPeriod() : period);
        List<MerchantPointsRecordVO> out = new ArrayList<>();
        int from = offset(page, size);
        for (int i = from; i < Math.min(bills.size(), from + capped(size)); i++) {
            StlBill b = bills.get(i);
            long fee = b.getPointsFeeMinor() == null ? 0 : b.getPointsFeeMinor();
            out.add(new MerchantPointsRecordVO(
                    b.getSettleNo(), b.getSubOrderNo(), fee * POINTS_PER_MINOR, fee,
                    period == null ? currentPeriod() : period,
                    b.getAccruedAt() == null ? 0 : b.getAccruedAt()));
        }
        return out;
    }

    @Override
    @Transactional
    public MerchantPointAccountVO toggleMerchant(String merchantNo, boolean enabled) {
        // 关闭只影响将来：**不动已发出的分，也不退已扣的服务费** ——
        // 否则关一次开关就是一次资金事故
        merchantAdmin.setPointsEnabled(merchantNo, enabled);
        return merchantAccount(merchantNo);
    }

    @Override
    public PointsOverviewVO overview(String market) {
        // 恒等式 2 的两边：流通中的积分 vs 池子里的钱。
        // 摆在一起是刻意的 —— 分开看的话，失衡要等到有人主动比对才会发现
        Long circulating = accountMapper.selectList(
                        Wrappers.<PtsUserAccount>lambdaQuery().eq(PtsUserAccount::getMarket, market))
                .stream().mapToLong(a -> a.getBalance() + a.getPendingBalance()).sum();

        List<StlPointsPool> flows = poolMapper.selectList(
                Wrappers.<StlPointsPool>lambdaQuery().eq(StlPointsPool::getMarket, market));

        // 池子按 (market, payChannel) 各自记账：账面一个数是平的，
        // 而两个真实账户可能一个溢一个空
        List<PoolByChannelVO> byChannel = flows.stream()
                .collect(java.util.stream.Collectors.groupingBy(StlPointsPool::getPayChannel))
                .entrySet().stream()
                .map(e -> new PoolByChannelVO(market, e.getKey(),
                        e.getValue().stream()
                                .mapToLong(f -> StlPointsPool.IN.equals(f.getDirection())
                                        ? f.getAmountMinor() : -f.getAmountMinor())
                                .sum()))
                .sorted(Comparator.comparing(PoolByChannelVO::payChannel))
                .toList();

        long poolBalance = byChannel.stream().mapToLong(PoolByChannelVO::balanceMinor).sum();
        long periodRedeem = flows.stream()
                .filter(f -> StlPointsPool.MERCHANT_PAY.equals(f.getPoolType()))
                .mapToLong(StlPointsPool::getAmountMinor).sum();

        return new PointsOverviewVO(circulating, poolBalance, periodRedeem, byChannel);
    }

    // ---------------------------------------------------------------- 内部

    private PtsUserAccount loadAccount(String userNo) {
        PtsUserAccount a = accountMapper.selectOne(
                Wrappers.<PtsUserAccount>lambdaQuery().eq(PtsUserAccount::getUserNo, userNo).last("LIMIT 1"));
        if (a != null) {
            return a;
        }
        // 没有账户就是零余额，不是错误 —— 新用户还没赚过分
        PtsUserAccount empty = new PtsUserAccount();
        empty.setUserNo(userNo);
        empty.setBalance(0L);
        empty.setPendingBalance(0L);
        empty.setTotalEarn(0L);
        empty.setTotalUse(0L);
        return empty;
    }

    private List<StlBill> merchantBills(String merchantNo, String period) {
        return billMapper.selectList(
                Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getEntityNo, merchantNo)
                        .gt(StlBill::getPointsFeeMinor, 0)
                        .orderByDesc(StlBill::getId));
    }

    /** 四级串联：全局 → 社区 → 主体 → 商家。为空表示可用。 */
    private String pointsDenyReason(String merchantNo) {
        return merchantQuery.pointsDenyReason(merchantNo);
    }

    private boolean isForced(String merchantNo) {
        return merchantQuery.isPointsForced(merchantNo);
    }

    private static String currentPeriod() {
        return java.time.LocalDate.now().format(PERIOD);
    }

    private static long millis(PtsUserLedger r) {
        return r.getCreatedAt() == null ? 0 : r.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static String title(PtsUserLedger r) {
        return switch (r.getBizType()) {
            case PtsUserLedger.EARN -> "消费获得";
            case PtsUserLedger.USE -> "下单抵扣";
            case PtsUserLedger.REFUND -> "退款返还";
            case PtsUserLedger.EXPIRE -> "到期清零";
            case PtsUserLedger.REVOKE -> "退款扣回";
            default -> r.getBizType();
        };
    }

    private static int offset(int page, int size) {
        return Math.max(0, page - 1) * capped(size);
    }

    /** 分页上限：不设的话一次 `size=100000` 就能把库拖垮 */
    private static int capped(int size) {
        return size <= 0 ? 20 : Math.min(size, 100);
    }
}
