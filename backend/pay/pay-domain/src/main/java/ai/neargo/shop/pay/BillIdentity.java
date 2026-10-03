package ai.neargo.shop.pay;

import ai.neargo.shop.pay.entity.StlBill;

/**
 * 门 1 · 单据自查：<b>这张单自己的账平不平</b>。
 *
 * <pre>基数 = 佣金 + 履约服务费 + 积分费用金 + 商家承担的通道手续费 + 商家实得</pre>
 *
 * <p>三个数分别来自三处（费率表、自提点配置、通道费率），
 * <b>不校验就会出现「分完了还差几分钱」</b>，而它要到月底对账才被发现。
 *
 * <p>做成纯函数：它是三道门里<b>唯一不依赖外部数据</b>的一道 ——
 * 只看单据自己。所以它也是唯一今天就能实现的一道。
 */
public final class BillIdentity {

    private BillIdentity() {
    }

    /**
     * 差额：<b>基数 减去 各项之和</b>。0 表示账平。
     *
     * <p>返回有符号的差额而不是 boolean：
     * <b>正负号说明钱多了还是少了</b>，而那是排查时的第一个问题。
     * 只回 true/false 的话，运营看到「不平」还要自己去减一遍。
     */
    public static long gap(StlBill b) {
        return nz(b.getGrossMinor())
                - nz(b.getCommissionMinor())
                - nz(b.getServiceFeeMinor())
                - nz(b.getPointsFeeMinor())
                - merchantBorneFee(b)
                - nz(b.getNetMinor());
    }

    /** 账平不平 */
    public static boolean balanced(StlBill b) {
        return gap(b) == 0;
    }

    /**
     * 商家<b>自己承担</b>的那部分通道手续费。
     *
     * <p><b>不能直接用 {@code channelFeeMinor}</b>：承担方是平台或未知时，
     * 那笔钱没有从商家的实得里扣，把它算进等式左边会让每一张这样的单都「不平」——
     * 而那不是账错，是等式写错了。判据与 {@code SettleServiceImpl} 里落库时用的
     * 是同一个（{@code fee_bearer == MERCHANT}）。
     */
    public static long merchantBorneFee(StlBill b) {
        return "MERCHANT".equals(b.getFeeBearer()) ? nz(b.getChannelFeeMinor()) : 0L;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
