package ai.neargo.shop.payclient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Browser links use the configured public OPS origin, never the internal PAY origin. */
public final class PayOpsLinks {
    private PayOpsLinks() {}

    public static String trustedBase(String configured) {
        if (configured == null || configured.isBlank()) return "";
        URI uri = URI.create(configured.trim());
        boolean local = "http".equals(uri.getScheme()) && uri.getHost() != null && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
        if ((!"https".equals(uri.getScheme()) && !local) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw new IllegalArgumentException("shop.pay.ops-public-base-url requires a trusted HTTPS base URL");
        return uri.toASCIIString().replaceAll("/+$", "");
    }

    public static String onboarding(String base, String merchantNo) {
        return base.isEmpty() || merchantNo == null ? null : base + "/merchants/onboarding-review?tab=fiat&keyword="
                + URLEncoder.encode(merchantNo, StandardCharsets.UTF_8);
    }
}
