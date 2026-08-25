package ai.neargo.shop.portal.port;

import ai.neargo.shop.marketing.port.CouponPortImpl;
import ai.neargo.shop.promotion.service.CouponAllocService;
import ai.neargo.shop.spi.marketing.CouponPort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 券在下单时走老模型还是新模型 —— <b>按这张券在哪张表里分流，不按开关</b>（P4）。
 *
 * <p>两套表要并存到 P9。期间用户手上同时有两种券：存量的在 {@code mkt_user_coupon}，
 * 商家新发的在 {@code pmt_user_coupon}。
 *
 * <p><b>为什么不用一个全局开关</b>：开关切错的那一刻，用户手上**一整类券会同时失效**，
 * 而失效的表现是「这张券用不了」，与门槛不够、过期长得一模一样 ——
 * 客服问不出、用户也说不清。按数据分流没有这个状态：券在哪张表，就走哪条路。
 *
 * <p><b>为什么放在 app 层而不是某个域里</b>：它要同时认识两个域的实现，
 * 而域之间不许互相依赖（ArchitectureTest 守着）。app 层本来就是接线的地方。
 * P9 老表退场时，这个类连同 {@code marketing.port.CouponPortImpl} 一起删掉，
 * 新实现直接实现 {@link CouponPort} 即可 —— 到那时它是纯粹的多余。
 */
@Primary
@Component
public class CouponPortRouter implements CouponPort {

    private final CouponPortImpl legacy;
    private final CouponAllocService promo;

    public CouponPortRouter(CouponPortImpl legacy, CouponAllocService promo) {
        this.legacy = legacy;
        this.promo = promo;
    }

    @Override
    public Allocation allocate(String userNo, String userCouponNo, List<MerchantAmount> groups) {
        return promo.owns(userNo, userCouponNo)
                ? promo.allocate(userNo, userCouponNo, groups)
                : legacy.allocate(userNo, userCouponNo, groups);
    }

    @Override
    public void markUsed(String userNo, String userCouponNo, String orderNo,
                         Allocation allocation) {
        if (promo.owns(userNo, userCouponNo)) {
            promo.markUsed(userNo, userCouponNo, orderNo, allocation);
        } else {
            legacy.markUsed(userNo, userCouponNo, orderNo, allocation);
        }
    }

    @Override
    public void release(String orderNo) {
        /*
         * 退券**两边都要跑**，不能像上面那样二选一：这里只有订单号，
         * 拿不到用户券号，无从判断这一单当时用的是哪一套。
         * 两边各自按 order_no 查，没有就是空转 —— 漏退一张券，
         * 用户会认为券被平台吞了，那是券功能第二大客诉。
         */
        legacy.release(orderNo);
        promo.release(orderNo);
    }
}
