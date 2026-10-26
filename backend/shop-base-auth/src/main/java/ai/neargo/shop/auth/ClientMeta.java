package ai.neargo.shop.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 从请求里取「谁在哪儿发的」。两条认证链共用。
 *
 * <p>抽出来而不是各自复制一份：取 IP 这件事有两个**容易写错的细节**——
 * 反代之后 {@code getRemoteAddr()} 拿到的是反代的地址（所有用户共用一个计数器）；
 * 而转发头又可能是客户端伪造的（限流、白名单形同虚设）。见 {@link #clientIp}。
 */
public final class ClientMeta {

    private ClientMeta() {
    }

    static RequestMetaContext.Meta of(HttpServletRequest req, String clientType) {
        return new RequestMetaContext.Meta(clientIp(req), clientType, userAgent(req));
    }

    /** 截到 255：登录日志那一列就这么宽，在这里截比在写库时被数据库截掉好 —— 后者会报错。 */
    private static String userAgent(HttpServletRequest req) {
        String ua = req.getHeader("User-Agent");
        if (ua == null || ua.isBlank()) {
            return null;
        }
        return ua.length() > 255 ? ua.substring(0, 255) : ua;
    }

    /**
     * 可信客户端 IP（支付服务-安全规范 §4.5）：**只认反代覆写的 {@code X-Real-IP}，且只在请求确实来自反代时才认**。
     *
     * <p>此前取 {@code X-Forwarded-For} 最左值 —— 那是客户端自己写的，按 IP 的限流、白名单、审计都能被一个伪造头绕过（G2）。
     * 现在：nginx 用 {@code $remote_addr} 覆写 {@code X-Real-IP}（不是追加）；应用只在 TCP 对端是本机或私网
     * （即请求确实经过我们的反代 / 负载均衡）时采信它，否则用对端地址本身 —— 直连应用的人伪造任何头都没有用。
     * {@code X-Forwarded-For} 一律不读。全仓只有这一处读转发头（{@code ClientIpSingleSourceTest}）。
     */
    public static String clientIp(HttpServletRequest req) {
        String remote = req.getRemoteAddr();
        if (fromTrustedProxy(remote)) {
            String real = req.getHeader("X-Real-IP");
            if (real != null && !real.isBlank()) {
                return real.trim();
            }
        }
        return remote;
    }

    /** 本机、私网（10/8、172.16/12、192.168/16）、IPv6 唯一本地与回环 = 我们自己的反代 / 负载均衡 */
    private static boolean fromTrustedProxy(String remote) {
        if (remote == null || remote.isBlank() || !remote.matches("[0-9a-fA-F:.]+")) {
            return false;
        }
        try {
            java.net.InetAddress a = java.net.InetAddress.getByName(remote);
            if (a.isLoopbackAddress() || a.isSiteLocalAddress()) {
                return true;
            }
            byte[] b = a.getAddress();
            return b.length == 16 && (b[0] & 0xfe) == 0xfc;
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }
}
