package ai.neargo.shop.event.rabbit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code shop.events.rabbitmq.*}（ADR-067）。只在 {@code shop.events.transport=rabbitmq} 时绑定。
 * 口令只从环境变量来、不写默认值（{@code NoSecretDefaultInConfigTest}），漏配启动即失败。
 */
@ConfigurationProperties(prefix = "shop.events.rabbitmq")
public class RabbitEventsProperties {

    private String host;
    private int port = 5672;
    private String virtualHost = "/";
    private String username;
    private String password;
    /** 事件交换机（topic，路由键 = 事件类型）。 */
    private String exchange = "shop.events";
    /** 死信交换机（direct，路由键 = 消费者 handlerId）。 */
    private String deadLetterExchange = "shop.events.dlx";
    /** 队列名前缀：{@code <前缀>.<handlerId>}，死信队列再加 {@code .dlq}。 */
    private String queuePrefix = "shop.events";
    /** 一条消息最多投几次，超过进死信（仲裁队列的 x-delivery-limit）。 */
    private int deliveryLimit = 5;
    /** 每条队列的消费线程数。 */
    private int concurrency = 1;
    /** 发送失败的本地重试次数（含第一次）。 */
    private int sendAttempts = 3;
    /** 两次重试之间等多久（毫秒）。 */
    private long sendBackoffMillis = 500;

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getVirtualHost() { return virtualHost; }
    public void setVirtualHost(String virtualHost) { this.virtualHost = virtualHost; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }
    public String getDeadLetterExchange() { return deadLetterExchange; }
    public void setDeadLetterExchange(String deadLetterExchange) { this.deadLetterExchange = deadLetterExchange; }
    public String getQueuePrefix() { return queuePrefix; }
    public void setQueuePrefix(String queuePrefix) { this.queuePrefix = queuePrefix; }
    public int getDeliveryLimit() { return deliveryLimit; }
    public void setDeliveryLimit(int deliveryLimit) { this.deliveryLimit = deliveryLimit; }
    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    public int getSendAttempts() { return sendAttempts; }
    public void setSendAttempts(int sendAttempts) { this.sendAttempts = sendAttempts; }
    public long getSendBackoffMillis() { return sendBackoffMillis; }
    public void setSendBackoffMillis(long sendBackoffMillis) { this.sendBackoffMillis = sendBackoffMillis; }

    /** 漏配直接失败，不带着空口令去连。 */
    void validate() {
        require(host, "host");
        require(username, "username");
        require(password, "password");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank() || value.startsWith("${")) {
            throw new IllegalStateException("shop.events.transport=rabbitmq 但没有配 shop.events.rabbitmq." + name);
        }
    }
}
