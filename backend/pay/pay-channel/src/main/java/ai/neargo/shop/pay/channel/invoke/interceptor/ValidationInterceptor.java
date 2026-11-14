package ai.neargo.shop.pay.channel.invoke.interceptor;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.Money;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.pay.channel.api.command.RefundCommand;
import ai.neargo.shop.pay.channel.invoke.Chain;
import ai.neargo.shop.pay.channel.invoke.ChannelInterceptor;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import ai.neargo.shop.pay.channel.invoke.InvocationState;
import java.util.Currency;

/**
 * 序 20：命令完整性，<b>不过就不发渠道</b>（渠道接入统一封装设计 §5.2、§11.2）。
 * 金额 > 0；币种 = 地区币种；按渠道精度换算无余数（TWD 在蓝新为整数元，120.50 元发不出去 ——
 * 截断就是少收钱，四舍五入就是多收钱，两种都只能拒）；退款不超过原单。
 */
public final class ValidationInterceptor implements ChannelInterceptor {

    public static final int ORDER = 20;

    @Override
    public ChannelResult intercept(Invocation inv, Chain next) {
        Money amount = inv.command().requestAmount();
        if (amount != null) {
            if (amount.minor() <= 0) {
                return reject(PayErrorCode.PARAM_INVALID, "金额必须大于 0：" + amount.minor());
            }
            String currency = inv.account().currency();
            if (currency != null && !currency.equals(amount.currency())) {
                return reject(PayErrorCode.PARAM_INVALID, "币种与地区不符：" + amount.currency() + " ≠ " + currency);
            }
            if (!fitsScale(amount, inv.account().amountScale())) {
                return reject(PayErrorCode.PARAM_AMOUNT_SCALE, "金额按渠道精度 " + inv.account().amountScale()
                        + " 换算有余数：" + amount.minor() + " " + amount.currency());
            }
        }
        if (inv.command() instanceof RefundCommand r && r.refundAmount().minor() > r.originAmount().minor()) {
            return reject(PayErrorCode.PARAM_INVALID, "退款金额超过原单：" + r.refundAmount().minor() + " > " + r.originAmount().minor());
        }
        inv.state(InvocationState.VALIDATED);
        return next.proceed(inv);
    }

    /** 最小单位按渠道精度能否整除：CNY 两位小数、精度 2 → 恒能；TWD 两位小数、精度 0 → 必须是 100 的倍数 */
    static boolean fitsScale(Money amount, int channelScale) {
        int digits = Currency.getInstance(amount.currency()).getDefaultFractionDigits();
        if (digits <= channelScale) {
            return true;
        }
        long unit = (long) Math.pow(10, digits - channelScale);
        return amount.minor() % unit == 0;
    }

    private static ChannelResult reject(PayErrorCode code, String message) {
        return new ChannelResult.Failed(code, null, message, false);
    }

    @Override
    public int order() {
        return ORDER;
    }
}
