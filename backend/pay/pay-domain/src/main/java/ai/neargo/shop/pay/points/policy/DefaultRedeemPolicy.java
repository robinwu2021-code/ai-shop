package ai.neargo.shop.pay.points.policy;

import ai.neargo.shop.pay.points.model.RedeemRate;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Allocation uses whole redeemable units and deterministic remainder assignment, never floating-point money. */
public final class DefaultRedeemPolicy implements RedeemPolicy {
    @Override public Decision calculate(long want,long available,int bp,List<Target> input,RedeemRate rate) {
        if (available<0 || bp<0 || bp>10000) { throw new IllegalArgumentException("POINTS_REDEEM_INPUT_INVALID"); }
        var seen=new HashSet<String>();
        for (Target t:input) {
            if (t.subOrderNo()==null || t.storeNo()==null || t.goodsMinor()<0 || !seen.add(t.subOrderNo())) {
                throw new IllegalArgumentException("POINTS_REDEEM_TARGET_INVALID");
            }
        }
        List<Target> eligible=input.stream().filter(t -> t.accepting() && t.goodsMinor()>0)
                .sorted(Comparator.comparingLong(Target::goodsMinor).reversed()
                        .thenComparing(Target::subOrderNo)).toList();
        if (want<=0 || bp==0 || eligible.isEmpty()) { return new Decision(0,0,List.of()); }
        long goods=0,capacity=0;
        for (Target t:eligible) {
            goods=Math.addExact(goods,t.goodsMinor());
            capacity=Math.addExact(capacity,t.goodsMinor()/rate.minorUnit());
        }
        long capMoney=BigInteger.valueOf(goods).multiply(BigInteger.valueOf(bp))
                .divide(BigInteger.valueOf(10000)).longValueExact();
        long units=Math.min(Math.min(rate.floorAligned(Math.min(want,available))/rate.pointsUnit(),
                capMoney/rate.minorUnit()),capacity);
        List<Long> assigned=new ArrayList<>();
        long remaining=units;
        for (Target t:eligible) {
            long share=BigInteger.valueOf(units).multiply(BigInteger.valueOf(t.goodsMinor()))
                    .divide(BigInteger.valueOf(goods)).longValueExact();
            share=Math.min(share,t.goodsMinor()/rate.minorUnit());
            assigned.add(share); remaining-=share;
        }
        for (int i=0;i<eligible.size() && remaining>0;i++) {
            long extra=Math.min(remaining,eligible.get(i).goodsMinor()/rate.minorUnit()-assigned.get(i));
            assigned.set(i,assigned.get(i)+extra); remaining-=extra;
        }
        List<Share> shares=new ArrayList<>();
        for (int i=0;i<eligible.size();i++) {
            if (assigned.get(i)>0) {
                Target t=eligible.get(i);
                shares.add(new Share(t.subOrderNo(),t.storeNo(),Math.multiplyExact(assigned.get(i),rate.pointsUnit()),
                        Math.multiplyExact(assigned.get(i),rate.minorUnit())));
            }
        }
        return new Decision(Math.multiplyExact(units,rate.pointsUnit()),
                Math.multiplyExact(units,rate.minorUnit()),shares);
    }
}
