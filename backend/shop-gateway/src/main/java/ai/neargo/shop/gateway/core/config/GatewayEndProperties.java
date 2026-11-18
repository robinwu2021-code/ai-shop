package ai.neargo.shop.gateway.core.config;

import java.util.List;

/**
 * 一端的配置（{@code shop.gateway.mp} / {@code .biz} / {@code .ops}）。
 *
 * <p>两项都可以不配：{@code enabled} 不配时沿用部署面（{@code api} 等于开 C 端与 B 端，{@code ops} 等于开运营端），
 * {@code publicPaths} 不配时用各端代码里的默认清单。数据源、Redis、会话在 M3 加进来。
 */
public class GatewayEndProperties {

    /** 显式开关。不配 = 跟着部署面走；配成 false 而该端的入口仍被装配时，该端前缀下一律 404。 */
    private Boolean enabled;

    /** 不用登录就能访问的地址。不配 = 用该端的默认清单。 */
    private List<String> publicPaths;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }
}
