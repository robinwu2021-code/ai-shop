package ai.neargo.shop.pay.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 支付宝通道的装配点（F12 · Review R13）。
 *
 * <p>今天<b>只说明、不装配</b>：两个网关依赖的 {@code alipayChannelClient}（RSA2 签名、网关调用、凭据加载）全仓没有实现。
 * 此前网关自带 {@code @Component} + 开关，一开 {@code shop.pay.alipay.enabled} 应用就因找不到 bean 起不来 ——
 * 现在开关打开时应用照常启动，支付宝按「未接通」处理：进件与支付明确返回渠道不可用，并在启动时打一条 ERROR。
 *
 * <p>客户端实现时（开发计划 I8）：在这里加 {@code alipayChannelClient}，再把 {@link AlipayPayGateway}、
 * {@link AlipayApplymentGateway} 作为 {@code @Bean} 装上，并删掉 {@link AlipayNotReady}。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shop.pay.alipay.enabled", havingValue = "true")
public class AlipayChannelConfig {

    private static final Logger log = LoggerFactory.getLogger(AlipayChannelConfig.class);

    /** 「开关开了但没接通」的显式标记，留在上下文里便于排查 */
    public record AlipayNotReady(String reason) { }

    @Bean
    AlipayNotReady alipayNotReady() {
        String reason = "shop.pay.alipay.enabled=true，但 alipayChannelClient 尚未实现（开发计划 I8）——支付宝按未接通处理";
        log.error("[alipay] {}", reason);
        return new AlipayNotReady(reason);
    }
}
