package ai.neargo.shop.pay.points.policy;

import ai.neargo.shop.spi.points.PointsFactPort.LineSnapshot;
import java.math.BigInteger;
import java.util.*;

/** Gross goods remain the refund denominator; only cash goods earn points. */
public final class EarningCashBasis {
    private EarningCashBasis() {}
    public static Map<String,Long> allocate(List<LineSnapshot> lines,long deduction) {
        long gross=0;
        var seen=new HashSet<String>();
        for(var line:lines) {
            require(line.goodsMinor()>=0 && seen.add(line.itemId()));
            gross=Math.addExact(gross,line.goodsMinor());
        }
        require(deduction>=0 && deduction<=gross);
        long total=gross-deduction,given=0;
        long[] values=new long[lines.size()];BigInteger[] remainders=new BigInteger[lines.size()];
        for(int i=0;i<lines.size();i++) {
            var amount=BigInteger.valueOf(total).multiply(BigInteger.valueOf(lines.get(i).goodsMinor()));
            var divided=gross==0?new BigInteger[]{BigInteger.ZERO,BigInteger.ZERO}:amount.divideAndRemainder(BigInteger.valueOf(gross));
            values[i]=divided[0].longValueExact();remainders[i]=divided[1];given=Math.addExact(given,values[i]);
        }
        var order=new ArrayList<Integer>();for(int i=0;i<lines.size();i++)order.add(i);
        order.sort((a,b)->{int cmp=remainders[b].compareTo(remainders[a]);return cmp==0?Integer.compare(a,b):cmp;});
        for(int i=0;given<total;i++,given++)values[order.get(i)]++;
        var out=new LinkedHashMap<String,Long>();for(int i=0;i<lines.size();i++)out.put(lines.get(i).itemId(),values[i]);
        return out;
    }
    private static void require(boolean ok) {if(!ok)throw new IllegalArgumentException("POINTS_EARN_CASH_BASIS_INVALID");}
}
