package ai.neargo.shop.pay.channel;

import ai.neargo.shop.pay.channel.http.RecordingChannelClient;
import ai.neargo.shop.pay.channel.master.PayChannelMasterService;
import ai.neargo.shop.pay.channel.wechat.WechatErrorMapper;
import ai.neargo.shop.pay.channel.wechat.WechatV3Client;
import ai.neargo.shop.pay.channel.wechat.WechatV3Protocol;
import ai.neargo.shop.pay.channel.wechat.direct.WechatDirectChannel;
import ai.neargo.shop.pay.channel.wechat.ecommerce.WechatEcommerceChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 微信支付凭据的装配点。
 *
 * <h2>为什么签名器要单独成一个 bean</h2>
 * 它同时被两个人要：{@link WechatV3Protocol}（签请求、验应答）与小程序 paySign。
 * 让网关去 {@code instanceof} 客户端再把它掏出来的话，
 * 密钥的持有关系就从「一处声明」变成「谁能拿到谁拿」——
 * 而那种关系没有任何地方能一眼看全。
 *
 * <p><b>密钥只在这一个方法里出现</b>：读配置、造签名器，之后再没有任何代码
 * 能从签名器里取回密钥（它不提供这样的方法）。
 *
 * <p>整体挂在 {@code shop.pay.wechat.enabled} 上：<b>用显式开关而不是
 * 「凭据配了没」</b> —— {@code ${ENV:}} 在未配置时是空串，
 * 而 {@code @ConditionalOnProperty} 认为「键存在」即成立，
 * 于是会带着空凭据启动，直到第一次调用才失败。
 */
@Configuration
@ConditionalOnProperty(name = "shop.pay.wechat.enabled", havingValue = "true")
public class WechatPayChannelConfig {

    @Bean
    public WechatApiV3Signer wechatApiV3Signer(
            @Value("${shop.pay.wechat.mchid:}") String mchId,
            @Value("${shop.pay.wechat.serial-no:}") String serialNo,
            @Value("${shop.pay.wechat.private-key:}") String privateKeyPem,
            @Value("${shop.pay.wechat.private-key-path:}") String privateKeyPath,
            @Value("${shop.pay.wechat.platform-public-key:}") String platformPublicKeyPem,
            @Value("${shop.pay.wechat.platform-public-key-path:}") String platformPublicKeyPath) {
        if (mchId == null || mchId.isBlank() || serialNo == null || serialNo.isBlank()) {
            /*
             * **装配期就要炸。** 带着空商户号启动的话，第一次真实下单才失败，
             * 而那时用户已经在收银台前面了 —— 且失败信息是通道给的英文码，
             * 看不出是我方少配了一个环境变量。
             */
            throw new IllegalStateException("微信支付已开启（shop.pay.wechat.enabled=true）"
                    + "但商户号或证书序列号未配置 —— 拒绝以不完整的凭据装配");
        }
        String publicKey = pemOf(platformPublicKeyPem, platformPublicKeyPath,
                "shop.pay.wechat.platform-public-key");
        if (publicKey == null || publicKey.isBlank()) {
            /*
             * **公钥缺了也拒绝装配**（2026-09-04 收紧）。此前只打一条 WARN 就放行 ——
             * 而那是**两边不一致**：回调验签（WechatCallbackVerifier）没有公钥时
             * 是 fail-closed（每一条回调都被拒），应答验签却是 fail-open（不验就用）。
             *
             * 于是「没配公钥」的真实后果是：所有回调被拒 + 出站应答不设防，
             * 而日志上只有一行 WARN 和一堆「验签失败」——
             * 那一行 WARN 与「验过了」在日志里长得一模一样。
             *
             * 既然缺了它整条链本来就不能用，就让它在装配期把话说清楚。
             */
            throw new IllegalStateException("微信支付已开启但未配置微信支付公钥"
                    + "（shop.pay.wechat.platform-public-key 或 -path）—— "
                    + "缺了它回调一条都验不过，且出站应答不设防，拒绝装配");
        }
        return new WechatApiV3Signer(mchId, serialNo,
                pemOf(privateKeyPem, privateKeyPath, "shop.pay.wechat.private-key"),
                publicKey);
    }

    /** 协议：签名、验应答、paySign（W2 S5 · 渠道接入统一封装设计 §6.3） */
    @Bean
    public WechatV3Protocol wechatV3Protocol(WechatApiV3Signer signer) {
        return new WechatV3Protocol(signer);
    }

    /** 发送口：协议 + 记录型客户端 + 主备域名切换。所有微信出站 HTTP 都经这里（RecordingClientOnlyTest） */
    @Bean
    public WechatV3Client wechatV3Client(WechatV3Protocol protocol, RecordingChannelClient http,
                                         @Value("${shop.pay.wechat.read-timeout-seconds:15}") int readTimeoutSeconds) {
        return new WechatV3Client(protocol, http, Duration.ofSeconds(readTimeoutSeconds));
    }

    /** 进件（{@code WechatApplymentGateway}）仍用旧的 {@link ChannelClient} 外形；底下同一个发送口，进件请求也落原文 */
    @Bean("wechatChannelClient")
    public ChannelClient wechatChannelClient(WechatV3Client client) {
        return new WechatChannelClient(client);
    }

    @Bean
    public WechatErrorMapper wechatErrorMapper() {
        return new WechatErrorMapper();
    }

    /**
     * 直连商户号处理器（{@code MERCHANT_OWN}）。<b>两个条件都要满足</b>：本 @Configuration 挂着 {@code enabled=true}，
     * 方法上再挂 {@code mode=direct}。{@code @ConditionalOnProperty} 不可重复 —— 类上叠两个只有一个生效，
     * {@code enabled=false} 时 bean 照样被创建、再去要一个不存在的客户端，整个应用起不来。嵌在带条件的 @Configuration 里才是真正的「与」。
     */
    @Bean
    @ConditionalOnProperty(name = "shop.pay.wechat.mode", havingValue = "direct")
    public WechatDirectChannel wechatDirectChannel(
            WechatV3Client client,
            WechatV3Protocol protocol,
            @Value("${shop.pay.wechat.mchid:}") String mchId,
            @Value("${shop.pay.wechat.appid:}") String appId,
            @Value("${shop.pay.wechat.notify-url:}") String notifyUrl,
            @Value("${shop.pay.wechat.refund-notify-url:}") String refundNotifyUrl) {
        return new WechatDirectChannel(client, protocol, mchId, appId, notifyUrl, refundNotifyUrl);
    }

    /**
     * 电商收付通处理器（{@code SERVICE_PROVIDER}）。与直连按 {@code mode} 二选一 —— 凭据是一套（mchid 即 sp_mchid）；
     * 两个处理器的键接入模式不同，同时装上也不会互相顶替，但按渠道名问「用哪一种」就答不出来（{@code ChannelContextFactory} 拒绝）。
     */
    @Bean
    @ConditionalOnProperty(name = "shop.pay.wechat.mode", havingValue = "ecommerce")
    public WechatEcommerceChannel wechatEcommerceChannel(
            WechatV3Client client,
            @Value("${shop.pay.wechat.mchid:}") String spMchId,
            PaymentAccountLookup accounts,
            PayChannelMasterService channelMaster) {
        // 查单 / 查退款要的 sub_mchid 由支付域按交易查（F12 · R14）
        return new WechatEcommerceChannel(client, spMchId, accounts, channelMaster::supportsSubsidy);
    }

    /**
     * 内容优先，其次路径。两个都配了不去猜：内容是显式注入（密钥管理系统给的），路径是挂载文件，只有这一条规则。
     * 路径读不出来时装配期就炸：容忍它的话，服务会带着空密钥起来，到第一次真实下单才失败。
     */
    private static String pemOf(String inline, String path, String key) {
        if (inline != null && !inline.isBlank()) {
            return inline;
        }
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            // 不带出路径内容，只带路径本身 —— 它不是秘密，而内容是
            throw new IllegalStateException(key + "-path 指向的文件读不出来：" + path, e);
        }
    }
}
