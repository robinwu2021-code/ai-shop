package ai.neargo.shop.product.policy;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 类目禁止的付款方式（{@code prd_category.disallowed_pay_modes}，JSON 数组；空表示全部放行）。
 *
 * <p>旧库里这是一张表 {@code prd_category_pay_mode}（「没有行即放行，插 allowed=0 才是禁止」），
 * 门店交易库把它并成类目的一列（TDD-商品领域迁到门店交易库 批次 2）。语义不变：只记禁止的，
 * 数组为空就存 NULL —— 「被禁的类目」仍然一眼可数，不会长出一份「配过但不禁」的噪声。
 */
public final class CategoryPayModePolicy {
    private static final Pattern MODE = Pattern.compile("\"([A-Z_]+)\"");

    private CategoryPayModePolicy() {
    }

    /** 解析不出来按空处理：脏数据不该让一整类商品变成不可下单。 */
    public static Set<String> disallowed(String jsonArray) {
        Set<String> out = new TreeSet<>();
        if (jsonArray == null || jsonArray.isBlank()) {
            return out;
        }
        Matcher m = MODE.matcher(jsonArray);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    public static boolean allows(String jsonArray, String payMode) {
        return !disallowed(jsonArray).contains(payMode);
    }

    /** 把一种付款方式设为禁止或放行，返回新的列值（空数组为 NULL）。 */
    public static String with(String jsonArray, String payMode, boolean disallow) {
        Set<String> modes = disallowed(jsonArray);
        if (disallow) {
            modes.add(payMode);
        } else {
            modes.remove(payMode);
        }
        if (modes.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder("[");
        for (String mode : modes) {
            out.append(out.length() > 1 ? "," : "").append('"').append(mode).append('"');
        }
        return out.append(']').toString();
    }
}
