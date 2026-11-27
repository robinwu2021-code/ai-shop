package ai.neargo.shop.event.broker;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@code shop.events.*}：与具体中间件无关的部分（ADR-067）。中间件自己的连接参数在各实现的配置下。
 */
@ConfigurationProperties(prefix = "shop.events")
public class BrokerProperties {

    /** local = 进程内（默认，不建任何中间件连接）；其余值由对应实现认领，如 rabbitmq。 */
    private String transport = "local";
    /**
     * 发件箱里改由中间件投递的事件类型（按域逐个切）。不在名单里的照旧进程内投递。
     * 在名单里的事件，必达消费者以保序订阅收。
     */
    private Set<String> outboxEventTypes = new LinkedHashSet<>();
    /** 提交后通知发中间件失败时的本地重试次数（含第一次）。 */
    private int sendAttempts = 3;
    /** 两次重试之间等多久（毫秒）。 */
    private long sendBackoffMillis = 500;

    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }
    public Set<String> getOutboxEventTypes() { return outboxEventTypes; }
    public void setOutboxEventTypes(Set<String> outboxEventTypes) { this.outboxEventTypes = outboxEventTypes; }
    public int getSendAttempts() { return sendAttempts; }
    public void setSendAttempts(int sendAttempts) { this.sendAttempts = sendAttempts; }
    public long getSendBackoffMillis() { return sendBackoffMillis; }
    public void setSendBackoffMillis(long sendBackoffMillis) { this.sendBackoffMillis = sendBackoffMillis; }

    /** 这个发件箱事件是否改由中间件投递。 */
    public boolean relays(String eventType) {
        return eventType != null && outboxEventTypes.contains(eventType);
    }
}
