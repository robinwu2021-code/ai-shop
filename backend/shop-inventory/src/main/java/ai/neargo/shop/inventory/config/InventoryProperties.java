package ai.neargo.shop.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 进销存的配置项。**零硬编码**：连接串、账号、池大小全部走配置。
 *
 * <p>{@code enabled} 默认 {@code false} —— 见 {@link InventoryDataSourceConfig} 的类注释。
 */
@ConfigurationProperties(prefix = "shop.inventory")
public class InventoryProperties {

    /** 打开进销存领域（独立数据源 + 独立迁移）。默认关，存量部署零变化。 */
    private boolean enabled = false;

    /**
     * 本模块自己跑迁移。**多实例部署或流水线统一迁移时关掉它**
     * （见开发计划 D4：服务侧 flyway 关闭，迁移由流水线单独跑一次）。
     * 关掉之后表结构由外部保证 —— 本模块不再检查，也不会因此拒绝启动。
     */
    private boolean flywayEnabled = true;

    /**
     * 迁移脚本位置。
     *
     * <p><b>可配的理由不是为了测试</b>：D4 规划里服务侧要关掉自跑迁移、由流水线单独跑，
     * 那时位置也可能不同。测试顺带用它指向 H2 等价脚本 ——
     * 生产那份是 MySQL 语法，H2 跑不了。
     */
    private String flywayLocations = "classpath:db/inventory";

    private Datasource datasource = new Datasource();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFlywayEnabled() {
        return flywayEnabled;
    }

    public void setFlywayEnabled(boolean flywayEnabled) {
        this.flywayEnabled = flywayEnabled;
    }

    public String getFlywayLocations() {
        return flywayLocations;
    }

    public void setFlywayLocations(String flywayLocations) {
        this.flywayLocations = flywayLocations;
    }

    public Datasource getDatasource() {
        return datasource;
    }

    public void setDatasource(Datasource datasource) {
        this.datasource = datasource;
    }

    /** 独立库的连接参数。**不复用平台的 {@code spring.datasource}** —— 它们迟早要指向两个实例。 */
    public static class Datasource {
        private String url;
        private String username;
        private String password;
        /** 池不必大：进销存的写路径是单笔单据，读路径有索引兜着。 */
        private int maxPoolSize = 8;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }
    }
}
