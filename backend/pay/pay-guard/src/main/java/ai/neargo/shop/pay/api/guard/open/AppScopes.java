package ai.neargo.shop.pay.api.guard.open;

import ai.neargo.shop.pay.openapp.entity.PayApp;
import java.util.Arrays;
import java.util.Map;

/**
 * 开放 API 每个动作要的 scope（开发计划 M3.3）：守卫在认出接入方之后、进 controller 之前判 —— 缺 scope 回 403，
 * 请求体都不解析。动作按入口 + 方法 + 路径推（{@code PortalActions}，与请求记录的动作列同一份）。
 *
 * <p><b>认不出的动作没有 scope</b>：守卫据此拒绝（fail closed）。新加端点忘了登记，结果是 403，而不是谁都能调。
 */
public final class AppScopes {

    private static final Map<String, String> BY_ACTION = Map.of(
            "CREATE_ONLINE", "pay:create",
            "START_PAYMENT", "pay:create",
            "CLOSE", "pay:create",
            "QUERY", "pay:read",
            "QUERY_METHODS", "pay:read",
            "REQUEST_REFUND", "pay:refund");

    private AppScopes() {
    }

    /** 该动作要的 scope；认不出的动作为空 */
    public static String requiredFor(String action) {
        return action == null ? null : BY_ACTION.get(action);
    }

    /** {@code pay_app.scopes}：逗号分隔 */
    public static boolean granted(PayApp app, String scope) {
        return app.getScopes() != null && Arrays.stream(app.getScopes().split(",")).map(String::trim).anyMatch(scope::equals);
    }
}
