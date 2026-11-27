package ai.neargo.shop.printbridge;

import org.springframework.boot.test.context.TestConfiguration;

import ai.neargo.shop.spi.printing.*;

import ai.neargo.shop.config.OutboxConfiguration;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.notify.PushSender;
import ai.neargo.shop.print.api.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PrintNotifyOutboxRegistrationTest {
    /** 打印提醒是通知类消费者（ADR-067）：不登记进发件箱，提交后由 AfterCommitNotifications 交给它；按事件号去重。 */
    @Test void printNotifyIsAfterCommitOnlyAndDedupsByEventNo() {
        new ApplicationContextRunner()
                .withUserConfiguration(OutboxConfiguration.class, Dependencies.class)
                .withPropertyValues("shop.print.enabled=true", "shop.outbox.enabled=false",
                        "shop.outbox.sources.platform.mode=unified")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var facade = context.getBean(UnifiedOutboxFacade.class);
                    assertThat(facade.handler("PLATFORM", "print-notify")).isNull();
                    var bridge = context.getBean(PrintNotifyBridge.class);
                    assertThat(bridge.afterCommitOnly()).isTrue();
                    assertThat(bridge.supports("PRINT_INCIDENT_OPENED")).isTrue();
                    var json = context.getBean(ObjectMapper.class);
                    var scope = new PrintScope("MAIN", "E1", "S1");
                    var recipients = context.getBean(PrintNotificationRecipientsPort.class);
                    when(recipients.recipients(scope, Set.of("MANAGER"))).thenReturn(List.of("STAFF1"));
                    String policy = json.writeValueAsString(Map.of("onIncident", true,
                            "channels", List.of("IN_APP"), "recipientRoleKeys", List.of("MANAGER")));
                    var event = new PrintIncidentOpened(scope, "I1", "UNKNOWN", "J1",
                            new FrozenJson(policy, PrintHashes.sha256(policy)));
                    var row = new ai.neargo.shop.event.SysOutbox();
                    row.setEventNo("EV1");
                    row.setAggregateType("PRINT");
                    row.setAggregateId("I1");
                    row.setEventType("PRINT_INCIDENT_OPENED");
                    row.setPayload(json.writeValueAsString(event));
                    bridge.consume(row);
                    bridge.consume(row);
                    verify(context.getBean(MessageService.class), org.mockito.Mockito.times(2)).pushTo(
                            eq("STAFF"), eq("STAFF1"), anyString(), anyString(), anyString(), anyString(),
                            eq("PRINT:" + PrintHashes.sha256("EV1:STAFF1")));
                });
    }

    @TestConfiguration static class Dependencies {
        @Bean ObjectMapper json() { return JsonMapper.builder().build(); }
        @Bean MessageService messages() { return mock(MessageService.class); }
        @Bean PushSender push() { return mock(PushSender.class); }
        @Bean PrintNotificationRecipientsPort recipients() { return mock(PrintNotificationRecipientsPort.class); }
        @Bean PrintNotifyBridge printNotifyBridge(MessageService messages,
                PrintNotificationRecipientsPort recipients, PushSender push, ObjectMapper json) {
            return new PrintNotifyBridge(messages, recipients, push, json);
        }
        @Bean DataSource dataSource() {
            var ds = new DriverManagerDataSource("jdbc:h2:mem:print-notify-"
                    + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE sys_outbox (source_system VARCHAR(32),status VARCHAR(16))");
            jdbc.execute("CREATE TABLE sys_event_consumed (event_no VARCHAR(64),handler VARCHAR(128),"
                    + "consumed_at TIMESTAMP, UNIQUE(event_no,handler))");
            return ds;
        }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }
    }
}
