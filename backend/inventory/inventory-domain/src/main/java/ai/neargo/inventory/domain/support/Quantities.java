package ai.neargo.inventory.domain.support;

import java.math.BigDecimal;

/** 精确数量的边界：非负、最多 6 位小数、整数部分最多 18 位。 */
public final class Quantities {

    private Quantities() {
    }

    /**
     * 校验并去掉尾零。
     *
     * <p><b>与 ai-shop 的按需计划规范同一条规则</b>（下单方、库存方、远程传输三处必须一致，
     * 否则同一份计划在两边算出两个哈希）。改这里要同时改调用方那份，
     * {@code shop-inventory} 的 {@code DomainRuleParityTest} 会比对两边。
     */
    public static BigDecimal exact(BigDecimal value, boolean zero) {
        if (value == null
                || value.signum() < 0
                || !zero && value.signum() == 0
                || value.stripTrailingZeros().scale() > 6
                || value.stripTrailingZeros().precision() - value.stripTrailingZeros().scale() > 18) {
            throw new IllegalArgumentException("Exact quantity required");
        }
        return value.stripTrailingZeros();
    }
}
