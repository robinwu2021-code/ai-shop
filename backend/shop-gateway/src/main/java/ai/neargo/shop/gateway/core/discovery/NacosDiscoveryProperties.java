package ai.neargo.shop.gateway.core.discovery;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 接入 Nacos 的配置（{@code shop.nacos}；ADR-062、TDD-网关层与统一权限 §2.24）。
 *
 * <p>默认不启用：{@code enabled} 不是 {@code true} 时，这个包里一个 bean 都不装，服务地址照旧只读配置。
 */
@ConfigurationProperties("shop.nacos")
public class NacosDiscoveryProperties {

    /** 总开关。 */
    private boolean enabled = false;
    /** Nacos 地址，如 {@code 192.168.3.80:8848}。 */
    private String serverAddr = "";
    /** 命名空间编号；空 = 默认的 public。 */
    private String namespace = "";
    private String group = "DEFAULT_GROUP";
    /** Nacos 账号与口令。没有默认值。 */
    private String username = "";
    private String password = "";

    private final Register register = new Register();
    private final Discovery discovery = new Discovery();

    /** 把本实例登记进 Nacos。 */
    public static class Register {
        private boolean enabled = true;
        /** 登记用的服务名；不配时按部署面取 {@code ai-shop-ops} / {@code ai-shop-api}。 */
        private String serviceName = "";
        /** 登记的地址；不配时取 {@code server.address}，再取不到由客户端自己挑本机地址。 */
        private String ip = "";
        /** 登记的端口；不配时取 {@code server.port}。 */
        private int port = 0;
        /** 附加的元数据，原样带上。 */
        private final Map<String, String> metadata = new LinkedHashMap<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public String getIp() { return ip; }
        public void setIp(String ip) { this.ip = ip; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public Map<String, String> getMetadata() { return metadata; }
    }

    /** 按服务名从 Nacos 找地址。 */
    public static class Discovery {
        /**
         * 本仓库的服务名（{@code ServiceName} 里的常量）→ Nacos 里的服务名，如 {@code PAY: near-pay}。
         * <b>只有列在这里的服务才去 Nacos 找</b>；没列的照旧读 {@code shop.services.targets}。
         */
        private final Map<String, String> services = new LinkedHashMap<>();

        public Map<String, String> getServices() { return services; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getServerAddr() { return serverAddr; }
    public void setServerAddr(String serverAddr) { this.serverAddr = serverAddr; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public Register getRegister() { return register; }
    public Discovery getDiscovery() { return discovery; }
}
