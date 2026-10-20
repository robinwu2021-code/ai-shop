package ai.neargo.shop.paybridge;

import ai.neargo.shop.marketing.coupon.CouponService;
import ai.neargo.shop.marketing.coupon.dto.UserCouponVO;
import java.util.List;

/** Original best-response contract; candidates are already projected through their authoritative owner. */
public interface CouponBestAppService {
    CouponService.BestResult best(String user,List<CouponService.Item> items,List<UserCouponVO> wallet);
}
