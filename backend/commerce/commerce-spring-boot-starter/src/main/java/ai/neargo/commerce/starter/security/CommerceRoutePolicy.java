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
        LEGACY_PREVIEW,
        DENY
    }

    public record Route(
            Lane lane,
            String pathStore,
            String entitlement,
            String capability,
            boolean newModelOnly) {
        private Route(Lane lane, String pathStore, String entitlement, String capability) {
            this(lane, pathStore, entitlement, capability, false);
        }
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
                    "POST /biz/auth/logout",
                    "GET /biz/auth/context",
                    "GET /biz/merchant/apply",
                    "POST /biz/merchant/apply",
                    "POST /biz/merchant/quick-start",
                    "POST /biz/merchant/agreement/accept",
                    "GET /biz/business-types");
    private static final String ID = "[A-Za-z0-9_-]{1,64}";
    private static final Pattern STORE = Pattern.compile("/biz/(?:store|stores)/([^/]+)/(.+)");

    /** Recovery routes may be used without a selection. An explicit selection must be verified. */
    public boolean isStoreSelection(String method, String path) {
        return "GET".equals(method) && SELECTION_READS.contains(path);
    }

    public boolean allowsAccountRecovery(
            Route route, String method, String path, String requested, String selected) {
        return route.lane() == Lane.ACCOUNT
                && (requested == null
                        || requested.isBlank()
                        || !isStoreSelection(method, path) && requested.equals(selected));
    }

    public Route match(String method, String path) {
        if (isStoreSelection(method, path)
                || ACCOUNT.contains(method + " " + path)
                || method.equals("GET") && path.matches("/biz/business-types/" + ID + "/plans"))
            return new Route(Lane.ACCOUNT, null, null, null);
        if (method.equals("POST") && path.equals("/biz/store/create"))
            return new Route(Lane.BOOTSTRAP, null, null, "biz:store:admin");
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
            if (method.equals("GET") && tail.equals("pos/access"))
                return new Route(Lane.END_PROTECTED, store, POS, "biz:receive", true);
            if (method.equals("GET")
                            && Set.of(
                                            "context",
                                            "end-access",
                                            "subscription",
                                            "mall/categories",
                                            "mall/products")
                                    .contains(tail)
                    || method.equals("POST") && tail.equals("end-sessions"))
                return new Route(Lane.BOOTSTRAP, store, null, null, true);
            if (method.equals("POST") && Set.of("mall/quotes", "mall/orders").contains(tail))
                return new Route(Lane.BOOTSTRAP, store, null, PROCUREMENT_DUTY, true);
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
