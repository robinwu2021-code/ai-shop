package ai.neargo.shop.pay.points.policy;

import ai.neargo.shop.pay.points.model.RedeemRate;
import java.util.List;

/** Quote and hold share integer allocation; acceptance is resolved by the business service, never the client. */
public interface RedeemPolicy {
    Decision calculate(long wantPoints,long availablePoints,int ratioBp,List<Target> targets,RedeemRate rate);
    record Target(String subOrderNo,String storeNo,long goodsMinor,boolean accepting) { }
    record Share(String subOrderNo,String storeNo,long points,long minor) { }
    record Decision(long points,long minor,List<Share> shares) {
        public Decision { shares=List.copyOf(shares); }
    }
}
