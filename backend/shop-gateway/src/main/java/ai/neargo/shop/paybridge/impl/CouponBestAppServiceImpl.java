package ai.neargo.shop.paybridge.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.marketing.coupon.CouponService;
import ai.neargo.shop.marketing.coupon.CouponService.BestResult;
import ai.neargo.shop.marketing.coupon.CouponService.BestResult.Unusable;
import ai.neargo.shop.marketing.coupon.dto.UserCouponVO;
import ai.neargo.shop.paybridge.CouponBestAppService;
import ai.neargo.shop.spi.marketing.CouponPort;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Service;

/** Reuses the checkout allocator for both old sources and pay; never recomputes coupon rules in a controller. */
@Service
public class CouponBestAppServiceImpl implements CouponBestAppService {
    private final GoodsQueryPort goods;
    private final CouponPort coupons;
    public CouponBestAppServiceImpl(GoodsQueryPort goods,CouponPort coupons) {this.goods=goods;this.coupons=coupons;}
    @Override public BestResult best(String user,List<CouponService.Item> items,List<UserCouponVO> wallet) {
        if(items==null || items.isEmpty()) return new BestResult(null,0,List.of(),List.of());
        var snapshots=goods.snapshot(items.stream().map(CouponService.Item::skuNo).toList());
        var amounts=new LinkedHashMap<String,Long>();
        for(var item:items) {
            if(item.qty()<1) throw BizException.of(ErrorCode.BAD_REQUEST);
            var sku=snapshots.get(item.skuNo());
            if(sku!=null) {
                try {amounts.merge(sku.merchantNo(),Math.multiplyExact(sku.price(),item.qty()),Math::addExact);}
                catch(ArithmeticException overflow) {throw BizException.of(ErrorCode.BAD_REQUEST);}
            }
        }
        var groups=amounts.entrySet().stream().map(e->new CouponPort.MerchantAmount(e.getKey(),e.getValue())).toList();
        var usable=new ArrayList<UserCouponVO>();var unavailable=new ArrayList<Unusable>();
        String bestNo=null;long best=0,now=System.currentTimeMillis();
        for(var row:wallet) {
            if("USED".equals(row.status())) continue;
            var rule=row.coupon();
            if("EXPIRED".equals(row.status()) || rule.endAt()>0 && rule.endAt()<now) {
                unavailable.add(new Unusable(row.userCouponNo(),"已过期",Unusable.EXPIRED,null));continue;
            }
            if(rule.startAt()>now) {
                unavailable.add(new Unusable(row.userCouponNo(),"尚未开始",Unusable.NOT_STARTED,null));continue;
            }
            if(!row.usableNow()) {
                unavailable.add(new Unusable(row.userCouponNo(),"暂不可用","UNAVAILABLE",null));continue;
            }
            try {
                var quote=coupons.allocate(user,row.userCouponNo(),groups);
                usable.add(row);
                if(quote.totalDiscount()>best) {best=quote.totalDiscount();bestNo=row.userCouponNo();}
            } catch(BizException failure) {
                if(failure.errorCode()==ErrorCode.COUPON_NOT_APPLICABLE) {
                    long base;
                    try {base="PLATFORM".equals(rule.funder())?amounts.values().stream().reduce(0L,Math::addExact)
                            :amounts.getOrDefault(rule.merchantNo(),0L);}
                    catch(ArithmeticException overflow) {throw BizException.of(ErrorCode.BAD_REQUEST);}
                    long gap=base<=0?Math.max(rule.thresholdMinor(),1):Math.max(0,rule.thresholdMinor()-base);
                    unavailable.add(gap>0?new Unusable(row.userCouponNo(),"未达使用门槛，还差 "+gap+" 分",Unusable.BELOW_THRESHOLD,gap)
                            :new Unusable(row.userCouponNo(),"暂不可用","UNAVAILABLE",null));
                } else if(failure.errorCode()==ErrorCode.CONFLICT || failure.errorCode()==ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE) {
                    unavailable.add(new Unusable(row.userCouponNo(),"暂不可用","UNAVAILABLE",null));
                } else throw failure;
            }
        }
        return new BestResult(bestNo,best,usable,unavailable);
    }
}
