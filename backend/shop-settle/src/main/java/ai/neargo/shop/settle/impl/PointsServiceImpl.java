package ai.neargo.shop.settle.impl;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.settle.PointsConfig;
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
import ai.neargo.shop.spi.platform.SettingPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import tools.jackson.databind.ObjectMapper;
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


    private final PointsAccountMapper accountMapper;
    private final PointsLedgerMapper ledgerMapper;
    private final PointsPoolMapper poolMapper;
    private final BillMapper billMapper;
    private final MerchantQueryPort merchantQuery;
    private final MerchantAdminPort merchantAdmin;
    private final SettingPort settingPort;
    private final ObjectMapper json = new ObjectMapper();

    public PointsServiceImpl(PointsAccountMapper accountMapper,
                             PointsLedgerMapper ledgerMapper,
                             PointsPoolMapper poolMapper,
                             BillMapper billMapper,
                             MerchantQueryPort merchantQuery,
                             MerchantAdminPort merchantAdmin,
                             SettingPort settingPort) {
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
        this.poolMapper = poolMapper;
        this.billMapper = billMapper;
        this.merchantQuery = merchantQuery;
        this.merchantAdmin = merchantAdmin;
        this.settingPort = settingPort;
    }

    /**
     * 读积分参数。<b>每次都读</b>而不是缓存：运营改了汇率要立刻生效，
     * 而这条读的是 {@code sys_setting}（本来就带缓存），不值得再包一层。
     *
     * <p>解析失败退回默认值 —— 一行配置写坏了不该让所有人下不了单。
     */
    private PointsConfig config() {
        try {
            return json.readValue(settingPort.get(PointsConfig.KEY, PointsConfig.DEFAULT_JSON),
                    PointsConfig.class);
        } catch (Exception e) {
            return defaultConfig();
        }
    }

    private PointsConfig defaultConfig() {
        try {
            return json.readValue(PointsConfig.DEFAULT_JSON, PointsConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("积分默认配置写坏了", e);
        }
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
        // 三者取小，顺序与下单时一致：开关 → 上限 → 余额。
        // **上限那段算术只有 PointsConfig.maxUsablePoints 一处** ——
        // 试算与实扣分两处算，就会出现「说能抵 30、实扣 25」
        PointsConfig cfg = config();
        long maxPoints = cfg.maxUsablePoints(payableMinor, balance);
        return new PointsDeductibleVO(maxPoints, cfg.toMinor(maxPoints), balance, null);
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
                    b.getSettleNo(), b.getSubOrderNo(), fee * config().perMinor(), fee,
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

    // ================================================================ 写侧
    //
    // 在这批之前，本类只有 select —— 积分余额恒为 0，
    // C 端的抵扣开关点了等于没点。见 docs/technical/积分抵扣接入下单-对齐清单.md

    private static final String DEFAULT_MARKET = "CN";
    private static final String BIZ_USE = "USE";
    private static final String BIZ_EARN = "EARN";
    private static final String BIZ_REFUND = "REFUND";
    private static final String USE_PENDING = "PENDING";
    private static final String USE_REVERSED = "REVERSED";

    @Override
    @Transactional
    public DeductResult deductOnPlace(String userNo, long wantPoints, List<DeductTarget> targets) {
        if (wantPoints <= 0 || targets == null || targets.isEmpty()) {
            return DeductResult.none();
        }
        /*
         * 上限按**整单券后金额**算，不是逐个商家各算各的。
         *
         * 逐个算会让同样的商品拆成两家买时能多抵一倍（每家各占三成），
         * 而那正是刷单的入口 —— 上限是「这一单」的属性，不是「这一家」的。
         */
        long total = targets.stream().mapToLong(DeductTarget::payableMinor).sum();
        if (total <= 0) {
            return DeductResult.none();
        }
        // 闸一：商家开关。有一家关着就整单不抵 —— 分摊到关着的那家会让它凭空少收钱。
        // 一期自营模式下一单只有一家，这条是给将来兜底的
        for (DeductTarget t : targets) {
            if (pointsDenyReason(t.merchantNo()) != null) {
                return DeductResult.none();
            }
        }
        PtsUserAccount account = loadAccount(userNo);
        PointsConfig cfg = config();
        // 闸二、闸三：抵扣上限与余额。**与 deductible 调同一个方法**，
        // 两处各算一次就会出现「结算页说能抵 30、下单只抵了 25」
        long points = Math.min(wantPoints, cfg.maxUsablePoints(total, account.getBalance()));
        if (points <= 0) {
            return DeductResult.none();
        }

        long now = System.currentTimeMillis();
        String market = account.getMarket() == null ? DEFAULT_MARKET : account.getMarket();
        long expireAt = now + cfg.inactiveDays() * 86_400_000L;
        /*
         * 闸四，也是唯一真正拦并发的那道：SQL 里的 balance >= points。
         * 前面三道都只是「算出来能抵多少」，两个请求同时下单时它们各自都算得对。
         * 影响行数为 0 = 余额被别的请求抢先扣掉了，降级为不抵扣（不是报错）。
         */
        if (accountMapper.deduct(userNo, market, points, now, expireAt) == 0) {
            return DeductResult.none();
        }

        long amountMinor = cfg.toMinor(points);
        List<Share> shares = allocate(points, amountMinor, total, targets);
        long balanceAfter = account.getBalance() - points;
        for (Share sh : shares) {
            if (sh.points() <= 0) {
                continue;
            }
            PtsUserLedger use = new PtsUserLedger();
            use.setLedgerNo(BizKey.next(BizKey.POINTS_LEDGER));
            use.setUserNo(userNo);
            use.setBizType(BIZ_USE);
            use.setPoints(-sh.points());
            use.setBalanceAfter(balanceAfter);
            // 收单方：池子将来付钱给它。**不记发放方** —— 发分时商家已经付过费用金，
            // 此后这些分是平台对用户的负债，与谁发的无关
            use.setAcceptorMerchantNo(sh.merchantNo());
            use.setAmountMinor(sh.amountMinor());
            use.setRateSnapshot((int) cfg.perMinor());
            // PENDING = 预占：池子还没付钱，因为订单还可能取消或退款。
            // 兑付成立（CONFIRMED + 出池）在售后期结束时做，**本批不实现** ——
            // 所以账面上会积累一批挂着的 PENDING，这是已知边界不是遗漏
            use.setStatus(USE_PENDING);
            // 一个子单一条：部分退款时才退得准。合成一条的话，
            // 三家里退了一家，不知道该退多少分
            use.setSubOrderNo(sh.subOrderNo());
            use.setMarket(market);
            ledgerMapper.insert(use);
        }
        return new DeductResult(points, amountMinor, shares);
    }

    /**
     * 按券后金额比例分摊到各子单，<b>与券的分摊口径一致</b>。
     *
     * <p><b>余数给最后一家</b>：逐个按比例取整会少几分钱，
     * 而「各家之和等于总额」是对账的硬约束 —— 差一分的单会被对账任务报成异常。
     */
    private List<Share> allocate(long points, long amountMinor, long total,
                                 List<DeductTarget> targets) {
        List<Share> shares = new ArrayList<>();
        long assignedPoints = 0;
        long assignedAmount = 0;
        for (int i = 0; i < targets.size(); i++) {
            DeductTarget t = targets.get(i);
            boolean last = i == targets.size() - 1;
            long p = last ? points - assignedPoints : points * t.payableMinor() / total;
            long a = last ? amountMinor - assignedAmount : amountMinor * t.payableMinor() / total;
            assignedPoints += p;
            assignedAmount += a;
            shares.add(new Share(t.subOrderNo(), t.merchantNo(), p, a));
        }
        return shares;
    }

    @Override
    @Transactional
    public void reverse(String subOrderNo, String reason) {
        PtsUserLedger use = ledgerMapper.selectOne(Wrappers.<PtsUserLedger>lambdaQuery()
                .eq(PtsUserLedger::getSubOrderNo, subOrderNo)
                .eq(PtsUserLedger::getBizType, BIZ_USE)
                .eq(PtsUserLedger::getStatus, USE_PENDING)
                .last("LIMIT 1"));
        // 幂等：已经退过（状态不再是 PENDING）或本来就没用积分，都是静默返回。
        // 退款链路会对同一单调多次，报错反而会让退款失败
        if (use == null) {
            return;
        }
        long points = Math.abs(use.getPoints() == null ? 0L : use.getPoints());
        if (points <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        String market = use.getMarket() == null ? DEFAULT_MARKET : use.getMarket();
        accountMapper.refund(use.getUserNo(), market, points, now,
                now + config().inactiveDays() * 86_400_000L);

        use.setStatus(USE_REVERSED);
        ledgerMapper.updateById(use);

        PtsUserLedger back = new PtsUserLedger();
        back.setLedgerNo(BizKey.next(BizKey.POINTS_LEDGER));
        back.setUserNo(use.getUserNo());
        back.setBizType(BIZ_REFUND);
        back.setPoints(points);
        back.setBalanceAfter(loadAccount(use.getUserNo()).getBalance());
        back.setSubOrderNo(subOrderNo);
        back.setRemark(reason);
        back.setMarket(market);
        ledgerMapper.insert(back);
    }

    @Override
    @Transactional
    public long grantOnPay(String userNo, String merchantNo, long baseMinor, String subOrderNo) {
        if (baseMinor <= 0 || pointsDenyReason(merchantNo) != null) {
            return 0;
        }
        PointsConfig cfg = config();
        long points = cfg.earnFor(baseMinor);
        if (points <= 0) {
            return 0;
        }
        PtsUserAccount account = loadAccount(userNo);
        long now = System.currentTimeMillis();
        String market = account.getMarket() == null ? DEFAULT_MARKET : account.getMarket();
        long expireAt = now + cfg.inactiveDays() * 86_400_000L;

        // 新用户还没有账户行。先建再加，不能指望 UPDATE 影响 0 行时「自动创建」
        if (account.getId() == null) {
            account.setMarket(market);
            account.setLastActiveAt(now);
            account.setExpireAt(expireAt);
            account.setPendingBalance(points);
            account.setTotalEarn(points);
            accountMapper.insert(account);
        } else {
            accountMapper.grantPending(userNo, market, points, now, expireAt);
        }

        PtsUserLedger earn = new PtsUserLedger();
        earn.setLedgerNo(BizKey.next(BizKey.POINTS_LEDGER));
        earn.setUserNo(userNo);
        earn.setBizType(BIZ_EARN);
        earn.setPoints(points);
        // 待生效的分不进 balance，所以余额快照不变 —— 这一行看着奇怪，但它是对的
        earn.setBalanceAfter(account.getBalance());
        // 发放方只用于追溯与统计，不参与任何资金流动
        earn.setIssuerMerchantNo(merchantNo);
        earn.setSubOrderNo(subOrderNo);
        earn.setMarket(market);
        ledgerMapper.insert(earn);
        return points;
    }
}
