package ai.neargo.shop.promotion.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.promotion.dto.PeriodVOs.PeriodVO;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtApply;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.entity.PmtPeriod;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ApplyMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponMapper;
import ai.neargo.shop.promotion.service.MarketingSummaryService;
import ai.neargo.shop.promotion.service.PeriodService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/** {@link MarketingSummaryService} 的实现。 */
@Service
public class MarketingSummaryServiceImpl implements MarketingSummaryService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ApplyMapper applyMapper;
    private final ActivityMapper activityMapper;
    private final CouponMapper couponMapper;
    private final PeriodService periodService;

    /** 可报名的平台活动数（s01「平台活动」那一格）。setter 注入，缺了就是 0 */
    private ai.neargo.shop.promotion.service.PlatformActivityService platformService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPlatformService(ai.neargo.shop.promotion.service.PlatformActivityService platformService) {
        this.platformService = platformService;
    }

    public MarketingSummaryServiceImpl(ApplyMapper applyMapper, ActivityMapper activityMapper,
                                       CouponMapper couponMapper, PeriodService periodService) {
        this.applyMapper = applyMapper;
        this.activityMapper = activityMapper;
        this.couponMapper = couponMapper;
        this.periodService = periodService;
    }

    @Override
    public SummaryVO summary(String entityNo) {
        LocalDate today = LocalDate.now(ZONE);
        long monthStart = today.withDayOfMonth(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        /*
         * ★ 显式钉 entity_no 后绕域：pmt_apply 未登记数据域（它是账本，从不作为检索入口），
         * 其余几张在商家会话下有域。统一绕开、统一靠等值条件钉死这一家 ——
         * 一屏数字来自两种可见性规则的话，某一天就会出现「首页 3 个、点进去 2 个」。
         */
        List<PmtApply> applies = DataScopeContext.executeWithoutScope(() ->
                applyMapper.selectList(Wrappers.<PmtApply>lambdaQuery()
                        .eq(PmtApply::getEntityNo, entityNo)
                        .ge(PmtApply::getAppliedAt, monthStart)
                        .isNull(PmtApply::getRevertedAt)));
        long discount = applies.stream().mapToLong(a -> a.getAmountMinor() == null ? 0L : a.getAmountMinor()).sum();
        int orders = (int) applies.stream().map(PmtApply::getOrderNo).filter(Objects::nonNull).distinct().count();

        int activities = DataScopeContext.executeWithoutScope(() ->
                activityMapper.selectCount(Wrappers.<PmtActivity>lambdaQuery()
                        .eq(PmtActivity::getEntityNo, entityNo)
                        .eq(PmtActivity::getStatus, PmtActivity.RUNNING)
                        .isNull(PmtActivity::getArchivedAt))).intValue();
        int coupons = DataScopeContext.executeWithoutScope(() ->
                couponMapper.selectCount(Wrappers.<PmtCoupon>lambdaQuery()
                        .eq(PmtCoupon::getEntityNo, entityNo)
                        .eq(PmtCoupon::getStatus, PmtCoupon.ACTIVE)
                        .isNull(PmtCoupon::getArchivedAt))).intValue();

        List<PeriodVO> open = periodService.list(entityNo, PmtPeriod.OPEN).stream()
                .filter(p -> today.toString().equals(p.periodDate())).toList();
        int periodQty = open.stream().mapToInt(PeriodVO::qty).sum();
        Long cutoff = open.stream().map(PeriodVO::cutoffAt).min(Long::compare).orElse(null);
        int periodsShort = periodService.list(entityNo, PmtPeriod.SHORT).size();

        // 团的两个数（差人的团、待报价）由门户层补：团在 marketing 域，见 SummaryVO#withGroups
        return new SummaryVO(discount, orders, activities, coupons,
                periodQty, cutoff, periodsShort, 0, 0,
                platformService == null ? 0 : platformService.forMerchant(entityNo, "ENROLLABLE").size());
    }
}
