package ai.neargo.shop.report.config;

import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 报表库的配置。照 {@code JobStoreProperties} 的形状，前缀换成 {@code shop.report}。 */
@ConfigurationProperties(prefix = "shop.report")
public class ReportStoreProperties {

    /** 总开关。**默认关** —— 没配库的环境（本地、测试）不应该因为多了个模块就起不来。 */
    private boolean enabled = false;

    private String flywayLocations = "classpath:db/report";

    private boolean flywayEnabled = true;

    private String expectedCatalog;
    private String expectedInstance;
    private AccessRole accessRole = AccessRole.WRITER;
    private long retryDelayMillis = 60000;
    private final Datasource migrationDatasource = new Datasource();
    private final Food food = new Food();

    public enum AccessRole { READER, WRITER, MIGRATOR }

    public static class Food {
        private boolean enabled;
        private int metricVersion = 1;
        private int rowLimit = 10000;
        private int maxPartitions = 30;
        private int leaseSeconds = 240;
        private int bufferMinutes = 30;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public int getMetricVersion() { return metricVersion; }
        public void setMetricVersion(int value) { if (value < 1) throw new IllegalArgumentException(); metricVersion = value; }
        public int getRowLimit() { return rowLimit; }
        public void setRowLimit(int value) { if (value < 1 || value > 100000) throw new IllegalArgumentException(); rowLimit = value; }
        public int getMaxPartitions() { return maxPartitions; }
        public void setMaxPartitions(int value) { if (value < 3 || value > 366) throw new IllegalArgumentException(); maxPartitions = value; }
        public int getLeaseSeconds() { return leaseSeconds; }
        public void setLeaseSeconds(int value) { if (value < 10 || value > 6000) throw new IllegalArgumentException(); leaseSeconds = value; }
        public int getBufferMinutes() { return bufferMinutes; }
        public void setBufferMinutes(int value) { if (value < 0 || value > 1440) throw new IllegalArgumentException(); bufferMinutes = value; }
    }

    public String getExpectedCatalog() { return expectedCatalog; }
    public void setExpectedCatalog(String value) { expectedCatalog = value; }
    public String getExpectedInstance() { return expectedInstance; }
    public void setExpectedInstance(String value) { expectedInstance = value; }
    public AccessRole getAccessRole() { return accessRole; }
    public void setAccessRole(AccessRole value) { accessRole = Objects.requireNonNull(value); }
    public long getRetryDelayMillis() { return retryDelayMillis; }
    public void setRetryDelayMillis(long value) { if (value < 0 || value > 3600000) throw new IllegalArgumentException(); retryDelayMillis = value; }
    public Datasource getMigrationDatasource() { return migrationDatasource; }
    public Food getFood() { return food; }

    public enum InitializationMode { STRICT, ISOLATED }

    public enum InitializationRole { VALIDATOR, MIGRATOR }

    private InitializationMode initializationMode = InitializationMode.STRICT;

    private InitializationRole initializationRole = InitializationRole.VALIDATOR;

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

    public InitializationMode getInitializationMode() { return initializationMode; }

    public void setInitializationMode(InitializationMode mode) {
        this.initializationMode = Objects.requireNonNull(mode);
    }

    public InitializationRole getInitializationRole() { return initializationRole; }

    public void setInitializationRole(InitializationRole role) {
        this.initializationRole = Objects.requireNonNull(role);
    }

    public Datasource getDatasource() { return datasource; }
}
