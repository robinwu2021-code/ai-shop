package ai.neargo.shop.promotion.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtActivityGoods;
import ai.neargo.shop.promotion.entity.PmtPeriod;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityGoodsMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.PeriodMapper;
import ai.neargo.shop.spi.marketing.PeriodPort;
import ai.neargo.shop.spi.trade.PeriodOrderPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * {@link PeriodPort} 的实现：下单时给出「这一单属于哪一期」。
 *
 * <p><b>归属只看两个时刻</b>：下单时刻与这一期的截单时刻。不看定时任务跑没跑到 ——
 * 19:59:59 下的单属于今天，哪怕任务 20:00:30 才扫到这一期。
 */
@Component
public class PeriodPortImpl implements PeriodPort {

    /** 市场时区。与 {@code GroupRulePortImpl.ZONE} 同一个值：「今晚 8 点截单」按店所在地算 */
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 活动没配提货偏移时：次日提货（PRD §4.3.5 的典型形态） */
    private static final int DEFAULT_PICKUP_OFFSET = 1;

    /** 往后找几期：今天这期已被商家提前截单时，下一单落到明天；再往后就不该发生 */
    private static final int MAX_ROLL = 3;

    private final ActivityMapper activityMapper;
    private final ActivityGoodsMapper goodsMapper;
    private final PeriodMapper periodMapper;
    private final PeriodOrderPort orderPort;

    public PeriodPortImpl(ActivityMapper activityMapper, ActivityGoodsMapper goodsMapper,
                          PeriodMapper periodMapper, PeriodOrderPort orderPort) {
        this.activityMapper = activityMapper;
        this.goodsMapper = goodsMapper;
        this.periodMapper = periodMapper;
        this.orderPort = orderPort;
    }

    @Override
    public Optional<PeriodTicket> ticketFor(String entityNo, Collection<String> goodsNos, int qty, long orderAt) {
        if (entityNo == null || goodsNos == null || goodsNos.isEmpty()) {
            return Optional.empty();
        }
        /*
         * ★ 绕数据域：下单是**买家**会话，pmt_activity / pmt_period 按商家登记了 MERCHANT 维度 ——
         * 不绕的话恒为空，集单商品全按普通单处理（提货日错一天、不进任何一期），且零报错。
         * 边界靠显式 entityNo 条件：这里只查这一家的活动。
         */
        List<PmtActivity> live = liveCutoff(entityNo, goodsNos, orderAt);
        if (live.isEmpty()) {
            return Optional.empty();
        }
        if (live.size() > 1) {
            throw BizException.of(ErrorCode.PERIOD_MIXED);
        }
        PmtActivity a = live.get(0);

        LocalDate day = Instant.ofEpochMilli(orderAt).atZone(ZONE).toLocalDate();
        for (int i = 0; i < MAX_ROLL; i++) {
            long cutoffAt = cutoffOf(a, day);
            if (orderAt >= cutoffAt) {
                day = day.plusDays(1);
                continue;
            }
            if (a.getEndAt() != null && cutoffAt > a.getEndAt()) {
                // 下一期已经在活动结束之后：这件货此刻不在任何一期里，按普通单
                return Optional.empty();
            }
            PmtPeriod p = getOrCreate(a, day, cutoffAt);
            if (!PmtPeriod.OPEN.equals(p.getStatus()) || orderAt >= nz(p.getCutoffAt())) {
                // 这一期被商家提前截单了：落到下一期
                day = day.plusDays(1);
                continue;
            }
            assertRoom(a, p, qty);
            return Optional.of(new PeriodTicket(p.getPeriodNo(), a.getActivityNo(),
                    p.getPickupDate(), nz(p.getCutoffAt())));
        }
        return Optional.empty();
    }

    @Override
    public boolean isCutOff(String periodNo, long now) {
        if (periodNo == null) {
            return true;
        }
        PmtPeriod p = scoped(() -> periodMapper.selectOne(Wrappers.<PmtPeriod>lambdaQuery()
                .eq(PmtPeriod::getPeriodNo, periodNo).last("limit 1")));
        return p == null || !PmtPeriod.OPEN.equals(p.getStatus()) || now >= nz(p.getCutoffAt());
    }

    @Override
    public Long openUntil(String periodNo, long now) {
        if (periodNo == null) {
            return null;
        }
        PmtPeriod p = scoped(() -> periodMapper.selectOne(Wrappers.<PmtPeriod>lambdaQuery()
                .eq(PmtPeriod::getPeriodNo, periodNo).last("limit 1")));
        if (p == null || !PmtPeriod.OPEN.equals(p.getStatus()) || now >= nz(p.getCutoffAt())) {
            return null;
        }
        return p.getCutoffAt();
    }

    @Override
    public Optional<BatchView> viewFor(String entityNo, String goodsNo, long now) {
        if (entityNo == null || goodsNo == null) {
            return Optional.empty();
        }
        List<PmtActivity> live;
        try {
            live = liveCutoff(entityNo, List.of(goodsNo), now);
        } catch (BizException e) {
            return Optional.empty();
        }
        if (live.size() != 1) {
            return Optional.empty();
        }
        PmtActivity a = live.get(0);
        LocalDate day = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate();
        for (int i = 0; i < MAX_ROLL; i++) {
            long cutoffAt = cutoffOf(a, day);
            if (now >= cutoffAt) {
                day = day.plusDays(1);
                continue;
            }
            if (a.getEndAt() != null && cutoffAt > a.getEndAt()) {
                return Optional.empty();
            }
            PmtPeriod p = find(a.getActivityNo(), day);
            if (p != null && (!PmtPeriod.OPEN.equals(p.getStatus()) || now >= nz(p.getCutoffAt()))) {
                day = day.plusDays(1);
                continue;
            }
            int offset = a.getPickupOffset() == null || a.getPickupOffset() < 0
                    ? DEFAULT_PICKUP_OFFSET : a.getPickupOffset();
            int ordered = p == null ? 0 : orderPort.lines(List.of(p.getPeriodNo())).stream()
                    .filter(PeriodOrderPort.PeriodLine::paidAndKept)
                    .mapToInt(PeriodOrderPort.PeriodLine::qty).sum();
            return Optional.of(new BatchView(a.getActivityNo(), a.getName(),
                    a.getBenefitAmountMinor() == null ? 0L : a.getBenefitAmountMinor(),
                    p == null ? cutoffAt : nz(p.getCutoffAt()),
                    p == null ? day.plusDays(offset).toString() : p.getPickupDate(),
                    a.getPickupFrom(), ordered));
        }
        return Optional.empty();
    }

    /** 这家、这几件货此刻在跑的集单活动。多于一个时由调用方决定是拒还是忽略 */
    private List<PmtActivity> liveCutoff(String entityNo, Collection<String> goodsNos, long at) {
        List<String> activityNos = scoped(() -> goodsMapper.selectList(Wrappers.<PmtActivityGoods>lambdaQuery()
                        .eq(PmtActivityGoods::getEntityNo, entityNo)
                        .eq(PmtActivityGoods::getScopeType, PmtActivityGoods.GOODS)
                        .in(PmtActivityGoods::getRefNo, goodsNos)))
                .stream().map(PmtActivityGoods::getActivityNo).distinct().toList();
        if (activityNos.isEmpty()) {
            return List.of();
        }
        return scoped(() -> activityMapper.selectList(Wrappers.<PmtActivity>lambdaQuery()
                        .eq(PmtActivity::getEntityNo, entityNo)
                        .in(PmtActivity::getActivityNo, activityNos)
                        .eq(PmtActivity::getTriggerType, PmtActivity.TRIGGER_CUTOFF)
                        .eq(PmtActivity::getStatus, PmtActivity.RUNNING)))
                .stream().filter(a -> a.getStartAt() == null || a.getStartAt() <= at).toList();
    }

    private void assertRoom(PmtActivity a, PmtPeriod p, int qty) {
        if (a.getPeriodQuota() == null || a.getPeriodQuota() <= 0) {
            return;
        }
        int held = orderPort.lines(List.of(p.getPeriodNo())).stream()
                .filter(PeriodOrderPort.PeriodLine::holdsQuota)
                .mapToInt(PeriodOrderPort.PeriodLine::qty).sum();
        if (held + qty > a.getPeriodQuota()) {
            throw BizException.of(ErrorCode.PERIOD_FULL);
        }
    }

    private PmtPeriod getOrCreate(PmtActivity a, LocalDate day, long cutoffAt) {
        PmtPeriod found = find(a.getActivityNo(), day);
        if (found != null) {
            return found;
        }
        PmtPeriod p = new PmtPeriod();
        p.setPeriodNo(BizKey.next(BizKey.PROMO_PERIOD));
        p.setActivityNo(a.getActivityNo());
        p.setEntityNo(a.getEntityNo());
        p.setPeriodDate(day.toString());
        p.setCutoffAt(cutoffAt);
        int offset = a.getPickupOffset() == null || a.getPickupOffset() < 0
                ? DEFAULT_PICKUP_OFFSET : a.getPickupOffset();
        p.setPickupDate(day.plusDays(offset).toString());
        p.setStatus(PmtPeriod.OPEN);
        try {
            scoped(() -> periodMapper.insert(p));
            return p;
        } catch (DuplicateKeyException race) {
            // 两单同时建同一期：唯一键 (tenant_no, activity_no, period_date) 让后到的一方读回先到的那一行
            return find(a.getActivityNo(), day);
        }
    }

    private PmtPeriod find(String activityNo, LocalDate day) {
        return scoped(() -> periodMapper.selectOne(Wrappers.<PmtPeriod>lambdaQuery()
                .eq(PmtPeriod::getActivityNo, activityNo)
                .eq(PmtPeriod::getPeriodDate, day.toString())
                .last("limit 1")));
    }

    /** 这一天的截单时刻。活动没配截单时刻的不会走到这里（建活动时拦住了） */
    static long cutoffOf(PmtActivity a, LocalDate day) {
        LocalTime t = LocalTime.parse(a.getCutoffTime());
        return day.atTime(t).atZone(ZONE).toInstant().toEpochMilli();
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static <T> T scoped(java.util.function.Supplier<T> action) {
        return DataScopeContext.executeWithoutScope(action::get);
    }
}
