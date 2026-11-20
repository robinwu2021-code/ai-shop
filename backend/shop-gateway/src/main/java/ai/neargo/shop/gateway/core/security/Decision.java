package ai.neargo.shop.gateway.core.security;

import java.util.List;

/**
 * 一次判定的结论。
 *
 * @param missing 被拒时缺的权限码（权限式里此人一个都不满足的那些）；放行时为空
 */
public record Decision(boolean permitted, List<String> missing) {

    public static Decision permit() {
        return new Decision(true, List.of());
    }

    public static Decision forbidden(List<String> missing) {
        return new Decision(false, List.copyOf(missing));
    }
}
