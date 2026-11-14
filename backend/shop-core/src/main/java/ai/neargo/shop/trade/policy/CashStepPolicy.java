package ai.neargo.shop.trade.policy;

import java.util.Map;

/**
 * 现金步长：一个币种的最小单位里，现实中能收到的最小一笔是多少。
 *
 * <p>台湾 TWD 库内小数位是 2（{@code sys_market.currency_scale}），但现金与渠道都只有整元 ——
 * NT$480.50 在台币里不存在，记进来就与同一笔经渠道收的钱（必为整元）对不上。
 * 取值与商城 {@code ShopMallConfiguration#mallMoneyPolicy}（twd-whole-v1）、渠道出款
 * {@code ValidationInterceptor}（TWD 精度 0）同一口径；这里只是 trade 域可用的一处小表，不引 commerce-api。
 *
 * <p>小数位来自门店市场，<b>不在这里写死</b>：步长 = 10^(库内小数位 − 现金小数位)；不在表里的币种不限步长。
 */
public final class CashStepPolicy {
    /** 币种 → 现金小数位。只登记比库内小数位更粗的币种。 */
    private static final Map<String, Integer> CASH_DIGITS = Map.of("TWD", 0);

    private CashStepPolicy() {}

    /** 该币种在给定库内小数位下的现金步长（最小单位）。 */
    public static long step(String currency, int currencyScale) {
        Integer cash = CASH_DIGITS.get(currency);
        if (cash == null || cash >= currencyScale) {
            return 1;
        }
        long step = 1;
        for (int i = cash; i < currencyScale; i++) {
            step *= 10;
        }
        return step;
    }

    /** 金额是否落在现金步长上。 */
    public static boolean onStep(long amountMinor, String currency, int currencyScale) {
        return amountMinor % step(currency, currencyScale) == 0;
    }
}
