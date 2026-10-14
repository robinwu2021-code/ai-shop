package ai.neargo.shop.pay.service.recon;

/**
 * 一条<b>对账轴</b>：一种「两边对不上」的发现方式。
 *
 * <p><b>为什么要抽象成轴</b>：资金链路上有四处会对不上，而它们的数据源、
 * 判据、能查到的范围各不相同 ——
 *
 * <ul>
 *   <li><b>收款</b>　用户付了钱而我方没收到回调（已有）</li>
 *   <li><b>分账</b>　我方发出了分账指令，而通道那边迟迟不确认</li>
 *   <li><b>出款</b>　自营应付登记了付款，而银行流水对不上</li>
 *   <li><b>积分池</b>池子余额与流通中的积分不相等</li>
 * </ul>
 *
 * 塞进一个方法里的话，「今天有没有差异」会变成一个笼统的是非题，
 * 而运营真正要问的是「<b>哪一类</b>对不上、该找谁处置」。
 *
 * <p>⚠️ <b>{@link #coverage()} 不是可选项。</b>
 * 每条轴都必须说得清自己<b>查不到什么</b> —— 收款那条的类注释已经写着
 * 「页面照它显示提示条，否则『今天没有差异』是句假话」，
 * 而那句话对四条轴同样成立。一条不说明覆盖范围的轴，
 * 它报出的「零差异」是没有意义的。
 */
public interface ReconAxis {

    /** 轴的标识，落进 {@code stl_recon_diff.axis} —— 运营据它分辨「这条该找谁处置」 */
    String code();

    /** 跑一轮扫描。<b>只发现，不越权处置</b> —— 能安全自动收口的由各轴自己判断 */
    ScanOutcome scan(long now);

    /**
     * 这条轴<b>覆盖不到什么</b>。页面照它显示提示条。
     *
     * @param complete 判据是否完整（false 时 {@code note} 必须显示给运营）
     * @param note     说明，直接展示。**不在端上写死** —— 写死的话能力补上之后页面还在说「看不见」
     */
    record Coverage(boolean complete, String note) {
    }

    /**
     * @param scanned  扫了多少条
     * @param resolved 当场自动收口的
     * @param opened   新记下的差异
     * @param deferred 判不了、留到下一轮的（<b>不处置</b>）——
     *                 把「查不到」当成「没有」是这类任务最容易犯的错，
     *                 而它的后果是把一笔真实存在的单据关掉
     */
    record ScanOutcome(int scanned, int resolved, int opened, int deferred) {

        public static ScanOutcome none() {
            return new ScanOutcome(0, 0, 0, 0);
        }
    }

    Coverage coverage();

    /**
     * 「这条差异没有支付通道」的占位值。
     *
     * <p>⚠️ `stl_recon_diff.pay_channel` 是 <b>NOT NULL 且进了唯一键</b>
     * （`bill_date + pay_channel + payment_no + diff_type`）——
     * 那张表是**为收款对账设计的**，而收款一定有通道。
     *
     * <p>出款走网银、积分池根本不涉及通道，所以要一个显式的「不适用」。
     * <b>不改那个唯一键</b>：它正在保证收款差异不重复，
     * 而为了两条新轴去动一条在跑的资金约束，风险与收益不成比例。
     * 用占位值的代价只是多一个词，而且它在页面上是看得懂的。
     */
    String CHANNEL_NA = "N/A";
}
