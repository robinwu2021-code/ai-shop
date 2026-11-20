package ai.neargo.shop.paybridge;

import ai.neargo.shop.promotion.dto.CouponVOs.MyCouponVO;
import ai.neargo.shop.marketing.coupon.dto.UserCouponVO;
import java.util.List;

/** Compatibility views keep their original external IDs; payment snapshots supply current rights state. */
public interface LegacyCouponWalletProjectionService {
    default List<MyCouponVO> issuedWallet(String user) {return List.of();}
    default List<ai.neargo.shop.promotion.dto.CouponVOs.HeldCoupon> issuedHeld(String user) {return List.of();}
    List<MyCouponVO> wallet(String user,List<MyCouponVO> rows);
    List<UserCouponVO> checkoutWallet(String user,List<UserCouponVO> rows);
    List<String> usable(String user,List<String> candidates);
}
