package ai.neargo.shop.printbridge;

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
import org.springframework.context.annotation.Configuration;
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
    @Test void productionRegistrationConsumesNotificationOnceThroughThePlatformInbox() {
        new ApplicationContextRunner()
                .withUserConfiguration(OutboxConfiguration.class, Dependencies.class)
                .withPropertyValues("shop.print.enabled=true", "shop.outbox.enabled=false",
                        "shop.outbox.sources.platform.mode=unified")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var facade = context.getBean(UnifiedOutboxFacade.class);
                    var handler = facade.handler("PLATFORM", "print-notify");
                    assertThat(handler).isNotNull();
                    assertThat(handler.supports("PRINT_INCIDENT_OPENED")).isTrue();
                    assertThat(handler.externalWorkflow()).isFalse();
                    var json = context.getBean(ObjectMapper.class);
                    var scope = new PrintScope("MAIN", "E1", "S1");
                    var recipients = context.getBean(PrintNotificationRecipientsPort.class);
                    when(recipients.recipients(scope, Set.of("MANAGER"))).thenReturn(List.of("STAFF1"));
                    String policy = json.writeValueAsString(Map.of("onIncident", true,
                            "channels", List.of("IN_APP"), "recipientRoleKeys", List.of("MANAGER")));
                    var event = new PrintIncidentOpened(scope, "I1", "UNKNOWN", "J1",
                            new FrozenJson(policy, PrintHashes.sha256(policy)));
                    var envelope = new OutboxEnvelope("EV1", "PLATFORM", "EV1", "PRINT", "I1",
                            "PRINT_INCIDENT_OPENED", 1, "PRINT:I1", 1,
                            json.writeValueAsString(event), Instant.EPOCH);
                    assertThat(handler.handle(envelope).kind()).isEqualTo(DeliveryResult.Kind.SENT);
                    assertThat(handler.handle(envelope).kind()).isEqualTo(DeliveryResult.Kind.SENT);
                    verify(context.getBean(MessageService.class)).pushTo(eq("STAFF"), eq("STAFF1"),
                            anyString(), anyString(), anyString(), anyString(),
                            eq("PRINT:" + PrintHashes.sha256("EV1:STAFF1")));
                });
    }

    @Configuration static class Dependencies {
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
