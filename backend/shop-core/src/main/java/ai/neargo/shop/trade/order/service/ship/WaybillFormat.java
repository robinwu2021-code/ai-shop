package ai.neargo.shop.trade.order.service.ship;

import java.util.regex.Pattern;

/**
 * 运单号格式校验（订单服务 2.0 · 发货容错）。输入应为 {@link WaybillNormalizer#normalize} 之后的值。
 *
 * <p>通用规则：长度 6–32，仅字母/数字/连字符。拦住"少打一位""粘进一句话"这类手误 ——
 * 比不校验（输错 → 订阅成功却永远无轨迹）强得多。按承运商的精确正则可作为后续增强叠加，
 * 这里先给覆盖面最广的通用规则。
 */
public final class WaybillFormat {

    private static final int MIN = 6;
    private static final int MAX = 32;
    private static final Pattern VALID = Pattern.compile("^[0-9A-Z-]+$");

    private WaybillFormat() {
    }

    public static boolean isValid(String normalized) {
        if (normalized == null) {
            return false;
        }
        int len = normalized.length();
        return len >= MIN && len <= MAX && VALID.matcher(normalized).matches();
    }
}
