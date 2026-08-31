package ai.neargo.shop.pay;

/**
 * 积分的可调参数。
 *
 * <p><b>为什么不写成常量</b>：汇率与抵扣上限是运营要调的东西，
 * 写死在代码里等于「改汇率要发版」。而更要命的是它**此前存在两份** ——
 * 后端 {@code PointsServiceImpl} 里一份，前端 {@code packages/shared} 的
 * {@code POINTS} 里一份，后者的注释还写着「与 shared 同值」。
 *
 * <p>两份同值常量必然分叉。今天刚在券的折扣率上修过一次同样的事：
 * 一处按万分比、一处按百分数，算出来<b>优惠为负，等于加价</b>。
 * 那次是趁库里没有数据统一掉的，积分这次趁没有流水统一掉。
 *
 * @param perMinor       多少积分抵一个最小货币单位（1 = 100 分抵 1 元）。
 *                       <b>必须 &gt; 0</b>，否则算抵扣金额时会除零
 * @param maxDeductRatio 单笔订单积分最多抵扣的比例。基数是<b>券后金额且不含运费</b> ——
 *                       含运费的话，一单全靠积分抵掉，商家一分收不到
 * @param earnPerMinor   消费一个最小货币单位送多少分。0 = 不发放
 * @param inactiveDays   无积分变动多久清零（滚动到期：任何变动都把到期日推后）
 * @param pendingDays    发放后多少天转为<b>可用</b>（售后期）。发出来的分先进
 *                       {@code pending_balance}（可见不可用），过了这个期才挪进
 *                       {@code balance} —— 售后期内退款要连分一起收回，
 *                       而已经花掉的分收不回来
 */
public record PointsConfig(long perMinor, double maxDeductRatio, double earnPerMinor,
                           int inactiveDays, int pendingDays) {

    /** {@code sys_setting} 的键。 */
    public static final String KEY = "points.config";

    /**
     * 没配过时用它。<b>与 {@code packages/shared} 的 {@code POINTS} 逐字段同值</b>，
     * 由 {@code PointsConfigParityTest} 守着 —— 两边默认值不一致的话，
     * C 端试算显示能抵 5 元、下单实扣 3 元，而两边代码各自都说得通。
     */
    public static final String DEFAULT_JSON = """
            {"perMinor":1,"maxDeductRatio":0.3,"earnPerMinor":0.01,"inactiveDays":365,"pendingDays":7}""";

    /**
     * 这单最多能用多少积分。
     *
     * <p><b>试算与实扣共用这一个方法</b>。分成两处算是这类缺陷的经典来源：
     * 用户看到的数字与实际扣的对不上，而两边的代码单独看都对。
     *
     * @param baseMinor 券后金额，<b>不含运费</b>
     * @param balance   账户可用余额（分数）
     */
    public long maxUsablePoints(long baseMinor, long balance) {
        if (baseMinor <= 0 || balance <= 0) {
            return 0;
        }
        long capMinor = (long) Math.floor(baseMinor * maxDeductRatio);
        return Math.max(0, Math.min(balance, capMinor * perMinor));
    }

    /** 这些分对应多少钱（分）。 */
    public long toMinor(long points) {
        return perMinor <= 0 ? 0 : points / perMinor;
    }

    /** 这笔消费该送多少分。 */
    public long earnFor(long baseMinor) {
        return baseMinor <= 0 ? 0 : (long) Math.floor(baseMinor * earnPerMinor);
    }
}
