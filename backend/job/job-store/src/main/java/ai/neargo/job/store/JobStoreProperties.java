package ai.neargo.job.store;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 定时任务独立库的连接配置。
 *
 * <p><b>默认关闭。</b>这一条不是保守，是必须的：加一个硬性的第二数据源，
 * 等于所有存量部署当天都要先备好一个 {@code ai_shop_job} 才起得来。
 * 打开它应当是一次配置改动，不是一次发版。
 */
@ConfigurationProperties(prefix = "shop.job")
public class JobStoreProperties {

    /** 总开关。关着时本模块一个 bean 都不建，不需要 job 库也能启动。 */
    private boolean enabled = false;

    /** Flyway 脚本位置。测试用 H2 等价脚本时改这里（见 db/job-h2）。 */
    private String flywayLocations = "classpath:db/job";

    /** 迁移开关。生产常开；只在排查时才关。 */
    private boolean flywayEnabled = true;

    private final Datasource datasource = new Datasource();

    public static class Datasource {
        private String url;
        private String username;
        private String password;
        /** worker 只有调度器，连接数要得很少；运营端读为主。10 是宽松值。 */
        private int maxPoolSize = 10;

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
    public Datasource getDatasource() { return datasource; }
}
