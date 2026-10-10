package ai.neargo.shop.promotion.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.dto.PeriodVOs.Decision;
import ai.neargo.shop.promotion.dto.PeriodVOs.GoodsQty;
import ai.neargo.shop.promotion.dto.PeriodVOs.PeriodDetailVO;
import ai.neargo.shop.promotion.dto.PeriodVOs.PeriodVO;
import ai.neargo.shop.promotion.dto.PeriodVOs.PickupQty;
import ai.neargo.shop.promotion.dto.PeriodVOs.PurchaseLineVO;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtPeriod;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.PeriodMapper;
import ai.neargo.shop.promotion.service.PeriodService;
import ai.neargo.shop.spi.trade.PeriodOrderPort;
import ai.neargo.shop.spi.trade.PeriodOrderPort.PeriodLine;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** {@link PeriodService} 的实现。状态机见 {@link PmtPeriod} 的类注释。 */
@Service
public class PeriodServiceImpl implements PeriodService {

    private static final Logger log = LoggerFactory.getLogger(PeriodServiceImpl.class);

    /** 截单后多久内已取消的期还要补扫退款：截单后才付款的单会落在那里（最长支付时限远小于它） */
    private static final Duration LATE_PAY_SWEEP = Duration.ofDays(3);

    static final String CANCEL_REASON = "集单未达起订量，本期取消";

    private final PeriodMapper periodMapper;
    private final ActivityMapper activityMapper;
    private final PeriodOrderPort orderPort;
    private final ai.neargo.shop.spi.marketing.PeriodPort periodPort;
    private final ai.neargo.shop.spi.product.GoodsQueryPort goodsPort;
    private final int defaultDecideHours;

    public PeriodServiceImpl(PeriodMapper periodMapper, ActivityMapper activityMapper,
                             PeriodOrderPort orderPort,
                             ai.neargo.shop.spi.marketing.PeriodPort periodPort,
                             ai.neargo.shop.spi.product.GoodsQueryPort goodsPort,
                             @Value("${shop.period.decide-hours:14}") int defaultDecideHours) {
        this.periodMapper = periodMapper;
        this.activityMapper = activityMapper;
        this.orderPort = orderPort;
        this.periodPort = periodPort;
        this.goodsPort = goodsPort;
        this.defaultDecideHours = defaultDecideHours;
    }

    @Override
    public java.util.Optional<ai.neargo.shop.spi.marketing.PeriodPort.BatchView> batchOfGoods(String goodsNo) {
        return goodsPort.snapshotOfGoods(goodsNo)
                .flatMap(g -> periodPort.viewFor(g.merchantNo(), goodsNo, System.currentTimeMillis()));
    }

    // ---------------------------------------------------------------- 查

    @Override
    public List<PeriodVO> list(String entityNo, String status) {
        List<PmtPeriod> rows = periodMapper.selectList(Wrappers.<PmtPeriod>lambdaQuery()
                .eq(PmtPeriod::getEntityNo, entityNo)
                .eq(status != null && !status.isBlank(), PmtPeriod::getStatus, status)
                .orderByDesc(PmtPeriod::getPeriodDate).orderByDesc(PmtPeriod::getId)
                .last("limit 200"));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, PmtActivity> acts = activitiesOf(rows);
        Map<String, List<PeriodLine>> lines = orderPort.lines(
                rows.stream().map(PmtPeriod::getPeriodNo).toList()).stream()
                .collect(Collectors.groupingBy(PeriodLine::periodNo));
        List<PeriodVO> out = new ArrayList<>(rows.size());
        for (PmtPeriod p : rows) {
            out.add(vo(p, acts.get(p.getActivityNo()), lines.getOrDefault(p.getPeriodNo(), List.of())));
        }
        return out;
    }

    @Override
    public PeriodDetailVO detail(String entityNo, String periodNo) {
        PmtPeriod p = require(entityNo, periodNo);
        PmtActivity a = activityOf(p.getActivityNo());
        List<PeriodLine> lines = orderPort.lines(List.of(periodNo));
        List<PeriodLine> kept = lines.stream().filter(PeriodLine::paidAndKept).toList();

        Map<String, GoodsQty> byGoods = new LinkedHashMap<>();
        Map<String, PickupQty> byPickup = new LinkedHashMap<>();
        for (PeriodLine l : kept) {
            byGoods.merge(l.goodsNo(), new GoodsQty(l.goodsNo(), l.title(), l.qty()),
                    (x, y) -> new GoodsQty(x.goodsNo(), x.title(), x.qty() + y.qty()));
            String pk = l.pickupNo() == null ? "" : l.pickupNo();
            byPickup.merge(pk, new PickupQty(l.pickupNo(), l.pickupName(), l.qty()),
                    (x, y) -> new PickupQty(x.pickupNo(), x.pickupName(), x.qty() + y.qty()));
        }
        return new PeriodDetailVO(vo(p, a, lines), List.copyOf(byGoods.values()), List.copyOf(byPickup.values()));
    }

    @Override
    public List<PurchaseLineVO> purchaseLines(String entityNo, String periodNo) {
        require(entityNo, periodNo);
        Map<String, PurchaseLineVO> bySku = new LinkedHashMap<>();
        for (PeriodLine l : orderPort.lines(List.of(periodNo))) {
            if (!l.paidAndKept()) {
                continue;
            }
            bySku.merge(l.skuNo(), new PurchaseLineVO(l.skuNo(), l.goodsNo(), l.title(), l.spec(), l.qty()),
                    (x, y) -> new PurchaseLineVO(x.skuNo(), x.goodsNo(), x.title(), x.spec(), x.qty() + y.qty()));
        }
        return List.copyOf(bySku.values());
    }

    // ---------------------------------------------------------------- 商家动作

    @Override
    public PeriodVO cutoffNow(String entityNo, String periodNo, String operatorNo) {
        PmtPeriod p = require(entityNo, periodNo);
        if (!PmtPeriod.OPEN.equals(p.getStatus())) {
            throw BizException.of(ErrorCode.PERIOD_STATE_CONFLICT);
        }
        long now = System.currentTimeMillis();
        if (p.getCutoffAt() > now) {
            // 截单时刻改写成此刻：之后下的单按 cutoff_at 判定，自然落进下一期
            int n = casUpdate(p, PmtPeriod.OPEN, w -> w.set(PmtPeriod::getCutoffAt, now));
            if (n == 0) {
                throw BizException.of(ErrorCode.PERIOD_STATE_CONFLICT);
            }
            p.setCutoffAt(now);
        }
        advance(p);
        log.info("[集单] 提前截单 {} by {}", periodNo, operatorNo);
        return reload(entityNo, periodNo);
    }

    @Override
    public PeriodVO decide(String entityNo, String periodNo, Decision action, String operatorNo) {
        PmtPeriod p = require(entityNo, periodNo);
        if (!PmtPeriod.SHORT.equals(p.getStatus()) || action == null) {
            throw BizException.of(ErrorCode.PERIOD_STATE_CONFLICT);
        }
        String to = action == Decision.PROCEED ? PmtPeriod.CONFIRMED : PmtPeriod.CANCELLED;
        long now = System.currentTimeMillis();
        int n = casUpdate(p, PmtPeriod.SHORT, w -> w.set(PmtPeriod::getStatus, to)
                .set(PmtPeriod::getDecidedBy, operatorNo)
                .set(PmtPeriod::getDecidedAt, now));
        if (n == 0) {
            // 与超时自动取消撞在同一刻：先到的赢，后到的看到的是「状态已变化」而不是自己的选择生效了
            throw BizException.of(ErrorCode.PERIOD_STATE_CONFLICT);
        }
        if (PmtPeriod.CANCELLED.equals(to)) {
            int refunds = orderPort.refundAll(periodNo, CANCEL_REASON);
            log.info("[集单] 商家取消本期 {}，发起退款 {} 张 by {}", periodNo, refunds, operatorNo);
        }
        return reload(entityNo, periodNo);
    }

    // ---------------------------------------------------------------- 任务

    @Override
    public int advanceDue(long now) {
        List<PmtPeriod> due = scoped(() -> periodMapper.selectList(Wrappers.<PmtPeriod>lambdaQuery()
                .eq(PmtPeriod::getStatus, PmtPeriod.OPEN)
                .le(PmtPeriod::getCutoffAt, now)
                .last("limit 500")));
        int n = 0;
        for (PmtPeriod p : due) {
            if (advance(p)) {
                n++;
            }
        }
        return n;
    }

    @Override
    public int cancelUndecided(long now) {
        int refunds = 0;
        List<PmtPeriod> overdue = scoped(() -> periodMapper.selectList(Wrappers.<PmtPeriod>lambdaQuery()
                .eq(PmtPeriod::getStatus, PmtPeriod.SHORT)
                .le(PmtPeriod::getDecideDeadline, now)
                .last("limit 200")));
        for (PmtPeriod p : overdue) {
            int n = casUpdate(p, PmtPeriod.SHORT, w -> w.set(PmtPeriod::getStatus, PmtPeriod.CANCELLED)
                    .set(PmtPeriod::getDecidedBy, PmtPeriod.BY_SYSTEM)
                    .set(PmtPeriod::getDecidedAt, now));
            if (n > 0) {
                refunds += orderPort.refundAll(p.getPeriodNo(), CANCEL_REASON);
                log.info("[集单] 未达起订量且超时未处理，自动取消 {}", p.getPeriodNo());
            }
        }
        /*
         * 补扫：截单后才完成支付的单（下单在截单前、付款在截单后）会挂在一个
         * 已经取消的期上。refundAll 幂等（已退、退款中的都跳过），重复扫不会退两次。
         */
        List<PmtPeriod> recent = scoped(() -> periodMapper.selectList(Wrappers.<PmtPeriod>lambdaQuery()
                .eq(PmtPeriod::getStatus, PmtPeriod.CANCELLED)
                .ge(PmtPeriod::getCutoffAt, now - LATE_PAY_SWEEP.toMillis())
                .last("limit 200")));
        for (PmtPeriod p : recent) {
            refunds += orderPort.refundAll(p.getPeriodNo(), CANCEL_REASON);
        }
        return refunds;
    }

    /**
     * OPEN → CONFIRMED / SHORT。份数只算<b>已付款且未退</b>的 —— 还在等付款的不算，
     * 否则一张 15 分钟后就会被关掉的单能让一期「看着够了」。
     *
     * @return 这一次是不是由我推进的（并发下另一边先推进了则为 false）
     */
    private boolean advance(PmtPeriod p) {
        PmtActivity a = activityOf(p.getActivityNo());
        int qty = orderPort.lines(List.of(p.getPeriodNo())).stream()
                .filter(PeriodLine::paidAndKept).mapToInt(PeriodLine::qty).sum();
        Integer min = a == null ? null : a.getMinQty();
        if (min == null || min <= 0 || qty >= min) {
            return casUpdate(p, PmtPeriod.OPEN, w -> w.set(PmtPeriod::getStatus, PmtPeriod.CONFIRMED)) > 0;
        }
        int hours = a.getDecideHours() == null || a.getDecideHours() <= 0
                ? defaultDecideHours : a.getDecideHours();
        // 时限在进入 SHORT 这一刻写死：之后商家改活动的时限，不影响已经在等的这一期
        long deadline = p.getCutoffAt() + Duration.ofHours(hours).toMillis();
        return casUpdate(p, PmtPeriod.OPEN, w -> w.set(PmtPeriod::getStatus, PmtPeriod.SHORT)
                .set(PmtPeriod::getDecideDeadline, deadline)) > 0;
    }

    // ---------------------------------------------------------------- 内部

    /** 带状态条件的更新：job 与商家同时动同一期时，只有一边生效 */
    private int casUpdate(PmtPeriod p, String fromStatus,
                          java.util.function.UnaryOperator<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PmtPeriod>> set) {
        var w = set.apply(Wrappers.<PmtPeriod>lambdaUpdate()
                .eq(PmtPeriod::getPeriodNo, p.getPeriodNo())
                .eq(PmtPeriod::getStatus, fromStatus));
        return scoped(() -> periodMapper.update(null, w));
    }

    private PmtPeriod require(String entityNo, String periodNo) {
        PmtPeriod p = periodMapper.selectOne(Wrappers.<PmtPeriod>lambdaQuery()
                .eq(PmtPeriod::getPeriodNo, periodNo)
                .eq(PmtPeriod::getEntityNo, entityNo)
                .last("limit 1"));
        if (p == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return p;
    }

    private PeriodVO reload(String entityNo, String periodNo) {
        return detail(entityNo, periodNo).period();
    }

    private PmtActivity activityOf(String activityNo) {
        return scoped(() -> activityMapper.selectOne(Wrappers.<PmtActivity>lambdaQuery()
                .eq(PmtActivity::getActivityNo, activityNo).last("limit 1")));
    }

    private Map<String, PmtActivity> activitiesOf(Collection<PmtPeriod> rows) {
        List<String> nos = rows.stream().map(PmtPeriod::getActivityNo).distinct().toList();
        Map<String, PmtActivity> out = new HashMap<>();
        scoped(() -> activityMapper.selectList(Wrappers.<PmtActivity>lambdaQuery()
                .in(PmtActivity::getActivityNo, nos))).forEach(a -> out.put(a.getActivityNo(), a));
        return out;
    }

    private static PeriodVO vo(PmtPeriod p, PmtActivity a, List<PeriodLine> lines) {
        List<PeriodLine> kept = lines.stream().filter(PeriodLine::paidAndKept).toList();
        int qty = kept.stream().mapToInt(PeriodLine::qty).sum();
        long amount = kept.stream().mapToLong(PeriodLine::amountMinor).sum();
        int customers = (int) kept.stream().map(PeriodLine::userNo).distinct().count();
        return new PeriodVO(p.getPeriodNo(), p.getActivityNo(), a == null ? null : a.getName(),
                p.getPeriodDate(), p.getCutoffAt() == null ? 0L : p.getCutoffAt(),
                p.getPickupDate(), a == null ? null : a.getPickupFrom(),
                p.getStatus(), qty, customers, amount,
                a == null ? null : a.getMinQty(), a == null ? null : a.getPeriodQuota(),
                p.getDecideDeadline());
    }

    /**
     * ★ 绕数据域：任务没有会话，而 pmt_period 按 entity_no 登记了 MERCHANT 维度 ——
     * 不绕的话任务里查出来恒为空，于是没有一期会被截单，也没有一期会被取消退款。
     * 边界靠显式条件：任务按状态与时刻扫全量（这正是任务的本意），商家动作走 require 钉死 entity_no。
     */
    private static <T> T scoped(java.util.function.Supplier<T> action) {
        return DataScopeContext.executeWithoutScope(action::get);
    }
}
