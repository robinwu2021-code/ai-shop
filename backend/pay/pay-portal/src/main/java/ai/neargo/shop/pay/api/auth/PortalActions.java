package ai.neargo.shop.pay.api.auth;

import ai.neargo.shop.pay.api.PortalFace;

/** 请求记录的「动作」列：按入口 + 方法 + 路径推（鉴权失败时还没有 controller，只能看路径） */
final class PortalActions {

    private PortalActions() {
    }

    static String of(PortalFace face, String method, String path) {
        String p = path.substring(Math.min(path.length(), face.prefix().length()));
        if (p.contains("/refunds")) {
            return "GET".equals(method) ? "QUERY" : "REQUEST_REFUND";
        }
        if (p.endsWith("/pay")) {
            return "START_PAYMENT";
        }
        if (p.endsWith("/close")) {
            return "CLOSE";
        }
        if (p.contains("/pay-methods")) {
            return "QUERY_METHODS";
        }
        if (p.startsWith("/ledger") || p.startsWith("/statements")) {
            return "READ_LEDGER";
        }
        if ("POST".equals(method) && (p.equals("/orders") || p.endsWith("/orders"))) {
            return "CREATE_ONLINE";
        }
        return "GET".equals(method) ? "QUERY" : "UNKNOWN";
    }
}
