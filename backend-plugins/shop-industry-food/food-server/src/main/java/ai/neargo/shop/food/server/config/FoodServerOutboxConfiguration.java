package ai.neargo.shop.food.server.config;

import ai.neargo.shop.event.outbox.runtime.JdbcOutboxStore;
import ai.neargo.shop.event.outbox.runtime.OutboxProperties;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.spi.food.FoodOutboxRouting;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/**
 * Standalone FOOD publishes into the existing physical PLATFORM source; PLATFORM alone delivers it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
@ConditionalOnProperty(prefix = "shop.food", name = "deployment", havingValue = "standalone")
@EnableConfigurationProperties(OutboxProperties.class)
public class FoodServerOutboxConfiguration {
    @Bean
    @ConditionalOnMissingBean(UnifiedOutboxFacade.class)
    UnifiedOutboxFacade foodServerOutboxFacade(
            @Qualifier("dataSource") DataSource source,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectMapper json,
            OutboxProperties properties,
            @Value("${shop.food.commerce.events.enabled:false}") boolean commerceEvents,
            @Value("${shop.food.commerce.fulfillment-events.enabled:false}")
                    boolean fulfillmentEvents,
            @Value("${shop.report.food.enabled:false}") boolean reportEvents,
            @Value("${shop.report.enabled:false}") boolean reportsEnabled) {
        properties.validate();
        if (!properties.unified("PLATFORM") || properties.unified("INVENTORY")) {
            throw new IllegalStateException(
                    "Standalone FOOD requires the existing unified PLATFORM producer source only");
        }
        if (!(manager instanceof DataSourceTransactionManager jdbc)
                || jdbc.getDataSource() != source) {
            throw new IllegalStateException(
                    "FOOD outbox must use the same primary business transaction and data source");
        }
        var facade = new UnifiedOutboxFacade(json);
        facade.setMaintenance(properties.isMaintenance());
        facade.setRetryPolicy(properties.retryPolicy());
        var archive = new HashSet<>(properties.getArchiveOnly());
        archive.addAll(FoodOutboxRouting.ARCHIVE_ONLY);
        var targets = new HashMap<>(FoodOutboxRouting.DELIVERY_TARGETS);
        if (commerceEvents)
            for (String type :
                    List.of(
                            "FOOD_MENU_CHANGED",
                            "FOOD_CONFIG_PUBLISHED",
                            "FOOD_DISH_CHANGED",
                            "FOOD_STORE_POLICY_PUBLISHED",
                            "FOOD_SERVICE_CALENDAR_SAVED")) {
                archive.remove("PLATFORM:" + type);
                targets.put(type, Map.of("commerce-food-source-events-v1", true));
            }
        if (fulfillmentEvents) {
            archive.remove("PLATFORM:DINING_SERVICE_EVENT");
            var handoverTargets =
                    new HashMap<>(targets.getOrDefault("DINING_SERVICE_EVENT", Map.of()));
            handoverTargets.put("commerce-food-fulfillment-events-v1", true);
            targets.put("DINING_SERVICE_EVENT", Map.copyOf(handoverTargets));
        }
        if (reportsEnabled && reportEvents) {
            archive.remove("PLATFORM:DINING_SERVICE_EVENT");
            var reportTargets =
                    new HashMap<>(targets.getOrDefault("DINING_SERVICE_EVENT", Map.of()));
            reportTargets.put("food-report-correction-v1", true);
            targets.put("DINING_SERVICE_EVENT", Map.copyOf(reportTargets));
        }
        facade.setArchiveOnly(archive);
        facade.registerProducer(
                new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM, source, manager, json),
                targets);
        // No OutboxRunner, signal pump, consumer registry or delivery scheduler is created in this
        // host.
        return facade;
    }
}
