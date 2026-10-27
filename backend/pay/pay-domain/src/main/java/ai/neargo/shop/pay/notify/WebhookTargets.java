package ai.neargo.shop.pay.notify;

import java.net.InetAddress;
import java.net.URI;

/**
 * webhook 目标地址的准入（安全规范 §2.3，防 SSRF）：只许 https；解析出的每个地址都不能是回环、私网、链路本地（含云元数据 169.254.169.254）、组播或未指定地址。
 * 登记时与每次投递前各校验一次（后者防 DNS 重绑定）。
 * {@code allowInsecure} 只给本机测试用（testcfg），生产不配。
 */
public final class WebhookTargets {

    private WebhookTargets() {
    }

    public static boolean acceptable(String url, boolean allowInsecure) {
        if (url == null || url.isBlank() || url.length() > 512) {
            return false;
        }
        try {
            URI u = URI.create(url.trim());
            if (u.getHost() == null || u.getUserInfo() != null) {
                return false;
            }
            if (allowInsecure) {
                return "https".equals(u.getScheme()) || "http".equals(u.getScheme());
            }
            if (!"https".equals(u.getScheme())) {
                return false;
            }
            for (InetAddress a : InetAddress.getAllByName(u.getHost())) {
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                        || a.isMulticastAddress() || isUniqueLocalV6(a)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isUniqueLocalV6(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xFE) == 0xFC;
    }
}
