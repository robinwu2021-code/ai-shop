package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.event.outbox.OutboxHandler;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.event.outbox.runtime.OutboxRunner;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.food.server.config.FoodServerOutboxConfiguration;
import ai.neargo.shop.spi.food.FoodOutboxRouting;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Runs the real production producer configuration; no test facade or handler stands in for runtime wiring. */
class FoodServerProducerOutboxTest {
    record Event(String aggregateId,String eventType) implements DomainEvent {
        @Override public String aggregateType() { return "FOOD_BATCH"; }
    }
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(Primary.class,FoodServerOutboxConfiguration.class)
                .withPropertyValues("shop.food.enabled=true","shop.food.deployment=standalone",
                        "shop.outbox.sources.platform.mode=unified","shop.outbox.enabled=false");
    }
    @Test void actualStandaloneProducerRecordsAcceptedDeliveryForExistingPlatformWorker() {
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(UnifiedOutboxFacade.class);
            assertThat(context.getBeansOfType(OutboxRunner.class)).isEmpty();
            assertThat(context.getBeansOfType(OutboxHandler.class)).isEmpty();
            var facade=context.getBean(UnifiedOutboxFacade.class);
            var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            tx.executeWithoutResult(status -> facade.publisher("PLATFORM").publish(
                    new Event("B1","DINING_ORDER_ACCEPTED"),new PublishOptions("FOOD_BATCH:B1",1)));
            var jdbc=new JdbcTemplate(context.getBean(DataSource.class));
            assertThat(jdbc.queryForObject("SELECT status FROM sys_outbox",String.class)).isEqualTo("PENDING");
            assertThat(jdbc.queryForList("SELECT handler_id FROM sys_outbox_delivery",String.class))
                    .containsExactlyInAnyOrder(FoodOutboxRouting.KITCHEN_ACCEPTED_HANDLER, "food-kds-business-events-v1");
            assertThat(jdbc.queryForList("SELECT external_workflow FROM sys_outbox_delivery",Integer.class))
                    .containsOnly(1);
            assertThat(facade.handler("PLATFORM",FoodOutboxRouting.KITCHEN_ACCEPTED_HANDLER)).isNull();
        });
    }
    @Test void standaloneSourceTransactionsRollbackAndFactsNeverPretendNotificationWasSent() {
        runner().run(context -> {
            var facade=context.getBean(UnifiedOutboxFacade.class);
            var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            tx.executeWithoutResult(status -> {
                facade.publisher("PLATFORM").publish(new Event("ROLLBACK","DINING_SERVICE_EVENT"),
                        new PublishOptions("FOOD_KITCHEN_TASK:T",1));
                status.setRollbackOnly();
            });
            var jdbc=new JdbcTemplate(context.getBean(DataSource.class));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox",Long.class)).isZero();
            tx.executeWithoutResult(status -> facade.publisher("PLATFORM").publish(
                    new Event("T","DINING_SERVICE_EVENT"),new PublishOptions("FOOD_KITCHEN_TASK:T",1)));
            tx.executeWithoutResult(status -> facade.publisher("PLATFORM").publish(
                    new Event("Q","DINING_QUEUE_CHANGED"),new PublishOptions("FOOD_QUEUE:Q",1)));
            assertThat(jdbc.queryForObject("SELECT status FROM sys_outbox WHERE event_type='DINING_QUEUE_CHANGED'",
                    String.class)).isEqualTo("ARCHIVED");
            assertThat(jdbc.queryForList("SELECT handler_id FROM sys_outbox_delivery",String.class))
                    .containsExactlyInAnyOrder(FoodOutboxRouting.PRODUCTION_HANDLER, "food-kds-business-events-v1");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox_delivery",Long.class)).isEqualTo(2);
        });
    }
    @Test void actualProducerPersistsAllEighteenFactRoutesAndBothDeliveryDeclarations() {
        runner().run(context -> {
            FoodServerProducerAssertions.verify(context);
            assertThat(FoodOutboxRouting.ARCHIVE_ONLY).hasSize(18);
            assertThat(FoodOutboxRouting.DELIVERY_TARGETS).hasSize(5);
            var facade=context.getBean(UnifiedOutboxFacade.class);
            var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            tx.executeWithoutResult(status -> {
                for(String fact:FoodOutboxRouting.ARCHIVE_ONLY) {
                    String type=fact.substring("PLATFORM:".length());
                    facade.publisher("PLATFORM").publish(new Event(type,type),new PublishOptions(type,1));
                }
                for(String type:FoodOutboxRouting.DELIVERY_TARGETS.keySet()) {
                    facade.publisher("PLATFORM").publish(new Event(type,type),new PublishOptions(type,1));
                }
            });
            var jdbc=new JdbcTemplate(context.getBean(DataSource.class));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox",Long.class)).isEqualTo(23);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE status='ARCHIVED'",Long.class))
                    .isEqualTo(18);
            for(String fact:FoodOutboxRouting.ARCHIVE_ONLY) {
                String type=fact.substring("PLATFORM:".length());
                assertThat(jdbc.queryForObject("SELECT status FROM sys_outbox WHERE event_type=?",String.class,type))
                        .isEqualTo("ARCHIVED");
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox_delivery",Long.class)).isEqualTo(7);
            for(var route:FoodOutboxRouting.DELIVERY_TARGETS.entrySet()) {
                assertThat(jdbc.queryForObject("SELECT status FROM sys_outbox WHERE event_type=?",String.class,route.getKey()))
                        .isEqualTo("PENDING");
                assertThat(jdbc.queryForList("SELECT d.handler_id FROM sys_outbox_delivery d "
                        + "JOIN sys_outbox e ON e.event_no=d.event_no WHERE e.event_type=?",String.class,route.getKey()))
                        .containsExactlyInAnyOrderElementsOf(route.getValue().keySet());
                assertThat(jdbc.queryForList("SELECT d.external_workflow FROM sys_outbox_delivery d "
                        + "JOIN sys_outbox e ON e.event_no=d.event_no WHERE e.event_type=?",Integer.class,route.getKey()))
                        .containsOnly(1);
            }
        });
    }
    @Test void standaloneProducerRefusesLegacyOrSecondInventorySource() {
        runner().withPropertyValues("shop.outbox.sources.platform.mode=legacy").run(context ->
                assertThat(context).hasFailed());
        runner().withPropertyValues("shop.outbox.sources.inventory.mode=unified").run(context ->
                assertThat(context).hasFailed());
    }
    @Configuration(proxyBeanMethods=false)
    static class Primary {
        @Bean DataSource dataSource() throws Exception {
            var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
            var root=Path.of(System.getProperty("food.reactor.root","..")).toAbsolutePath().normalize()
                    .resolve("../../backend/shop-app/src/main/resources/db/migration").normalize();
            String baseline=Files.readString(root.resolve("V1__baseline.sql"));
            int start=baseline.indexOf("CREATE TABLE IF NOT EXISTS sys_outbox");
            int end=baseline.indexOf(") ENGINE=",start);
            // H2 fixture retains the production columns/indexes while omitting MySQL table options.
            String base=baseline.substring(start,end+1)+";";
            new ResourceDatabasePopulator(new ByteArrayResource(base.getBytes(StandardCharsets.UTF_8)),
                    new FileSystemResource(root.resolve("V471__outbox_delivery.sql"))).execute(ds);
            return ds;
        }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
}
