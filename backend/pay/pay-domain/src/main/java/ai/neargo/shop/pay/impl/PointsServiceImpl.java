package ai.neargo.shop.pay.impl;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.PayScenes;
import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.pay.PointsConfig;
import ai.neargo.shop.pay.PointsService;
import ai.neargo.shop.pay.dto.PointsVOs.MerchantPointAccountVO;
import ai.neargo.shop.pay.dto.PointsVOs.MerchantPointsRecordVO;
import ai.neargo.shop.pay.dto.PointsVOs.PointAccountVO;
import ai.neargo.shop.pay.dto.PointsVOs.PointRecordVO;
import ai.neargo.shop.pay.dto.PointsVOs.PointsDeductibleVO;
import ai.neargo.shop.pay.dto.PointsVOs.PointsOverviewVO;
import ai.neargo.shop.pay.dto.PointsVOs.PoolByChannelVO;
import ai.neargo.shop.pay.entity.PtsUserAccount;
import ai.neargo.shop.pay.entity.PtsUserLedger;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlPointsPool;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.spi.settle.PointsPort;
import ai.neargo.shop.pay.mapper.SettleMappers.PointsAccountMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PointsLedgerMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PointsPoolMapper;
import ai.neargo.shop.pay.setting.PaySettingService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import tools.jackson.databind.ObjectMapper;
import ai.neargo.common.data.scope.DataScopeContext;
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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PointsServiceImpl.class);

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMM");


    private final PointsAccountMapper accountMapper;
    private final PointsLedgerMapper ledgerMapper;
    private final PointsPoolMapper poolMapper;
    private final BillMapper billMapper;
    private final MerchantQueryPort merchantQuery;
    /** 支付域自己的设置（2026-09-01 从 sys_setting 搬过来，见 V285） */
    private final PaySettingService paySettings;
    private final ObjectMapper json = new ObjectMapper();

    public PointsServiceImpl(PointsAccountMapper accountMapper,
                             PointsLedgerMapper ledgerMapper,
                             PointsPoolMapper poolMapper,
                             BillMapper billMapper,
                             MerchantQueryPort merchantQuery,
                             PaySettingService paySettings) {
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
        this.poolMapper = poolMapper;
        this.billMapper = billMapper;
        this.merchantQuery = merchantQuery;
        this.paySettings = paySettings;
    }

    /**
     * 读积分参数。<b>每次都读</b>而不是缓存：运营改了汇率要立刻生效，
     * 而这条读的是 {@code sys_setting}（本来就带缓存），不值得再包一层。
     *
     * <p>解析失败退回默认值 —— 一行配置写坏了不该让所有人下不了单。
     */
    private PointsConfig config() {
        try {
            return json.readValue(paySettings.get(PointsConfig.KEY, PointsConfig.DEFAULT_JSON),
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
    public PointsDeductibleVO deductible(String userNo, String merchantNo, long payableMinor,
                                        String payMode, String clientType) {
        /*
         * 与下单时**调同一个方法**。分成两处判的话，
         * 「结算页说能抵 30、下单只抵了 25」是迟早的事 ——
         * 而这次多了端与支付方式两个维度，两处走岔的机会只会更多。
         */
        PointsAvailability av = canRedeem(userNo, merchantNo, payMode, clientType);
        if (!av.allowed()) {
            return new PointsDeductibleVO(0, 0, loadAccount(userNo).getBalance(), av.reason());
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


    /**
     * 一行该发多少分。<b>规则从 product 域取，兜底在本域</b>。
     *
     * <p>「配了 0」与「没配」是两件事：前者 Port 会返回 {@code EarnRule(FIXED, 0)}，
     * 这里如实发 0；后者返回空，才落到平台兜底比例。
     * 把两者混为一谈的话，储值卡那种「明确要发 0 分」的商品会拿到兜底的非 0 值。
     */
    private long pointsForLine(ai.neargo.shop.spi.settle.PointsPort.EarnLine line, PointsConfig cfg) {
        if (line.baseMinor() <= 0) {
            return 0;
        }
        /*
         * **规则由调用方传进来**（2026-09-01 · M9），支付域不回查 product。
         * 它是「这一行配了什么」，下单那一刻就确定了，
         * 而支付域没有任何理由去问一次商品配置 —— 那是反着的依赖。
         */
        var r = line.rule();
        if (r == null) {
            return cfg.earnFor(line.baseMinor());
        }
        if (PointsPort.FIXED.equals(r.mode())) {
            return Math.max(0, r.value());
        }
        // 万分比：**整数运算**，不用浮点 —— 对账时的分位差没人说得清
        return r.value() <= 0 ? 0 : Math.max(0, line.baseMinor() * r.value() / 10_000);
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

    @Override
    public IdentityCheck checkIdentity(String market) {
        String mkt = market == null || market.isBlank() ? DEFAULT_MARKET : market;

        long circulating = DataScopeContext.executeWithoutScope(() ->
                        accountMapper.selectList(Wrappers.<PtsUserAccount>lambdaQuery()
                                .eq(PtsUserAccount::getMarket, mkt)))
                .stream().mapToLong(a -> nz(a.getBalance()) + nz(a.getPendingBalance())).sum();

        /*
         * **PENDING 的抵扣必须算进「还欠着的钱」。**
         *
         * 下单扣分之后、兑付成立之前，那笔钱已经不在用户账上（余额扣了），
         * 也还没付给收单方（池子没动）—— 它正躺在池子里等着。
         *
         * 漏掉这一项，等式会在**每个未结算的订单**上都差一截，
         * 告警天天响，也就等于没有告警。
         */
        long pendingUse = DataScopeContext.executeWithoutScope(() ->
                        ledgerMapper.selectList(Wrappers.<PtsUserLedger>lambdaQuery()
                                .eq(PtsUserLedger::getBizType, BIZ_USE)
                                .eq(PtsUserLedger::getStatus, USE_PENDING)
                                .eq(PtsUserLedger::getMarket, mkt)))
                .stream().mapToLong(l -> nz(l.getAmountMinor())).sum();

        long pool = DataScopeContext.executeWithoutScope(() ->
                        poolMapper.selectList(Wrappers.<StlPointsPool>lambdaQuery()
                                .eq(StlPointsPool::getMarket, mkt)))
                .stream()
                .mapToLong(f -> StlPointsPool.IN.equals(f.getDirection())
                        ? nz(f.getAmountMinor()) : -nz(f.getAmountMinor()))
                .sum();

        return new IdentityCheck(mkt, circulating,
                config().toMinor(circulating) + pendingUse, pool, pendingUse);
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
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

    // ------------------------------------------------------------ 端开关

    /** 平台端策略的存放键。与积分参数分开：改端策略不该动汇率与上限。 */
    private static final String POLICY_KEY = "points.client.policy";
    /** 默认<b>什么都不禁</b>：这一批上线不改变任何现有行为。 */
    private static final String POLICY_DEFAULT =
            "{\"earnDeny\":[],\"redeemDeny\":[],\"offlineRedeem\":true}";

    @Override
    public PointsAvailability canRedeem(String userNo, String merchantNo,
                                        String payMode, String clientType) {
        // ① 商家 / 社区 / 主体那一串既有判定，原样复用
        String denied = pointsDenyReason(merchantNo);
        if (denied != null) {
            return PointsAvailability.no(denied);
        }
        ClientPointsPolicy p = policy();
        // ② 线下支付。**默认允许** —— 积分成本本来就在商家（ADR-006），
        //    线下反而比线上简单：商家当面少收即是抵扣，平台零动作
        if (PayModes.OFFLINE.equals(payMode) && !p.offlineRedeem()) {
            return PointsAvailability.no("当面付款暂不支持积分抵扣");
        }
        // ③ 端。认不出来即放行 —— 见 ClientPointsPolicy 的注释
        String scene = PayScenes.normalize(clientType);
        if (scene != null && p.redeemDeny().contains(scene)) {
            return PointsAvailability.no("当前端暂不支持积分抵扣");
        }
        return PointsAvailability.ok();
    }

    @Override
    public PointsAvailability canEarn(String subOrderNo, String payChannel, String payScene) {
        /*
         * ⚠️ **线下（当面）收款的单不发积分**，这不是策略开关，是一条算得清的账。
         *
         * 发分要向商家收费用金进积分池 —— 那笔钱是**将来用户在别家花掉这些分时，
         * 平台要付给收单方的钱**。线上单靠分账扣走；自营单从平台欠商家的货款里净出来
         * （财务按 net_minor 打款，而 net 已经减过费用金）。
         *
         * 线下单两条路都没有：钱在商家自己口袋里，平台不欠他任何款，
         * **这笔费用金收不到**。照发不误的话，池子账面上会挂着一笔永远不会到的钱，
         * 而恒等式（池子余额 == 流通中积分 × 汇率）会随线下成交量单调失衡 ——
         * 且账面总额看着一直是平的，只有对账时逐笔查才发现。
         *
         * 不做成开关是刻意的：做成开关就会有人打开它，而打开它没有对应的收款机制。
         * 要支持的话得先有「按商家挂应收、下次线上结算净出来」那一套，那是另一件事。
         */
        if (PayModes.OFFLINE.equals(payChannel)) {
            return PointsAvailability.no("当面付款的订单不发放积分");
        }
        /*
         * ⚠️ 这里读的是**订单快照**，不是当前请求。
         *
         * 发放发生在支付/完成那一刻，而那时用户可能已经换了端，
         * 更常见的是根本没有用户在场（超时自动确认收货是系统动作）。
         * 读当前端会让「这单发不发积分」取决于谁在哪个端点的确认、
         * 甚至取决于是不是定时任务跑的 —— 不可复现也无法对账。
         */
        String scene = PayScenes.normalize(payScene);
        if (scene != null && policy().earnDeny().contains(scene)) {
            return PointsAvailability.no("当前端暂不发放积分");
        }
        return PointsAvailability.ok();
    }

    /**
     * 读端策略。**解析不了就当没配**：参数表里一行脏数据不该让全站积分停摆，
     * 也不该让它反过来全开 —— 默认值恰好是「什么都不禁」，两种取向在这里重合。
     */
    private ClientPointsPolicy policy() {
        try {
            ClientPointsPolicy p = json.readValue(
                    paySettings.get(POLICY_KEY, POLICY_DEFAULT), ClientPointsPolicy.class);
            return new ClientPointsPolicy(
                    p.earnDeny() == null ? List.of() : p.earnDeny(),
                    p.redeemDeny() == null ? List.of() : p.redeemDeny(),
                    p.offlineRedeem());
        } catch (Exception e) {
            log.warn("端策略解析失败，按「什么都不禁」处理：{}", e.toString());
            return new ClientPointsPolicy(List.of(), List.of(), true);
        }
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
    /** 兑付成立 —— 平台已把钱付给收单商家，此后不可再退回池子 */
    private static final String USE_CONFIRMED = "CONFIRMED";

    @Override
    @Transactional("payTxManager")
    public DeductResult deductOnPlace(String userNo, long wantPoints, List<DeductTarget> targets,
                                     String payMode, String clientType) {
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
        // 闸一：商家开关 + 端 + 支付方式。有一家不可用就整单不抵 ——
        // 分摊到不可用的那家会让它凭空少收钱。
        // **与 deductible 同一个 canRedeem**：两处走岔就会出现「说能抵 30、只抵了 25」
        for (DeductTarget t : targets) {
            if (!canRedeem(userNo, t.merchantNo(), payMode, clientType).allowed()) {
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
    @Transactional("payTxManager")
    public int confirmDeduction(String subOrderNo) {
        PtsUserLedger use = ledgerMapper.selectOne(Wrappers.<PtsUserLedger>lambdaQuery()
                .eq(PtsUserLedger::getSubOrderNo, subOrderNo)
                .eq(PtsUserLedger::getBizType, BIZ_USE)
                // **只挑 PENDING**。已 REVERSED 的（退款退回过）不能被重新确认 ——
                // 按子单号覆写的写法会把退过的单又付一遍钱，而账面看不出异常
                .eq(PtsUserLedger::getStatus, USE_PENDING)
                .last("LIMIT 1"));
        if (use == null) {
            // 没用积分、或已确认、或已退回：都是静默返回 0。
            // 结算链路会对同一单调多次（分账重试、账单重推），报错会让结算失败
            return 0;
        }
        long amountMinor = use.getAmountMinor() == null ? 0L : use.getAmountMinor();

        use.setStatus(USE_CONFIRMED);
        ledgerMapper.updateById(use);

        /*
         * 出池：平台把这笔钱付给收单商家。**到这一步池子才真的减少** ——
         * 此前 USE 只是预占（订单还可能取消或退款），池子一分没动。
         *
         * 不记这一笔的话，池子只进不出，「流通中的积分 == 池子里的钱」
         * 这条恒等式会随成交量单调失衡 —— 与 EXPIRE_INCOME 缺失时是同一个病。
         */
        recordPoolFlow(StlPointsPool.MERCHANT_PAY, amountMinor,
                use.getAcceptorMerchantNo(), subOrderNo, null, use.getMarket());
        return 1;
    }

    @Override
    @Transactional("payTxManager")
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
    @Transactional("payTxManager")
    public void recordPoolFlow(String poolType, long amountMinor, String entityNo,
                               String refNo, String payChannel, String market) {
        if (amountMinor <= 0) {
            // 0 或负数不入账。**方向由 poolType 决定，不靠符号** ——
            // 两处表达同一件事，迟早对不上，而对不上时恒等式不会告诉你是哪一笔
            return;
        }
        String mkt = market == null || market.isBlank() ? DEFAULT_MARKET : market;
        String direction = StlPointsPool.MERCHANT_RECEIVE.equals(poolType)
                ? StlPointsPool.IN : StlPointsPool.OUT;

        StlPointsPool f = new StlPointsPool();
        f.setFlowNo(BizKey.next(BizKey.POINTS_POOL));
        f.setDirection(direction);
        f.setPoolType(poolType);
        f.setAmountMinor(amountMinor);
        f.setEntityNo(entityNo);
        f.setRefNo(refNo);
        f.setMarket(mkt);
        f.setPayChannel(payChannel);
        f.setPeriod(java.time.LocalDate.now().toString().substring(0, 7));
        /*
         * balance_after 落快照。逐笔重算的代价不是性能，是**对不上时说不清**：
         * 中间少了一笔的话，只有快照能指出断点在哪一行。
         */
        f.setBalanceAfter(poolBalanceOf(mkt, payChannel)
                + (StlPointsPool.IN.equals(direction) ? amountMinor : -amountMinor));
        DataScopeContext.executeWithoutScope(() -> poolMapper.insert(f));
    }

    /** 某通道账户当前的池子余额。IN 加 OUT 减 —— 与 overview 同一口径 */
    private long poolBalanceOf(String market, String payChannel) {
        return DataScopeContext.executeWithoutScope(() ->
                        poolMapper.selectList(Wrappers.<StlPointsPool>lambdaQuery()
                                .eq(StlPointsPool::getMarket, market)
                                .eq(StlPointsPool::getPayChannel, payChannel)))
                .stream()
                .mapToLong(x -> StlPointsPool.IN.equals(x.getDirection())
                        ? x.getAmountMinor() : -x.getAmountMinor())
                .sum();
    }

    @Override
    @Transactional("payTxManager")
    public int expireIdleAccounts() {
        long now = System.currentTimeMillis();
        List<PtsUserAccount> idle = DataScopeContext.executeWithoutScope(() ->
                accountMapper.selectList(Wrappers.<PtsUserAccount>lambdaQuery()
                        .isNotNull(PtsUserAccount::getExpireAt)
                        .le(PtsUserAccount::getExpireAt, now)
                        // 已经是 0 的不用再清 —— 否则每天都会为同一批空账户写一条 0 分流水
                        .gt(PtsUserAccount::getBalance, 0)
                        .last("LIMIT 500")));

        int cleared = 0;
        for (PtsUserAccount a : idle) {
            long amount = a.getBalance() == null ? 0L : a.getBalance();
            String market = a.getMarket() == null ? DEFAULT_MARKET : a.getMarket();

            /*
             * 先记流水再清余额。顺序反了的话，中间崩一次就只剩「余额没了」
             * 而没有任何一条记录解释得了它去哪了 —— 而积分是用户看得见的东西。
             *
             * points 带符号：EXPIRE 记负数（与 USE/REVOKE 同一约定）。
             */
            PtsUserLedger row = new PtsUserLedger();
            row.setLedgerNo(BizKey.next(BizKey.POINTS_LEDGER));
            row.setUserNo(a.getUserNo());
            row.setBizType(PtsUserLedger.EXPIRE);
            row.setPoints(-amount);
            row.setBalanceAfter(0L);
            row.setMarket(market);
            DataScopeContext.executeWithoutScope(() -> ledgerMapper.insert(row));

            a.setBalance(0L);
            DataScopeContext.executeWithoutScope(() -> accountMapper.updateById(a));

            /*
             * **池子侧同步转收入。** 不记这一笔的话池子只增不减 ——
             * 用户那边的分没了，池子里对应的钱还挂着，恒等式永久失衡，
             * 且失衡量随时间单调增长。这是 EXPIRE_INCOME 这个类型存在的全部理由。
             */
            recordPoolFlow(StlPointsPool.EXPIRE_INCOME, amount, null,
                    row.getLedgerNo(), null, market);
            cleared++;
        }
        return cleared;
    }

    @Override
    @Transactional("payTxManager")
    public int activateDuePoints() {
        long now = System.currentTimeMillis();
        /*
         * 只扫「到点且未转正」的 EARN 行。
         *
         * **幂等靠 activated_at，不靠余额守卫兜底** —— 只按时间判的话，
         * 每天扫到的都是同一批已转过的行，第二次转正被「pending 不足」拦下、
         * 返回 0 行、任务当成没事发生，**不报错也永远不知道自己在空转**。
         */
        List<PtsUserLedger> due = DataScopeContext.executeWithoutScope(() ->
                ledgerMapper.selectList(Wrappers.<PtsUserLedger>lambdaQuery()
                        .eq(PtsUserLedger::getBizType, BIZ_EARN)
                        .isNull(PtsUserLedger::getActivatedAt)
                        .isNotNull(PtsUserLedger::getAvailableAt)
                        .le(PtsUserLedger::getAvailableAt, now)
                        // 一次别捞太多：这条链上每行都要改账户余额，
                        // 一个超大事务卡住的是所有人的下单
                        .last("LIMIT 500")));
        int activated = 0;
        for (PtsUserLedger row : due) {
            String market = row.getMarket() == null ? DEFAULT_MARKET : row.getMarket();
            int moved = accountMapper.activatePending(row.getUserNo(), market, row.getPoints(), now);
            if (moved == 0) {
                /*
                 * pending 不够。**不是可以忽略的情况** —— 说明发放与转正的账已经对不上，
                 * 而这条行会被反复扫到。标记掉并留日志，让它变成一条可查的记录，
                 * 而不是每天在任务里空转一次。
                 */
                log.warn("积分转正跳过：pending 不足 user={} ledger={} points={}",
                        row.getUserNo(), row.getLedgerNo(), row.getPoints());
            } else {
                activated++;
            }
            row.setActivatedAt(now);
            DataScopeContext.executeWithoutScope(() -> ledgerMapper.updateById(row));
        }
        return activated;
    }

    @Override
    @Transactional("payTxManager")
    public ai.neargo.shop.spi.settle.PointsPort.GrantResult grantOnPay(
            String userNo, String merchantNo,
            java.util.List<ai.neargo.shop.spi.settle.PointsPort.EarnLine> lines, String subOrderNo,
            String payChannel, String payScene) {
        /*
         * **自己的幂等，不再只靠调用方的 ord_sub_order.points_granted 标记。**
         *
         * 那个标记与流水由**两个不同的事务**写：标记跟着订单走，流水跟着积分走。
         * 发分推迟到订单事务提交之后（AfterCommit）之后，两者之间就有了一个真实窗口：
         * 流水写成了而标记没写上 → 重试进来时标记还是 false → **同一个子单发两次分**。
         *
         * 有了这一条，重试是安全的：已经有 EARN 流水就直接返回既有结果，
         * 于是「不变式巡检把标记清掉让它重发」这条修复路径也不会多发。
         *
         * <p>返回值要与首次一致（分数与费用金），否则调用方写回子单的
         * points_fee_minor 会与流水对不上，而那笔费用金是结算时真的要扣的钱。
         *
         * <p><b>放在最前面，在「商家开没开积分」之前</b>：已经发出去的分是既成事实，
         * 商家事后关掉积分不该让它查不回来。放在后面的话，那种情况下重试会返回
         * {@code none()} → 调用方什么都不写 → 标记停在 false 而流水在，
         * 落进「有流水而标记为假」那一类 —— 而那一类<b>刻意不自动修</b>，要人看。
         */
        PtsUserLedger existing = DataScopeContext.executeWithoutScope(() ->
                ledgerMapper.selectOne(Wrappers.<PtsUserLedger>lambdaQuery()
                        .eq(PtsUserLedger::getSubOrderNo, subOrderNo)
                        .eq(PtsUserLedger::getBizType, BIZ_EARN)
                        .last("limit 1")));
        if (existing != null) {
            long p = existing.getPoints() == null ? 0L : existing.getPoints();
            return new ai.neargo.shop.spi.settle.PointsPort.GrantResult(p, config().toMinor(p));
        }
        if (lines == null || lines.isEmpty() || pointsDenyReason(merchantNo) != null) {
            return ai.neargo.shop.spi.settle.PointsPort.GrantResult.none();
        }
        /*
         * 端闸。**读订单快照，不读当前请求** —— canEarn 的参数只有子单号，
         * 想读错也读不到（见 OrderSceneQueryPort 的类注释）。
         *
         * 位置在商家开关之后：那一道更便宜，也更常命中。
         */
        if (!canEarn(subOrderNo, payChannel, payScene).allowed()) {
            return ai.neargo.shop.spi.settle.PointsPort.GrantResult.none();
        }
        PointsConfig cfg = config();
        /*
         * **按行算再汇总，不是按子单取一个类目算整单。**
         *
         * 积分规则按二级类目配，而一个子单可以有多件不同类目的商品。
         * 按整单取一个类目在多类目子单上必然算错 —— 而且错得看不出来：
         * 总数看着永远是个合理的数字，只有逐行对账才发现口径不对。
         *
         * 逐行取规则走 PointsRuleResolver（三层优先级的唯一入口）。
         */
        long points = 0;
        for (var line : lines) {
            points += pointsForLine(line, cfg);
        }
        long baseMinor = lines.stream()
                .mapToLong(ai.neargo.shop.spi.settle.PointsPort.EarnLine::baseMinor).sum();
        if (points <= 0) {
            return ai.neargo.shop.spi.settle.PointsPort.GrantResult.none();
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
        /*
         * **可用时间。此前这一行不存在，整条积分链因此是断的。**
         *
         * 后果链：available_at 恒 NULL → 转正任务扫不到任何行 → balance 恒 0
         * → maxUsablePoints 算出 0 → 抵扣永远抵不了。
         * 用户看得见分在涨（pending_balance），却一分也花不出去，
         * 而 account() 的 nextActivate 也恒返回 null —— 页面连「何时生效」都显示不了。
         *
         * 全链路没有任何一处报错。
         */
        earn.setAvailableAt(now + cfg.pendingDays() * 86_400_000L);
        // 发放方只用于追溯与统计，不参与任何资金流动
        earn.setIssuerMerchantNo(merchantNo);
        earn.setSubOrderNo(subOrderNo);
        earn.setMarket(market);
        ledgerMapper.insert(earn);

        /*
         * 费用金 **1:1**，不打折不加价。
         *
         * 这些分将来可能被用户在**别家**花掉，那时平台要从池子里付给收单方 ——
         * 收的比将来要付的少，恒等式 2（池子余额 == 流通中积分 × 汇率）当场不成立，
         * 而失衡量会随发放量单调增长，与此前 EXPIRE_INCOME 缺失时是同一个病。
         *
         * **这里只算定，不入池**：钱还没到手（货款要到结算才扣）。
         * 入池发生在 SettleServiceImpl 建单时，与货款扣减同一时刻 ——
         * 提前入池等于池子里记着一笔还没收到的钱。
         */
        return new ai.neargo.shop.spi.settle.PointsPort.GrantResult(points, cfg.toMinor(points));
    }
}
