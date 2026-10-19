package ai.neargo.shop.config;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.OutboxHandler;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.event.outbox.runtime.JdbcOutboxStore;
import ai.neargo.shop.event.outbox.runtime.OutboxProperties;
import ai.neargo.shop.event.outbox.runtime.OutboxMigrationService;
import ai.neargo.shop.event.outbox.runtime.OutboxRunner;
import ai.neargo.shop.event.outbox.runtime.TransactionalOutboxHandler;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;

/** Delayed registration avoids the event-bus / consumer / publisher bean cycle. */
@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfiguration {
    @Bean
    UnifiedOutboxFacade unifiedOutboxFacade(ObjectMapper json) { return new UnifiedOutboxFacade(json); }

    @Bean
    SmartInitializingSingleton outboxRegistration(UnifiedOutboxFacade facade, OutboxProperties props,
            ObjectProvider<OutboxConsumer> consumers, ObjectMapper json, Environment env,
            @Qualifier("dataSource") DataSource platform,
            @Qualifier("transactionManager") PlatformTransactionManager platformTx,
            @Qualifier("invDataSource") ObjectProvider<DataSource> inventory,
            @Qualifier("invTransactionManager") ObjectProvider<PlatformTransactionManager> inventoryTx,
            ObjectProvider<TradeMappers.SubOrderMapper> subOrders,
            ObjectProvider<PlatformTransactionManager> allTransactionManagers) {
        return () -> {
            props.validate();
            facade.setMaintenance(props.isMaintenance());
            facade.setRetryPolicy(props.retryPolicy());
            facade.setArchiveOnly(props.getArchiveOnly());
            if (!props.unified("PLATFORM") && !props.unified("INVENTORY")) { return; }
            if (props.unified("INVENTORY") && !props.unified("PLATFORM")) {
                throw new IllegalStateException("Embedded inventory ingress requires unified platform source");
            }
            if (props.unified("PLATFORM") && env.getProperty("shop.outbox.legacy-scan.enabled", Boolean.class, false)) {
                throw new IllegalStateException("Unified outbox cannot enable legacy-scan");
            }
            allTransactionManagers.orderedStream().forEach(ai.neargo.shop.event.outbox.runtime.OutboxTransactions::register);
            List<OutboxHandler> handlers = consumers.orderedStream().map(consumer -> {
                boolean inv = "INVENTORY".equals(consumer.targetSource());
                DataSource ds = inv ? inventory.getObject() : platform;
                PlatformTransactionManager tx = inv ? inventoryTx.getObject() : platformTx;
                return (OutboxHandler) new TransactionalOutboxHandler(consumer, ds, tx,
                        inv ? "inv_event_consumed" : "sys_event_consumed");
            }).toList();
            if (props.unified("PLATFORM")) {
                facade.register(new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM, platform, platformTx, json), handlers);
            }
            if (props.unified("INVENTORY")) {
                OutboxHandler bridge = new OutboxHandler() {
                    public String handlerId() { return "inventory-to-platform"; }
                    public boolean supports(String type) { return true; }
                    public DeliveryResult handle(OutboxEnvelope e) {
                        if (e.schemaVersion() == 1) {
                            facade.acceptInventory(e.sourceEventNo(), e.aggregateId(), e.eventType(), e.payload(), e.occurredAt());
                            return DeliveryResult.sent();
                        }
                        facade.accept("PLATFORM", new OutboxEnvelope(BizKey.next(BizKey.EVENT),
                                e.sourceSystem(), e.sourceEventNo(), e.aggregateType(), e.aggregateId(),
                                "INV_" + e.eventType(), e.schemaVersion(), "INVENTORY:" + e.streamKey(), 0,
                                e.payload(), e.occurredAt()));
                        return DeliveryResult.sent();
                    }
                };
                facade.register(new JdbcOutboxStore(JdbcOutboxStore.Source.INVENTORY,
                        inventory.getObject(), inventoryTx.getObject(), json), List.of(bridge));
            }
            if (!props.isMaintenance()) {
                for (var store : facade.stores()) {
                    if (store.unadoptedCount() > 0) {
                        throw new IllegalStateException("Outbox source requires reviewed legacy adoption: " + store.source());
                    }
                }
            }
            facade.setRoutes((source, event) -> {
                var p = json.readTree(json.writeValueAsString(event));
                if ("INVENTORY".equals(source)) {
                    return new PublishOptions("DOCUMENT:" + p.path("docNo").asString(), 2);
                }
                String orderNo = p.path("orderNo").asString("");
                String subOrderNo = p.path("subOrderNo").asString("");
                boolean logistics = event.eventType().startsWith("WAYBILL_");
                if (subOrderNo.isBlank() && logistics) { subOrderNo = p.path("bizRef").asString(""); }
                String lookupSubOrder = subOrderNo;
                if (orderNo.isBlank() && !subOrderNo.isBlank()) {
                    OrdSubOrder sub = DataScopeContext.executeWithoutScope(() -> subOrders.getObject().selectOne(
                            Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getSubOrderNo, lookupSubOrder).last("limit 1")));
                    if (sub == null && !logistics) { throw new IllegalStateException("Order stream root not found"); }
                    if (sub != null) { orderNo = sub.getOrderNo(); }
                }
                String key = orderNo.isBlank() ? event.aggregateType() + ":" + event.aggregateId() : "ORDER:" + orderNo;
                return new PublishOptions(key, 1);
            });
        };
    }

    @Bean
    OutboxMigrationService outboxMigrationService(UnifiedOutboxFacade facade, OutboxProperties props) {
        return new OutboxMigrationService(facade, props, Clock.systemUTC());
    }

    @Bean(destroyMethod = "close")
    OutboxRunner unifiedOutboxRunner(UnifiedOutboxFacade facade, OutboxProperties props) {
        return new OutboxRunner(facade, Clock.systemUTC(), props);
    }

    @Bean
    SmartLifecycle outboxLifecycle(OutboxRunner runner, OutboxProperties props, UnifiedOutboxFacade facade) {
        return new SmartLifecycle() {
            private boolean running;
            public void start() { if (props.isEnabled() && !facade.stores().isEmpty()) { runner.start(); running = true; } }
            public void stop() { running = false; runner.close(); }
            public boolean isRunning() { return running; }
            public int getPhase() { return Integer.MAX_VALUE - 100; }
        };
    }
}
