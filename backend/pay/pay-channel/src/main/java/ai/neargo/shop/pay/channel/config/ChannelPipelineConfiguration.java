package ai.neargo.shop.pay.channel.config;

import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.PayGatewayRouter;
import ai.neargo.shop.pay.channel.api.ChannelGateway;
import ai.neargo.shop.pay.channel.callback.CallbackLogRecorder;
import ai.neargo.shop.pay.channel.catalog.DbChannelProductCatalog;
import ai.neargo.shop.pay.channel.fault.ChannelErrorMapper;
import ai.neargo.shop.pay.channel.followup.FollowUpScheduler;
import ai.neargo.shop.pay.channel.followup.FollowUpStore;
import ai.neargo.shop.pay.channel.followup.MybatisFollowUpStore;
import ai.neargo.shop.pay.channel.fault.FaultPolicyRegistry;
import ai.neargo.shop.pay.channel.fault.OutcomeClassifier;
import ai.neargo.shop.pay.channel.handler.ChannelHandler;
import ai.neargo.shop.pay.channel.handler.ChannelHandlerRegistry;
import ai.neargo.shop.pay.channel.handler.ChannelProductCatalog;
import ai.neargo.shop.pay.channel.invoke.ChannelInterceptor;
import ai.neargo.shop.pay.channel.invoke.ChannelInvoker;
import ai.neargo.shop.pay.channel.invoke.interceptor.AvailabilityInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.BulkheadInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.CircuitBreakerInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.ClassifyInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.IdempotencyInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.LoggingInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.RecordingInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.RetryInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.TimeoutInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.TraceInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.ValidationInterceptor;
import ai.neargo.shop.pay.channel.invoke.record.CallLogStore;
import ai.neargo.shop.pay.channel.invoke.record.ChannelCallRecorder;
import ai.neargo.shop.pay.channel.invoke.record.MybatisCallLogStore;
import ai.neargo.shop.pay.channel.lifecycle.ChannelLifecycleManager;
import ai.neargo.shop.pay.channel.lifecycle.MybatisLifecycleLogStore;
import ai.neargo.shop.pay.channel.legacy.LegacyAccessModes;
import ai.neargo.shop.pay.channel.legacy.LegacyChannelBridge;
import ai.neargo.shop.pay.channel.legacy.LegacyGatewayHandler;
import ai.neargo.shop.pay.channel.log.JsonPayLogger;
import ai.neargo.shop.pay.channel.log.PayLogger;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayCallbackLogMapper;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelCallLogMapper;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelFollowupMapper;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelLifecycleLogMapper;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelProductMapper;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelProductMarketMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 渠道管线的装配（TDD-支付服务-I2渠道统一封装 S4）。拦截器顺序由各自的 {@code ORDER} 定，执行器构造时冻结；
 * 处理器 = 新写的 {@link ChannelHandler} bean + 每个现有 {@link PayGateway} 的过渡适配。
 *
 * <p>「开着的产品都有处理器」那条启动校验读库，装在跑支付库迁移的进程里（shop-app {@code PayDataSourceConfig}），
 * 不在这里：独立的 pay-svc 不跑迁移，它的装配冒烟连的是空库。
 */
@Configuration(proxyBeanMethods = false)
public class ChannelPipelineConfiguration {

    @Bean
    FaultPolicyRegistry channelFaultPolicies() {
        return new FaultPolicyRegistry();
    }

    @Bean
    ChannelProductCatalog channelProductCatalog(PayChannelProductMapper products, PayChannelProductMarketMapper markets) {
        return new DbChannelProductCatalog(products, markets);
    }

    @Bean
    CallLogStore channelCallLogStore(PayChannelCallLogMapper mapper,
                                     @Qualifier("payTxManager") PlatformTransactionManager payTxManager) {
        return new MybatisCallLogStore(mapper, payTxManager);
    }

    @Bean
    ChannelCallRecorder channelCallRecorder(CallLogStore store) {
        return new ChannelCallRecorder(store);
    }

    @Bean
    FollowUpStore channelFollowUpStore(PayChannelFollowupMapper mapper, PayChannelCallLogMapper calls,
                                       @Qualifier("payTxManager") PlatformTransactionManager payTxManager) {
        return new MybatisFollowUpStore(mapper, calls, payTxManager);
    }

    @Bean
    CallbackLogRecorder callbackLogRecorder(PayCallbackLogMapper mapper,
                                            @Qualifier("payTxManager") PlatformTransactionManager payTxManager) {
        return new CallbackLogRecorder(mapper, payTxManager);
    }

    @Bean
    FollowUpScheduler channelFollowUpScheduler(FollowUpStore store) {
        return new FollowUpScheduler(store, Clock.systemUTC());
    }

    @Bean
    ChannelLifecycleManager channelLifecycleManager(FollowUpScheduler followUps, PayChannelLifecycleLogMapper mapper,
                                                    @Qualifier("payTxManager") PlatformTransactionManager payTxManager,
                                                    @Value("${shop.pay.channel.drain-timeout:20s}") Duration drainTimeout) {
        return new ChannelLifecycleManager(List.of(followUps), new MybatisLifecycleLogStore(mapper, payTxManager),
                drainTimeout);
    }

    @Bean
    PayLogger payLogger() {
        return new JsonPayLogger();
    }

    @Bean
    ChannelHandlerRegistry channelHandlerRegistry(List<ChannelHandler> handlers, List<PayGateway> gateways,
                                                  ChannelProductCatalog catalog) {
        List<ChannelHandler> all = new ArrayList<>(handlers);
        for (PayGateway g : gateways) {
            all.add(new LegacyGatewayHandler(g, LegacyAccessModes.of(g)));
        }
        return new ChannelHandlerRegistry(all, catalog);
    }

    @Bean
    ChannelGateway channelGateway(ChannelHandlerRegistry registry, ChannelProductCatalog catalog,
                                  FaultPolicyRegistry policies, ChannelCallRecorder recorder, CallLogStore store,
                                  PayLogger logger, List<ChannelErrorMapper> errorMappers, FollowUpScheduler followUps,
                                  ChannelLifecycleManager lifecycle) {
        List<ChannelInterceptor> chain = List.of(new TraceInterceptor(), new ValidationInterceptor(),
                new AvailabilityInterceptor(catalog), new RecordingInterceptor(recorder), new LoggingInterceptor(logger),
                new IdempotencyInterceptor(store), new CircuitBreakerInterceptor(policies),
                new BulkheadInterceptor(policies), new RetryInterceptor(policies), new TimeoutInterceptor(policies),
                new ClassifyInterceptor(new OutcomeClassifier(errorMappers)));
        return new ChannelInvoker(registry, chain, List.of(followUps, lifecycle), lifecycle);
    }

    @Bean
    LegacyChannelBridge legacyChannelBridge(ChannelGateway channelGateway, PayGatewayRouter router) {
        return new LegacyChannelBridge(channelGateway, router);
    }
}
