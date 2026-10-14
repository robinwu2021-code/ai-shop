package ai.neargo.shop.report.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 报表库的配置。照 {@code JobStoreProperties} 的形状，前缀换成 {@code shop.report}。 */
@ConfigurationProperties(prefix = "shop.report")
public class ReportStoreProperties {

    /** 总开关。**默认关** —— 没配库的环境（本地、测试）不应该因为多了个模块就起不来。 */
    private boolean enabled = false;

    private String flywayLocations = "classpath:db/report";

    private boolean flywayEnabled = true;

    private final Datasource datasource = new Datasource();

    /**
     * 日结每次重算的天数窗口。
     *
     * <p><b>不是 1。</b>退款、售后、改价都会改动**历史某一天**的数字，只算 T-1 的话
     * 前天的一笔退款永远补不回去，而且不会有任何东西报错。
     * 默认回看 3 天；补历史就是把它调大跑一次。
     */
    private int rollbackDays = 3;

    public static class Datasource {
        private String url;
        private String username;
        private String password;
        private int maxPoolSize = 8;

        public String getUrl() { return url; }

        public void setUrl(String url) { this.url = url; }

        public String getUsername() { return username; }

        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }

        public void setPassword(String password) { this.password = password; }

        public int getMaxPoolSize() { return maxPoolSize; }

        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
    }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getFlywayLocations() { return flywayLocations; }

    public void setFlywayLocations(String flywayLocations) { this.flywayLocations = flywayLocations; }

    public boolean isFlywayEnabled() { return flywayEnabled; }

    public void setFlywayEnabled(boolean flywayEnabled) { this.flywayEnabled = flywayEnabled; }

    public int getRollbackDays() { return rollbackDays; }

    public void setRollbackDays(int rollbackDays) { this.rollbackDays = rollbackDays; }

    public Datasource getDatasource() { return datasource; }
}
