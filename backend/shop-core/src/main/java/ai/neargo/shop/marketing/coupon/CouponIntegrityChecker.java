package ai.neargo.shop.marketing.coupon;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.marketing.coupon.entity.MktCoupon;
import ai.neargo.shop.marketing.coupon.mapper.CouponMappers.CouponMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时一次性扫描不合规的存量券（TDD-营销预算前置 §2.5）。
 *
 * <p>建券/改券（{@code CouponServiceImpl.saveCoupon}）已经堵死了两个取值：
 * 折扣券 {@code maxDiscountMinor=0}（不封顶）与 {@code totalCount=0}（不限量）——
 * 两者都会让"预算 ≥ 敞口"这条断言算不出来。<b>只报警，不改数据</b>：
 * 这批数据是在新规则生效前建的，当时合法，静默改掉等于替业务决定改了历史记录。
 */
@Component
public class CouponIntegrityChecker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CouponIntegrityChecker.class);

    private final CouponMapper couponMapper;

    public CouponIntegrityChecker(CouponMapper couponMapper) {
        this.couponMapper = couponMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        java.util.List<MktCoupon> bad = DataScopeContext.executeWithoutScope(() ->
                couponMapper.selectList(Wrappers.<MktCoupon>lambdaQuery()
                        .eq(MktCoupon::getDeleted, 0)
                        .and(w -> w
                                .or(x -> x.eq(MktCoupon::getType, MktCoupon.DISCOUNT)
                                        .and(y -> y.isNull(MktCoupon::getMaxDiscountMinor)
                                                .or().eq(MktCoupon::getMaxDiscountMinor, 0)))
                                .or(x -> x.eq(MktCoupon::getTotalCount, 0)))));
        if (bad.isEmpty()) {
            return;
        }
        log.warn("[coupon-integrity] {} 张存量券不满足预算前置约束（折扣券未封顶 / 不限量），"
                        + "敞口无法计算，预算列对它们形同虚设：{}",
                bad.size(), bad.stream().map(MktCoupon::getCouponNo).toList());
    }
}
