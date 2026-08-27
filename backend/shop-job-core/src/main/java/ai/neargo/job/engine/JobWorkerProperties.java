package ai.neargo.job.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * worker 自己的配置。库连接在 {@code shop.job.datasource}（属于 store 模块），这里只管调度。
 */
@ConfigurationProperties(prefix = "shop.job.worker")
public class JobWorkerProperties {

    /**
     * 本实例的标识，落进 {@code job_log.worker_instance}。
     *
     * <p>迁移期可能同时有两个 worker（业务实例内的 + 独立进程的），
     * 到时候「这一轮是谁跑的」只能靠它区分。默认值故意取得难看，
     * 是为了让没配的部署一眼看得出来。
     */
    private String instance = "unnamed-worker";

    /** 轮询 {@code job_definition} 的间隔。改配置最长这么久后生效，运维上与「立刻」无异。 */
    private Duration pollInterval = Duration.ofSeconds(30);

    /** 调度线程池大小。任务多是等 HTTP 而不是算，给小一点即可。 */
    private int poolSize = 4;

    /**
     * 业务系统的地址表：{@code job_definition.target} → base URL。
     *
     * <p>例如 {@code PLATFORM: http://127.0.0.1:8081}。**走 127.0.0.1 而不是域名** ——
     * 这条调用是内网的，不该经过 nginx，也不该受公网证书与备案的影响。
     */
    private Map<String, String> targets = new LinkedHashMap<>();

    /** 调业务系统时带的共享密钥。**不进仓库**，从环境变量注入。 */
    private String token = "";

    /** 调不通时的退避序列。默认跨过本仓库 jar 约 40 秒的启动窗口。 */
    private Duration[] retryBackoff = {
            Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(45)};

    /** 每轮清理最多删多少行日志。分批是为了不长时间持锁 —— 这张表同时正被写入。 */
    private int logPurgeBatch = 1000;

    /** 日志保留天数。 */
    private int logRetentionDays = 30;

    public String getInstance() { return instance; }
    public void setInstance(String instance) { this.instance = instance; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
    public int getPoolSize() { return poolSize; }
    public void setPoolSize(int poolSize) { this.poolSize = poolSize; }
    public Map<String, String> getTargets() { return targets; }
    public void setTargets(Map<String, String> targets) { this.targets = targets; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public Duration[] getRetryBackoff() { return retryBackoff; }
    public void setRetryBackoff(Duration[] retryBackoff) { this.retryBackoff = retryBackoff; }
    public int getLogPurgeBatch() { return logPurgeBatch; }
    public void setLogPurgeBatch(int logPurgeBatch) { this.logPurgeBatch = logPurgeBatch; }
    public int getLogRetentionDays() { return logRetentionDays; }
    public void setLogRetentionDays(int logRetentionDays) { this.logRetentionDays = logRetentionDays; }
}
