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

    /**
     * **首次入库时一律置为「停」**，由运营在页面上逐个打开。
     *
     * <p>这一条是为「第一次启用」准备的：14 个任务从上线至今一次都没跑过，
     * 一次性全放开是**行为的净增加**，而且是 14 处同时增加 ——
     * 真出事时分不清是哪一个引起的。
     *
     * <p>开着它的时候，第一次启动会把任务全部登记进表（页面上看得见、有说明、有 cron），
     * 但一个都不跑。运营挑最无害的那个先开，看一轮，再开下一个。
     *
     * <p><b>只影响首次 INSERT</b>：之后 {@code enabled} 归运营，代码永不覆盖 ——
     * 所以把它一直开着也不会在下次发版时把人家开好的任务关掉。
     */
    private boolean startDisabled = false;

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
    public boolean isStartDisabled() { return startDisabled; }
    public void setStartDisabled(boolean startDisabled) { this.startDisabled = startDisabled; }
}
