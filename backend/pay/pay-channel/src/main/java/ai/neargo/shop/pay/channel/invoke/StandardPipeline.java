package ai.neargo.shop.pay.channel.invoke;

import ai.neargo.shop.pay.channel.fault.ChannelErrorMapper;
import ai.neargo.shop.pay.channel.fault.FaultPolicyRegistry;
import ai.neargo.shop.pay.channel.fault.OutcomeClassifier;
import ai.neargo.shop.pay.channel.handler.ChannelProductCatalog;
import ai.neargo.shop.pay.channel.invoke.interceptor.AvailabilityInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.BulkheadInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.CircuitBreakerInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.ClassifyInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.IdempotencyInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.LoggingInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.MetricsInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.RecordingInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.RefundIntervalInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.RetryInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.TimeoutInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.TraceInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.ValidationInterceptor;
import ai.neargo.shop.pay.channel.invoke.record.CallLogStore;
import ai.neargo.shop.pay.channel.invoke.record.ChannelCallRecorder;
import ai.neargo.shop.pay.channel.log.PayLogger;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;

/**
 * 生产管线的 13 环（渠道接入统一封装设计 §5.2），装配与测试共用这一份 —— 此前装配类里手写一串，测试另写一串，
 * 两边可以走岔而没有任何东西会发现（I2 时生产管线的顺序就没有测试看着）。顺序由各环的 {@code ORDER} 定，执行器构造时冻结并校验。
 */
public final class StandardPipeline {

    private StandardPipeline() {
    }

    public static List<ChannelInterceptor> interceptors(ChannelProductCatalog catalog, FaultPolicyRegistry policies,
                                                        ChannelCallRecorder recorder, PayLogger logger,
                                                        CallLogStore callLogs, List<? extends ChannelErrorMapper> errorMappers,
                                                        MeterRegistry meters, RefundIntervalInterceptor refundInterval) {
        return new ArrayList<>(List.of(new TraceInterceptor(), new ValidationInterceptor(),
                new AvailabilityInterceptor(catalog), new RecordingInterceptor(recorder), new LoggingInterceptor(logger),
                new MetricsInterceptor(meters), new IdempotencyInterceptor(callLogs), refundInterval,
                new CircuitBreakerInterceptor(policies), new BulkheadInterceptor(policies), new RetryInterceptor(policies),
                new TimeoutInterceptor(policies), new ClassifyInterceptor(new OutcomeClassifier(errorMappers))));
    }
}
