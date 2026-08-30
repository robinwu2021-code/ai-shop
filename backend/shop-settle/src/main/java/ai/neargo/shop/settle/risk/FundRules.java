package ai.neargo.shop.settle.risk;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 三条今天算得出来的资金风控规则。
 *
 * <p><b>只有三条，是有意的。</b>[TDD-资金风控方案] 列了五类信号，
 * 另外两类（交易形态异常、主体名单命中）今天要么缺数据源、要么要跨库问，
 * 与其先塞两条永不命中的规则进去，不如让「只有三条」这件事是明摆着的 ——
 * 一条长期不命中的规则会让人以为那个维度已经被覆盖了。
 *
 * <p>阈值<b>全部可配且默认偏松</b>：取向是先松后紧，用影子模式找位置，
 * 而不是先设一个「安全」的严阈值再慢慢放松 —— 后者的每一天都在制造误伤。
 */
@Component
public class FundRules {

    /** 退款率阈值（万分比）。默认 2000 = 20% */
    @Value("${shop.risk.fund.refund-rate-bp:2000}")
    private int refundRateBp;

    /** 本批放款额相对保证金的倍数上限。默认 3 倍 */
    @Value("${shop.risk.fund.concentration-times:3}")
    private int concentrationTimes;

    private static final String CODE_REFUND = "REFUND_RATE_HIGH";
    private static final String CODE_BATCH = "BATCH_TOO_LARGE";
    private static final String CODE_DEBT = "DEBT_OUTSTANDING";

    public List<FundRisk.Rule> rules() {
        return List.of(refundRateRule(), batchSizeRule(), debtRule());
    }

    /**
     * 退款率突增。
     *
     * <p><b>分母为零不出结论</b>：近 N 天成交额为 0 的商家，
     * 退款率既不是 0% 也不是 100%，是无意义 —— 出结论的话，
     * 一个刚开店的商家会因为一笔退款被判成 100% 而挂起。
     */
    private FundRisk.Rule refundRateRule() {
        return new FundRisk.Rule() {
            @Override
            public String code() {
                return CODE_REFUND;
            }

            @Override
            public FundRisk.Verdict evaluate(FundRisk.Facts f) {
                if (f.refundRateBp() < 0 || f.refundRateBp() <= refundRateBp) {
                    return FundRisk.Verdict.pass(CODE_REFUND);
                }
                return FundRisk.Verdict.hold(CODE_REFUND, String.format(
                        "近 %d 天退款率 %.2f%%（阈值 %.2f%%），本批转人工复核",
                        f.windowDays(), f.refundRateBp() / 100.0, refundRateBp / 100.0));
            }
        };
    }

    /**
     * 集中大额：一次放的钱远超他的保证金。
     *
     * <p><b>保证金为 0 时不判</b>：绝大多数商家没缴过保证金，
     * 拿 0 去做除数的话每一批都会命中 —— 那不是风控，是全站停摆。
     * 这一条只对「缴过保证金」的那批人有意义。
     */
    private FundRisk.Rule batchSizeRule() {
        return new FundRisk.Rule() {
            @Override
            public String code() {
                return CODE_BATCH;
            }

            @Override
            public FundRisk.Verdict evaluate(FundRisk.Facts f) {
                long deposit = f.depositAvailableMinor();
                if (deposit <= 0) {
                    return FundRisk.Verdict.pass(CODE_BATCH);
                }
                long cap = deposit * concentrationTimes;
                if (f.batchNetMinor() <= cap) {
                    return FundRisk.Verdict.pass(CODE_BATCH);
                }
                return FundRisk.Verdict.hold(CODE_BATCH, String.format(
                        "本批 %.2f 元超过保证金的 %d 倍（保证金可用 %.2f 元）",
                        f.batchNetMinor() / 100.0, concentrationTimes, deposit / 100.0));
            }
        };
    }

    /**
     * 欠款未清。
     *
     * <p>命中不等于「不放」—— 它的话术是<b>「会从本批扣除后放款」</b>，
     * 因为货款抵扣本来就是自动的。写成「转人工复核」的话，
     * 商家会以为钱被冻住了，而实际上只是少了一部分。
     */
    private FundRisk.Rule debtRule() {
        return new FundRisk.Rule() {
            @Override
            public String code() {
                return CODE_DEBT;
            }

            @Override
            public FundRisk.Verdict evaluate(FundRisk.Facts f) {
                if (f.debtBalanceMinor() <= 0) {
                    return FundRisk.Verdict.pass(CODE_DEBT);
                }
                return FundRisk.Verdict.hold(CODE_DEBT, String.format(
                        "有 %.2f 元待抵扣欠款，将从本批货款中扣除后放款",
                        f.debtBalanceMinor() / 100.0));
            }
        };
    }
}
