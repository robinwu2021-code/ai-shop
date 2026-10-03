package ai.neargo.shop.pay.risk;

/**
 * 资金风控的判定结果与上下文。
 *
 * <p>取向写在最前面（[ADR-022]）：**宁可漏放一笔进入追偿流程，
 * 不可无依据地冻住一笔正常货款**。理由不是心软 —— 漏放有三层兜底
 * （保证金 → 欠款抵扣 → 停放款），误伤一层都没有，
 * 而且被误伤的商家不会等我们排查完。
 */
public final class FundRisk {

    private FundRisk() {
    }

    /** 无命中，可放行 */
    public static final String PASS = "PASS";

    /** 有命中，整批挂起转人工。**可逆，且钱还在冻结态没跑掉** */
    public static final String HOLD = "HOLD";

    /**
     * 数据错误，不是风险。
     *
     * <p>只用于「这不可能是对的」那一类（金额为负、超过原单）。
     * 与风险混在一起的话，人工队列里会混进一批根本不需要判断的东西，
     * 而真正需要人判断的那些会被淹没。
     */
    public static final String HARD_FAIL = "HARD_FAIL";

    /**
     * 判一批时用到的全部事实。
     *
     * <p>做成一个记录而不是逐个传参：规则会增加，而每加一条就改所有规则的签名
     * 是这类代码最常见的腐烂方式。
     *
     * @param refundRateBp 近 N 天退款率（万分比）。<b>{@code -1} 表示分母为零、不出结论</b> ——
     *                     刚开店的商家成交额为 0，退款率既不是 0% 也不是 100%，是<b>无意义</b>；
     *                     出结论的话，他会因为一笔退款被判成 100% 而挂起
     * @param windowDays   退款率的统计窗口，进 explain 的原话里 —— 只给比率不给窗口，
     *                     商家没法自己核
     */
    public record Facts(String entityNo, String batchNo, long batchNetMinor,
                        int refundRateBp, int windowDays,
                        long depositAvailableMinor, long debtBalanceMinor) {
    }

    /**
     * 一条规则的判定。
     *
     * @param explain <b>直接展示给商家的原话</b>，必须含具体数字与阈值。
     *                说不出人话的规则不许上线 —— 不是体验问题，是<b>申诉链路走不通</b>：
     *                商家不知道触发了什么，只能反复问客服，而客服也看不到判据
     */
    public record Verdict(String code, String result, String explain) {

        public static Verdict pass(String code) {
            return new Verdict(code, PASS, null);
        }

        public static Verdict hold(String code, String explain) {
            return new Verdict(code, HOLD, explain);
        }
    }

    /** 一条资金风控规则 */
    public interface Rule {

        /** 稳定标识，进日志与统计。<b>改名等于把历史统计断掉</b> */
        String code();

        Verdict evaluate(Facts facts);
    }
}
