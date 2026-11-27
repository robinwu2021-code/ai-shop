package ai.neargo.shop.event.rabbit;

import ai.neargo.shop.event.NotificationTransport;
import ai.neargo.shop.event.SysOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.Executor;

/**
 * 通知类事件提交后发到 RabbitMQ（ADR-067 MQ 直发类）。在后台线程里发、等发布确认；
 * broker 暂时不可用就本地重试几次，用尽只记日志 —— 这类事件丢一次可以接受，兜底作业另有补发。
 */
public class RabbitNotificationTransport implements NotificationTransport {

    private static final Logger log = LoggerFactory.getLogger(RabbitNotificationTransport.class);
    private static final long CONFIRM_TIMEOUT_MILLIS = 5_000;

    private final RabbitTemplate template;
    private final ObjectMapper json;
    private final int attempts;
    private final long backoffMillis;
    private final Executor executor;

    public RabbitNotificationTransport(RabbitTemplate template, ObjectMapper json, int attempts,
                                       long backoffMillis, Executor executor) {
        this.template = template;
        this.json = json;
        this.attempts = Math.max(1, attempts);
        this.backoffMillis = Math.max(0, backoffMillis);
        this.executor = executor;
    }

    @Override
    public void send(SysOutbox envelope) {
        try {
            executor.execute(() -> sendNow(envelope));
        } catch (RuntimeException e) {
            log.warn("提交后通知未能排队发送 MQ，丢弃 {} {}", envelope.getEventType(), envelope.getEventNo(), e);
        }
    }

    private void sendNow(SysOutbox envelope) {
        Message message = EventMessages.toMessage(envelope, json);
        for (int i = 1; i <= attempts; i++) {
            try {
                template.invoke(ops -> {
                    ops.send(template.getExchange(), envelope.getEventType(), message);
                    ops.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MILLIS);
                    return null;
                });
                return;
            } catch (AmqpException e) {
                if (i == attempts) {
                    log.warn("提交后通知发 MQ 失败 {} 次，丢弃 {} {}", attempts, envelope.getEventType(),
                            envelope.getEventNo(), e);
                    return;
                }
                sleep();
            }
        }
    }

    private void sleep() {
        if (backoffMillis == 0) {
            return;
        }
        try {
            Thread.sleep(backoffMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
