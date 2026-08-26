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

    private Datasource datasource = new Datasource();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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
