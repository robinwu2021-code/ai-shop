package ai.neargo.shop.pay.channel;

import ai.neargo.shop.pay.channel.alipay.AlipayChannel;
import ai.neargo.shop.pay.channel.alipay.AlipayClient;
import ai.neargo.shop.pay.channel.alipay.AlipayProtocol;
import ai.neargo.shop.pay.channel.http.RecordingChannelClient;
import ai.neargo.shop.pay.channel.master.PayChannelMasterService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 支付宝通道的装配点（F12 · Review R13；TDD-支付服务-W2渠道层补完 S5 / S6）。
 *
 * <p>开关 {@code shop.pay.alipay.enabled} 打开后分两种：
 * <ul>
 *   <li><b>凭据齐</b>（{@code app-id} + 应用私钥 + 支付宝公钥，内容或路径二选一）→ 装 {@link AlipayProtocol} 与直付通处理器
 *       {@link AlipayChannel}；</li>
 *   <li><b>凭据不齐</b> → 照常启动，支付宝按「未接通」处理（{@link AlipayNotReady} + 一条 ERROR）。此前网关自带 {@code @Component}，
 *       一开开关应用就因找不到客户端起不来。</li>
 * </ul>
 * 「齐不齐」不用 {@code @ConditionalOnProperty} 判：{@code ${ENV:}} 未配置时是空串，而它认为「键存在」即成立。
 *
 * <p>进件（{@code AlipayApplymentGateway}）仍未装配：它要的 {@code alipayChannelClient} 旧外形没有实现，进件迁到能力接口时一起接（W4）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shop.pay.alipay.enabled", havingValue = "true")
public class AlipayChannelConfig {

    private static final Logger log = LoggerFactory.getLogger(AlipayChannelConfig.class);

    /** 「开关开了但没接通」的显式标记，留在上下文里便于排查 */
    public record AlipayNotReady(String reason) { }

    @Bean
    @Conditional(CredentialsMissing.class)
    AlipayNotReady alipayNotReady() {
        String reason = "shop.pay.alipay.enabled=true，但 app-id / 应用私钥 / 支付宝公钥没配齐 —— 支付宝按未接通处理";
        log.error("[alipay] {}", reason);
        return new AlipayNotReady(reason);
    }

    @Bean
    @Conditional(CredentialsPresent.class)
    AlipayProtocol alipayProtocol(@Value("${shop.pay.alipay.app-id:}") String appId,
                                  @Value("${shop.pay.alipay.private-key:}") String privateKey,
                                  @Value("${shop.pay.alipay.private-key-path:}") String privateKeyPath,
                                  @Value("${shop.pay.alipay.public-key:}") String publicKey,
                                  @Value("${shop.pay.alipay.public-key-path:}") String publicKeyPath) {
        return new AlipayProtocol(appId, pemOf(privateKey, privateKeyPath, "shop.pay.alipay.private-key"),
                pemOf(publicKey, publicKeyPath, "shop.pay.alipay.public-key"), Clock.systemUTC());
    }

    @Bean
    @Conditional(CredentialsPresent.class)
    AlipayChannel alipayChannel(AlipayProtocol protocol, RecordingChannelClient http,
                                PayChannelMasterService channelMaster,
                                @Value("${shop.pay.alipay.platform-account:}") String platformAccount,
                                @Value("${shop.pay.alipay.read-timeout-seconds:15}") int readTimeoutSeconds) {
        return new AlipayChannel(new AlipayClient(protocol, http, Duration.ofSeconds(readTimeoutSeconds)),
                platformAccount, channelMaster::supportsSubsidy);
    }

    /** 内容优先，其次路径（与微信同一口径）；路径读不出来装配期就炸 */
    static String pemOf(String inline, String path, String key) {
        if (inline != null && !inline.isBlank()) {
            return inline;
        }
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            throw new IllegalStateException(key + "-path 指向的文件读不出来：" + path, e);
        }
    }

    static boolean credentialsPresent(Environment env) {
        return present(env, "shop.pay.alipay.app-id")
                && (present(env, "shop.pay.alipay.private-key") || present(env, "shop.pay.alipay.private-key-path"))
                && (present(env, "shop.pay.alipay.public-key") || present(env, "shop.pay.alipay.public-key-path"));
    }

    private static boolean present(Environment env, String key) {
        String v = env.getProperty(key);
        return v != null && !v.isBlank();
    }

    static final class CredentialsPresent implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return credentialsPresent(context.getEnvironment());
        }
    }

    static final class CredentialsMissing implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !credentialsPresent(context.getEnvironment());
        }
    }
}
