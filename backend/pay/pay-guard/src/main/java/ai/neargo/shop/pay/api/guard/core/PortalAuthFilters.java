package ai.neargo.shop.pay.api.guard.core;

import ai.neargo.shop.pay.access.service.ApiRequestRecorder;
import ai.neargo.shop.pay.api.guard.PortalFace;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 按入口给出鉴权过滤器（ADR-044）：平台的安全配置为每个面建一条链，链上挂这里给的过滤器。
 * 每个面恰好一个 {@link PortalAuthenticator}；还没实现的面没有过滤器（调用方不挂链，请求落到 404）。
 */
@Component
public class PortalAuthFilters {

    private final Map<PortalFace, PortalAuthenticator> byFace = new EnumMap<>(PortalFace.class);
    private final ApiRequestRecorder recorder;

    public PortalAuthFilters(List<PortalAuthenticator> authenticators, ApiRequestRecorder recorder) {
        for (PortalAuthenticator a : authenticators) {
            if (byFace.put(a.face(), a) != null) {
                throw new IllegalStateException("入口 " + a.face() + " 有两个鉴权器");
            }
        }
        this.recorder = recorder;
    }

    public PortalAuthFilter forFace(PortalFace face) {
        PortalAuthenticator a = byFace.get(face);
        if (a == null) {
            throw new IllegalStateException("入口 " + face + " 还没有鉴权器");
        }
        return new PortalAuthFilter(face, a, recorder);
    }
}
