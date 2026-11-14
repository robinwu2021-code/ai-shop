package ai.neargo.shop.pay.callback;

import ai.neargo.shop.pay.channel.ChannelMessageRecorder;
import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.callback.CallbackLogRecorder;
import ai.neargo.shop.pay.channel.handler.ChannelHandlerRegistry;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.port.PayCallbackPortImpl;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.pay.ChannelCallbackVerifier;
import ai.neargo.shop.spi.pay.PayCallbackPort;
import ai.neargo.shop.spi.pay.PayQueryPort;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 统一回调的装配（TDD-支付服务-I2渠道统一封装 S7 / S8） */
@Configuration(proxyBeanMethods = false)
public class CallbackConfiguration {

    @Bean
    CallbackTemplate callbackTemplate(List<ChannelCallbackVerifier> verifiers, PayQueryPort channels,
                                      CallbackLogRecorder callbacks, ChannelMessageRecorder legacyMessages,
                                      PaymentLedgerService ledger, PaymentMapper payments) {
        return new CallbackTemplate(verifiers, channels, callbacks, legacyMessages, ledger, payments);
    }

    @Bean
    PayCallbackPort payCallbackPort(CallbackTemplate template) {
        return new PayCallbackPortImpl(template);
    }

    /**
     * 注册表启动校验三（渠道接入统一封装设计 §7）：装了外部渠道处理器、却没有回调处理器 → 启动失败。
     * 平台自有账户（测试、stub、免单）没有外部回调，不算。
     */
    @Bean
    SmartInitializingSingleton callbackCoverageCheck(ChannelHandlerRegistry registry, CallbackTemplate template) {
        return () -> {
            Set<String> missing = new TreeSet<>();
            registry.wired().forEach((channel, modes) -> {
                if (modes.stream().anyMatch(m -> m != AccessMode.PLATFORM_SUB) && !template.channels().contains(channel)) {
                    missing.add(channel);
                }
            });
            if (!missing.isEmpty()) {
                throw new IllegalStateException("这些渠道装了收款处理器，却没有回调验签（ChannelCallbackVerifier）：" + missing
                        + " —— 渠道推来的支付结果会全部被当成未知通道丢掉");
            }
        };
    }
}
