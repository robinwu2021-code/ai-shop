package ai.neargo.shop.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 从请求里取「谁在哪儿发的」。两条认证链共用。
 *
 * <p>抽出来而不是各自复制一份：取 IP 这件事有一个**容易写错的细节**——
 * 反代之后 {@code getRemoteAddr()} 拿到的是反代的地址，
 * 所有用户会因此共用同一个 IP，于是按 IP 的限流要么形同虚设、要么误伤全体。
 * 复制两份的代价是其中一份迟早会漏掉 {@code X-Forwarded-For} 这一步。
 */
final class ClientMeta {

    private ClientMeta() {
    }

    static RequestMetaContext.Meta of(HttpServletRequest req, String clientType) {
        return new RequestMetaContext.Meta(clientIp(req), clientType);
    }

    /**
     * ⚠️ 只信任**最左**一跳。
     *
     * <p>{@code X-Forwarded-For} 是客户端可伪造的头，链路上每一跳都往后追加。
     * 取最左是「客户端自称的地址」，取最右是「最近一跳反代的地址」——
     * 两者都不完美，但取最左至少让正常用户各自计数；取 {@code getRemoteAddr()}
     * 则会让反代后面所有人共用一个计数器。
     *
     * <p>攻击者能伪造这个头来绕过按 IP 的限流——**这是已知的**：
     * 按 IP 那道是补充闸，主闸是按手机号的两道。真要防伪造，
     * 得由反代覆写这个头（部署侧的事），不是应用层能解决的。
     */
    private static String clientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
