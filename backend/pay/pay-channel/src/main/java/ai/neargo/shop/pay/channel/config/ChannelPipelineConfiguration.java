package ai.neargo.shop.pay.channel.config;

import ai.neargo.shop.pay.channel.ChannelMessageRecorder;
import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.api.ChannelGateway;
import ai.neargo.shop.pay.channel.callback.CallbackLogRecorder;
import ai.neargo.shop.pay.channel.capability.ChannelCapability;
import ai.neargo.shop.pay.channel.capability.ChannelCapabilityRegistry;
import ai.neargo.shop.pay.channel.context.ChannelContextFactory;
import ai.neargo.shop.pay.channel.catalog.DbChannelProductCatalog;
import ai.neargo.shop.pay.channel.fault.ChannelErrorMapper;
import ai.neargo.shop.pay.channel.followup.FollowUpScheduler;
import ai.neargo.shop.pay.channel.http.ChannelTransport;
import ai.neargo.shop.pay.channel.http.JdkChannelTransport;
import ai.neargo.shop.pay.channel.http.MaskedPlaintextSealer;
import ai.neargo.shop.pay.channel.http.MybatisRawPayloadStore;
import ai.neargo.shop.pay.channel.http.RawPayloadSealer;
import ai.neargo.shop.pay.channel.http.RecordingChannelClient;
import ai.neargo.shop.pay.channel.followup.FollowUpStore;
import ai.neargo.shop.pay.channel.followup.MybatisFollowUpStore;
import ai.neargo.shop.pay.channel.fault.FaultPolicyRegistry;
import ai.neargo.shop.pay.channel.handler.ChannelHandler;
import ai.neargo.shop.pay.channel.handler.ChannelHandlerRegistry;
import ai.neargo.shop.pay.channel.handler.ChannelProductCatalog;
import ai.neargo.shop.pay.channel.invoke.ChannelInterceptor;
import ai.neargo.shop.pay.channel.invoke.ChannelInvoker;
import ai.neargo.shop.pay.channel.invoke.StandardPipeline;
import ai.neargo.shop.pay.channel.invoke.interceptor.RefundIntervalInterceptor;
import ai.neargo.shop.pay.channel.invoke.record.CallLogStore;
import ai.neargo.shop.pay.channel.invoke.record.ChannelCallRecorder;
import ai.neargo.shop.pay.channel.invoke.record.MybatisCallLogStore;
import ai.neargo.shop.pay.channel.lifecycle.ChannelLifecycleManager;
import ai.neargo.shop.pay.channel.lifecycle.MybatisLifecycleLogStore;
import ai.neargo.shop.pay.channel.legacy.LegacyChannelBridge;
import ai.neargo.shop.pay.channel.log.JsonPayLogger;
import ai.neargo.shop.pay.channel.log.PayLogger;
import ai.neargo.shop.pay.channel.platform.InProcessChannel;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayCallbackLogMapper;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelCallLogMapper;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelFollowupMapper;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelLifecycleLogMapper;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelProductMapper;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelProductMarketMapper;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayRawPayloadMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 渠道管线的装配（TDD-支付服务-I2渠道统一封装 S4 · W2渠道层补完 S1–S5）。拦截器顺序由各自的 {@code ORDER} 定，执行器构造时冻结；
 * 处理器由能力实现派生（{@link ChannelCapabilityRegistry}），真渠道的 HTTP 一律经 {@link RecordingChannelClient}。
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

    /** 裸 HTTP：只给记录型客户端（RecordingClientOnlyTest） */
    @Bean
    ChannelTransport channelTransport(@Value("${shop.pay.channel.connect-timeout:5s}") Duration connectTimeout) {
        return new JdkChannelTransport(connectTimeout);
    }

    /** 原文怎么存：密钥模块（W5）就绪前存脱敏全文；W5 提供加密实现时以 @Primary 或替换此 bean 接入 */
    @Bean
    RawPayloadSealer rawPayloadSealer() {
        return new MaskedPlaintextSealer();
    }

    @Bean
    RecordingChannelClient recordingChannelClient(ChannelTransport transport, CallLogStore callLogs,
                                                  PayRawPayloadMapper rawMapper, RawPayloadSealer sealer,
                                                  ChannelMessageRecorder legacyMessages,
                                                  @Qualifier("payTxManager") PlatformTransactionManager payTxManager,
                                                  @Value("${shop.pay.raw-payload.retention-days:1825}") int retentionDays) {
        return new RecordingChannelClient(transport, callLogs, new MybatisRawPayloadStore(rawMapper, payTxManager),
                sealer, legacyMessages, retentionDays);
    }

    @Bean
    PayLogger payLogger() {
        return new JsonPayLogger();
    }

    /** 处理器 = 单独写的 {@link ChannelHandler} bean + 每个能力实现一个（W2 S5：不再有旧网关的过渡适配） */
    @Bean
    ChannelHandlerRegistry channelHandlerRegistry(ObjectProvider<ChannelHandler> handlers,
                                                  ChannelCapabilityRegistry capabilities, ChannelProductCatalog catalog) {
        List<ChannelHandler> all = new ArrayList<>(handlers.orderedStream().toList());
        all.addAll(capabilities.handlers());
        return new ChannelHandlerRegistry(all, catalog);
    }

    @Bean
    ChannelGateway channelGateway(ChannelHandlerRegistry registry, ChannelProductCatalog catalog,
                                  FaultPolicyRegistry policies, ChannelCallRecorder recorder, CallLogStore store,
                                  PayLogger logger, List<ChannelErrorMapper> errorMappers, FollowUpScheduler followUps,
                                  ChannelLifecycleManager lifecycle, ObjectProvider<MeterRegistry> meters,
                                  @Value("${shop.pay.channel.refund-queue-max-wait:65s}") Duration refundQueueMaxWait) {
        // 没有 actuator 的进程（独立 pay-svc 的装配冒烟）没有 MeterRegistry：指标记在进程内，不挡启动
        List<ChannelInterceptor> chain = StandardPipeline.interceptors(catalog, policies, recorder, logger, store,
                errorMappers, meters.getIfAvailable(SimpleMeterRegistry::new),
                new RefundIntervalInterceptor(catalog, refundQueueMaxWait));
        return new ChannelInvoker(registry, chain, List.of(followUps, lifecycle), lifecycle);
    }

    @Bean
    LegacyChannelBridge legacyChannelBridge(ChannelGateway channelGateway, ChannelHandlerRegistry handlers,
                                            ChannelCapabilityRegistry capabilities, ChannelContextFactory contexts) {
        return new LegacyChannelBridge(channelGateway, handlers, capabilities, contexts);
    }

    /**
     * 本部署装了哪些渠道能力（替代按渠道名一张表的 {@code PayGatewayRouter}，W2 S1 / S5）：真渠道的能力实现 bean
     * （微信、支付宝，各自的配置按开关装）+ 每个进程内模拟银行（TEST / STUB / FREE 的 {@link PayGateway}）一个
     * {@link InProcessChannel}。
     */
    @Bean
    ChannelCapabilityRegistry channelCapabilities(ObjectProvider<ChannelCapability> capabilities,
                                                  ObjectProvider<PayGateway> inProcessBanks) {
        List<ChannelCapability> all = new ArrayList<>(capabilities.orderedStream().toList());
        inProcessBanks.orderedStream().map(InProcessChannel::new).forEach(all::add);
        return new ChannelCapabilityRegistry(all);
    }

    /** ChannelAccountContext 的唯一构造者（W2 S4） */
    @Bean
    ChannelContextFactory channelContextFactory(ChannelCapabilityRegistry capabilities, ChannelProductCatalog catalog) {
        return new ChannelContextFactory(capabilities, catalog);
    }
}
