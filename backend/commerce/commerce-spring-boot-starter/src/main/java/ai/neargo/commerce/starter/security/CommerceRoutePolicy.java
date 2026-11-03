package ai.neargo.commerce.starter.security;

import ai.neargo.commerce.api.access.AccessDenied;
import ai.neargo.commerce.api.access.AccessStatus;

import java.util.Set;
import java.util.regex.Pattern;

/** Method and full canonical path are assigned by the server. Unknown store routes are closed. */
public final class CommerceRoutePolicy {
    public enum Lane {
        ACCOUNT,
        BOOTSTRAP,
        END_PROTECTED,
        DENY
    }

    public record Route(Lane lane, String pathStore, String entitlement, String capability) {}

    public static final String BIZ = "END_BIZ_APP", POS = "END_APP_POS";
    private static final Set<String> ACCOUNT =
            Set.of(
                    "POST /biz/auth/login",
                    "POST /biz/auth/staff-login",
                    "POST /biz/auth/otp/send",
                    "POST /biz/auth/logout",
                    "GET /biz/auth/context",
                    "GET /biz/store/list",
                    "GET /biz/merchant/apply",
                    "POST /biz/merchant/apply",
                    "POST /biz/merchant/quick-start",
                    "POST /biz/merchant/agreement/accept",
                    "GET /biz/business-types");
    private static final String ID = "[A-Za-z0-9_-]{1,64}";
    private static final Pattern STORE = Pattern.compile("/biz/(?:store|stores)/(" + ID + ")/(.+)");

    public Route match(String method, String path) {
        if (ACCOUNT.contains(method + " " + path)
                || method.equals("GET") && path.matches("/biz/business-types/" + ID + "/plans"))
            return new Route(Lane.ACCOUNT, null, null, null);
        if (method.equals("POST") && path.equals("/biz/store/create"))
            return new Route(Lane.BOOTSTRAP, null, null, "biz:store:admin");
        if (method.equals("GET") && path.matches("/biz/order/" + ID + "/trace"))
            return new Route(Lane.BOOTSTRAP, null, null, "biz:order:view");
        if (method.equals("POST") && path.matches("/biz/order/" + ID + "/delivered"))
            return new Route(Lane.BOOTSTRAP, null, null, "biz:ship");
        var m = STORE.matcher(path);
        if (!m.matches()) return new Route(Lane.DENY, null, null, null);
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
            if (method.equals("GET") && tail.equals("pos/access"))
                return new Route(Lane.END_PROTECTED, store, POS, "biz:receive");
            if (method.equals("GET")
                            && Set.of(
                                            "context",
                                            "end-access",
                                            "subscription",
                                            "mall/categories",
                                            "mall/products")
                                    .contains(tail)
                    || method.equals("POST") && tail.equals("end-sessions"))
                return new Route(Lane.BOOTSTRAP, store, null, null);
            if (method.equals("POST") && Set.of("mall/quotes", "mall/orders").contains(tail))
                return new Route(Lane.BOOTSTRAP, store, null, "biz:store:admin");
        }
        return new Route(Lane.DENY, store, null, null);
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
