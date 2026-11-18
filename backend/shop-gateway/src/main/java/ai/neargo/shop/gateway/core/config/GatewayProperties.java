package ai.neargo.shop.gateway.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 网关层配置的根（{@code shop.gateway}）：三端各一块，互不引用。 */
@ConfigurationProperties("shop.gateway")
public class GatewayProperties {

    private GatewayEndProperties mp = new GatewayEndProperties();
    private GatewayEndProperties biz = new GatewayEndProperties();
    private GatewayEndProperties ops = new GatewayEndProperties();

    /** 按端名取配置；不是三端之一（比如支付运营面）返回 null，调用方用自己的默认值。 */
    public GatewayEndProperties end(String name) {
        return switch (name) {
            case "mp" -> mp;
            case "biz" -> biz;
            case "ops" -> ops;
            default -> null;
        };
    }

    public GatewayEndProperties getMp() {
        return mp;
    }

    public void setMp(GatewayEndProperties mp) {
        this.mp = mp;
    }

    public GatewayEndProperties getBiz() {
        return biz;
    }

    public void setBiz(GatewayEndProperties biz) {
        this.biz = biz;
    }

    public GatewayEndProperties getOps() {
        return ops;
    }

    public void setOps(GatewayEndProperties ops) {
        this.ops = ops;
    }
}
