package ai.neargo.shop.event.outbox.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.HashMap;
import java.time.Duration;
import ai.neargo.shop.event.outbox.RetryPolicy;
import java.util.Map;
import java.util.Set;

/** A source can be owned by exactly one delivery engine during migration. */
@ConfigurationProperties("shop.outbox")
public class OutboxProperties {
    public enum Mode { LEGACY, UNIFIED }
    public static class SourceSettings {
        private Mode mode = Mode.LEGACY;
        public Mode getMode() { return mode; }
        public void setMode(Mode mode) { this.mode = mode; }
    }
    public static class RetrySettings {
        private int maxAttempts = 10;
        private Duration initialDelay = Duration.ofSeconds(10);
        private Duration maxDelay = Duration.ofMinutes(30);
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int value) { maxAttempts = value; }
        public Duration getInitialDelay() { return initialDelay; }
        public void setInitialDelay(Duration value) { initialDelay = value; }
        public Duration getMaxDelay() { return maxDelay; }
        public void setMaxDelay(Duration value) { maxDelay = value; }
    }
    private RetrySettings retry = new RetrySettings();
    private Duration recoveryInterval = Duration.ofSeconds(5);
    private Duration leaseDuration = Duration.ofSeconds(120);
    private Duration heartbeatInterval = Duration.ofSeconds(20);
    private int batchSize = 100;
    private boolean maintenance;
    private Set<String> archiveOnly = Set.of();
    public Set<String> getArchiveOnly() { return archiveOnly; }
    public void setArchiveOnly(Set<String> value) { archiveOnly = value; }
    public boolean isMaintenance() { return maintenance; }
    public void setMaintenance(boolean value) { maintenance = value; }
    public RetrySettings getRetry() { return retry; }
    public void setRetry(RetrySettings value) { retry = value; }
    public Duration getRecoveryInterval() { return recoveryInterval; }
    public void setRecoveryInterval(Duration value) { recoveryInterval = value; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration value) { leaseDuration = value; }
    public Duration getHeartbeatInterval() { return heartbeatInterval; }
    public void setHeartbeatInterval(Duration value) { heartbeatInterval = value; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int value) { batchSize = value; }
    public RetryPolicy retryPolicy() {
        return new RetryPolicy(retry.getMaxAttempts(), retry.getInitialDelay(), retry.getMaxDelay());
    }
    private boolean enabled = true;
    private int concurrency = 5;
    private int queueCapacity = 500;
    private Map<String, SourceSettings> sources = new HashMap<>();
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public Map<String, SourceSettings> getSources() { return sources; }
    public void setSources(Map<String, SourceSettings> sources) { this.sources = sources; }
    public boolean unified(String source) {
        var settings = sources.get(source.toLowerCase(java.util.Locale.ROOT));
        return settings != null && settings.getMode() == Mode.UNIFIED;
    }
    public void validate() {
        retryPolicy();
        if (batchSize < 1 || batchSize > 1000 || recoveryInterval.isNegative() || recoveryInterval.isZero()
                || heartbeatInterval.isNegative() || heartbeatInterval.isZero()
                || leaseDuration.compareTo(heartbeatInterval.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException("Invalid outbox recovery or lease settings");
        }
        if (maintenance && enabled) { throw new IllegalArgumentException("Maintenance requires disabled runner"); }
        if (concurrency < 1 || concurrency > 5 || queueCapacity < 1) {
            throw new IllegalArgumentException("Outbox requires 1..5 threads and a bounded queue");
        }
        if (sources.keySet().stream().anyMatch(k -> !k.equals("platform") && !k.equals("inventory"))) {
            throw new IllegalArgumentException("Unknown outbox source");
        }
    }
}
