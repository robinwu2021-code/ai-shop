package ai.neargo.commerce.starter.security;

import ai.neargo.commerce.api.access.AccessDenied;
import ai.neargo.commerce.api.access.AccessStatus;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Method and full canonical path are assigned by the server. Unknown store routes are closed. */
public final class CommerceRoutePolicy {
    public enum Lane {
        ACCOUNT,
        BOOTSTRAP,
        END_PROTECTED,
        LEGACY_PREVIEW,
        DENY
    }

    public record Route(
            Lane lane,
            String pathStore,
            String entitlement,
            String capability,
            boolean newModelOnly,
            boolean foundation) {
        public Route(
                Lane lane,
                String pathStore,
                String entitlement,
                String capability,
                boolean newModelOnly) {
            this(lane, pathStore, entitlement, capability, newModelOnly, false);
        }

        private Route(Lane lane, String pathStore, String entitlement, String capability) {
            this(lane, pathStore, entitlement, capability, false);
        }
    }

    /**
     * 主机登记的「门店成员即可」路由：主机已有的日常经营接口（商品、订单、主体、收款……）。
     * 登录的是账户；这些接口在请求里选门店（X-Store-No），按「令牌 + 该门店成员 + 接口自己的权限码」放行，
     * 不要求业务端权益。没登记的受保护路由照旧默认拒绝。
     *
     * @param methods HTTP 方法
     * @param path    完整路径的正则（整串匹配）
     */
    public record HostRoute(Set<String> methods, Pattern path) {
        public static HostRoute of(String methods, String pathRegex) {
            return new HostRoute(Set.of(methods.split(",")), Pattern.compile(pathRegex));
        }

        boolean matches(String method, String fullPath) {
            return methods.contains(method) && path.matcher(fullPath).matches();
        }
    }

    private final List<HostRoute> hostRoutes;

    public CommerceRoutePolicy() {
        this(List.of());
    }

    public CommerceRoutePolicy(List<HostRoute> hostRoutes) {
        this.hostRoutes = List.copyOf(hostRoutes);
    }

    public static final String BIZ = "END_BIZ_APP", POS = "END_APP_POS";
    public static final String PROCUREMENT_DUTY = "biz:store:admin";
    private static final Set<String> SELECTION_READS =
            Set.of("/biz/context", "/biz/stores/mine", "/biz/store/list", "/biz/merchant/profile");
    private static final Set<String> ACCOUNT =
            Set.of(
                    "POST /biz/auth/login",
                    "POST /biz/auth/staff-login",
                    "POST /biz/auth/otp/send",
                    "POST /biz/auth/email-otp/send",
                    "POST /biz/auth/logout",
                    "GET /biz/auth/context",
                    "GET /biz/merchant/apply",
                    "GET /biz/auth/password",
                    "POST /biz/auth/password",
                    "GET /biz/communities",
                    "POST /biz/merchant/quick-start",
                    "GET /biz/merchant/store-setup",
                    "GET /biz/business-types",
                    // 账号本人的设置：按登录人定位，与门店无关；推送注册在登录后、选店前就会调
                    "POST /biz/merchant/display-name",
                    "POST /biz/push-token",
                    "POST /biz/push-token/unregister");
    private static final String ID = "[A-Za-z0-9_-]{1,64}";
    private static final Pattern STORE = Pattern.compile("/biz/(?:store|stores)/([^/]+)/(.+)");

    /** Recovery routes may be used without a selection. An explicit selection must be verified. */
    public boolean isStoreSelection(String method, String path) {
        return "GET".equals(method) && SELECTION_READS.contains(path);
    }

    public boolean allowsAccountRecovery(
            Route route, String method, String path, String requested, String selected) {
        return route.lane() == Lane.ACCOUNT
                && (isSessionEntry(method, path)
                        || requested == null
                        || requested.isBlank()
                        || !isStoreSelection(method, path) && requested.equals(selected));
    }

    private boolean isSessionEntry(String method, String path) {
        return "POST".equals(method)
                && Set.of(
                                "/biz/auth/login",
                                "/biz/auth/staff-login",
                                "/biz/auth/otp/send",
                                "/biz/auth/email-otp/send",
                                "/biz/auth/logout")
                        .contains(path);
    }

    public Route match(String method, String path) {
        if (isStoreSelection(method, path)
                || ACCOUNT.contains(method + " " + path)
                || method.equals("GET")
                        && path.matches("/biz/business-types/[A-Za-z0-9_.:-]{1,32}/plans"))
            return new Route(Lane.ACCOUNT, null, null, null);
        var foundation =
                switch (method + " " + path) {
                    case "POST /biz/store/create" -> "biz:store:admin";
                    case "GET /biz/merchant/payment",
                                    "POST /biz/merchant/payment",
                                    "GET /biz/merchant/pay-channel",
                                    // 申请支付服务与查状态：控制器要主体级收款目标（FoundationTarget），只有 foundation 通道会绑定它
                                    "GET /biz/merchant/pay-service",
                                    "POST /biz/merchant/pay-service/apply" ->
                            "biz:finance";
                    case "GET /biz/qualifications",
                                    "POST /biz/qualifications/save",
                                    "POST /biz/qualifications/recognize" ->
                            "biz:store";
                    case "POST /biz/merchant/apply",
                                    "POST /biz/merchant/agreement/accept",
                                    "POST /biz/upload/image" ->
                            "";
                    default -> null;
                };
        if (foundation != null)
            return new Route(
                    Lane.BOOTSTRAP,
                    null,
                    null,
                    foundation.isEmpty() ? null : foundation,
                    false,
                    true);
        if (method.equals("POST") && path.matches("/biz/merchant/payment/store/" + ID))
            return new Route(
                    Lane.BOOTSTRAP,
                    path.substring(path.lastIndexOf('/') + 1),
                    null,
                    "biz:finance",
                    false,
                    true);
        if (method.equals("POST") && path.matches("/biz/merchant/payment/" + ID + "/refresh"))
            return new Route(Lane.BOOTSTRAP, null, null, "biz:finance", false, true);
        if (method.equals("GET") && path.matches("/biz/order/" + ID + "/trace"))
            return new Route(Lane.BOOTSTRAP, null, null, "biz:order:view");
        if (method.equals("POST") && path.matches("/biz/order/" + ID + "/delivered"))
            return new Route(Lane.BOOTSTRAP, null, null, "biz:ship");
        if (method.equals("GET")
                        && Set.of("/biz/message", "/biz/message/unread-count").contains(path)
                || method.equals("POST")
                        && (path.equals("/biz/message/read-all")
                                || path.matches("/biz/message/" + ID + "/read")))
            return new Route(Lane.BOOTSTRAP, null, null, null);
        for (HostRoute h : hostRoutes) {
            if (h.matches(method, path)) {
                var inPath = STORE.matcher(path);
                return new Route(Lane.BOOTSTRAP, inPath.matches() ? inPath.group(1) : null, null, null, false);
            }
        }
        var m = STORE.matcher(path);
        if (!m.matches())
            return new Route(Lane.DENY, null, null, null, path.startsWith("/biz/stores/"));
        String store = m.group(1), tail = m.group(2);
        if (path.startsWith("/biz/store/")
                && tail.equals("pay-setting")
                && Set.of("GET", "PUT").contains(method))
            return new Route(
                    Lane.END_PROTECTED,
                    store,
                    BIZ,
                    method.equals("PUT") ? "biz:store:admin" : "biz:store");
        if (path.startsWith("/biz/stores/")) {
            if (tail.startsWith("mall/")) {
                if (!store.matches(ID)) return new Route(Lane.DENY, store, null, null, true);
                String mallId = "[A-Za-z0-9_.:-]{1,64}";
                boolean read = method.equals("GET") && (tail.equals("mall/orders")
                        || tail.matches("mall/orders/" + mallId + "(?:/payments)?"));
                boolean write = method.equals("POST") && (Set.of("mall/quotes", "mall/upgrade-quotes", "mall/orders").contains(tail)
                        || tail.matches("mall/orders/" + mallId + "/(?:cancel|payments|payments/refresh|refund-previews|refunds)"));
                // Offline (bank transfer) purchase: same procurement duty, exact routes only.
                read = read || method.equals("GET")
                        && tail.matches("mall/offline/orders/" + mallId + "/(?:instructions|claims|evidence/url)");
                write = write || method.equals("POST") && (tail.equals("mall/offline/quotes")
                        || tail.matches("mall/offline/orders/" + mallId + "/(?:instructions|evidence|evidence/content|claims)"));
                if (read || write) return new Route(Lane.BOOTSTRAP, store, null, PROCUREMENT_DUTY, true);
                if (method.equals("GET") && Set.of("mall/categories", "mall/products").contains(tail))
                    return new Route(Lane.BOOTSTRAP, store, null, null, true);
                return new Route(Lane.DENY, store, null, null, true);
            }
            if (method.equals("GET")
                    && store.matches(ID)
                    && Set.of("context", "subscription", "entitlements").contains(tail))
                return new Route(Lane.BOOTSTRAP, store, null, null, false, true);
            if (method.equals("GET") && tail.equals("pos/access"))
                return new Route(Lane.END_PROTECTED, store, POS, "biz:receive", true);
            if (method.equals("GET")
                            && Set.of("end-access", "mall/categories", "mall/products")
                                    .contains(tail)
                    || method.equals("POST") && tail.equals("end-sessions"))
                return new Route(Lane.BOOTSTRAP, store, null, null, true);
            // Preserve actual legacy store handlers. A new or unregistered plural route
            // stays closed even when MVC accepts a broader verb (e.g. HEAD for GET).
            if (Set.of("GET", "HEAD").contains(method) && tail.matches("fulfillment/[^/]+/impact"))
                return new Route(Lane.LEGACY_PREVIEW, store, null, null);
            boolean legacy =
                    tail.equals("fulfillment") && Set.of("GET", "HEAD", "PUT").contains(method)
                            || tail.equals("appointment-slots")
                                    && Set.of("GET", "HEAD", "POST").contains(method);
            return new Route(Lane.DENY, store, null, null, !legacy);
        }
        return new Route(Lane.DENY, store, null, null);
    }

    public static java.util.Optional<String> entitlementForEnd(String end) {
        return switch (end) {
            case "BIZ_APP" -> java.util.Optional.of(BIZ);
            case "APP_POS" -> java.util.Optional.of(POS);
            case null, default -> java.util.Optional.empty();
        };
    }

    /** Used by the host method-security expression. Unknown ends have no usable duty. */
    public String entryDuty(String end) {
        return entitlementForEnd(end).map(CommerceRoutePolicy::entryPermission).orElse("");
    }

    public static String endCode(String entitlement) {
        return switch (entitlement) {
            case BIZ -> "BIZ_APP";
            case POS -> "APP_POS";
            default -> throw new AccessDenied(AccessStatus.FORBIDDEN);
        };
    }

    public static String entryPermission(String entitlement) {
        return switch (entitlement) {
            case BIZ -> "biz:store";
            case POS -> "biz:receive";
            default -> throw new AccessDenied(AccessStatus.FORBIDDEN);
        };
    }
}
