package ai.neargo.shop.pay.channel;

import ai.neargo.shop.pay.channel.master.PayChannelMasterService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 微信支付凭据的装配点。
 *
 * <h2>为什么签名器要单独成一个 bean</h2>
 * 它同时被两个人要：{@link WechatChannelClient}（签请求）与
 * {@link WechatDirectPayGateway}（算小程序 paySign）。
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
        return new WechatApiV3Signer(mchId, serialNo,
                pemOf(privateKeyPem, privateKeyPath, "shop.pay.wechat.private-key"),
                pemOf(platformPublicKeyPem, platformPublicKeyPath, "shop.pay.wechat.platform-public-key"));
    }

    /**
     * 内容优先，其次路径。
     *
     * <p><b>两个都配了不去猜</b>：直接用内容，但那不是「随便选一个」——
     * 内容是显式注入（密钥管理系统给的），路径是挂载文件，
     * 两者不一致时用哪个都可能是错的，而选错的表现是签名失败，
     * 排查的人会去核那个<b>没被用到</b>的那份。所以只有一条规则，写在这里。
     *
     * <p>路径读不出来时<b>装配期就炸</b>：容忍它的话，服务会带着空密钥起来，
     * 到第一次真实下单才失败。
     */
    /**
     * 直连商户号网关。<b>两个条件都要满足</b>：本 @Configuration 挂着
     * {@code enabled=true}，方法上再挂 {@code mode=direct}。
     *
     * <p><b>为什么不是类上叠两个 {@code @ConditionalOnProperty}</b>：
     * 那个注解<b>不可重复</b>，叠两个只有一个生效。
     * 于是 {@code enabled=false} 而 {@code mode=direct}（yml 里的默认值）时，
     * bean 照样被创建，然后去要一个不存在的 {@code wechatChannelClient} ——
     * <b>整个应用起不来，而且报错指向注入，不指向那个开关</b>。
     * 嵌在带条件的 @Configuration 里则是真正的「与」。
     */
    @Bean
    @ConditionalOnProperty(name = "shop.pay.wechat.mode", havingValue = "direct")
    public WechatDirectPayGateway wechatDirectPayGateway(
            @Qualifier("wechatChannelClient") ChannelClient client,
            PayChannelMasterService channelMaster,
            ChannelMessageRecorder recorder,
            WechatApiV3Signer signer,
            @Value("${shop.pay.wechat.mchid:}") String mchId,
            @Value("${shop.pay.wechat.appid:}") String appId,
            @Value("${shop.pay.wechat.notify-url:}") String notifyUrl) {
        return new WechatDirectPayGateway(client, channelMaster, recorder, signer,
                mchId, appId, notifyUrl);
    }

    /**
     * 电商收付通（服务商）网关。与上面**互斥** —— 两者的 {@code payChannel()}
     * 都是 {@code WECHAT}，同时装上会被 {@link PayGatewayRouter} 在启动时拦下。
     */
    @Bean
    @ConditionalOnProperty(name = "shop.pay.wechat.mode", havingValue = "ecommerce")
    public WechatPayGateway wechatPayGateway(
            @Qualifier("wechatChannelClient") ChannelClient client,
            PayChannelMasterService channelMaster,
            ChannelMessageRecorder recorder) {
        return new WechatPayGateway(client, channelMaster, recorder);
    }

    private static String pemOf(String inline, String path, String key) {
        if (inline != null && !inline.isBlank()) {
            return inline;
        }
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path));
        } catch (java.io.IOException e) {
            // 不带出路径内容，只带路径本身 —— 它不是秘密，而内容是
            throw new IllegalStateException(key + "-path 指向的文件读不出来：" + path, e);
        }
    }
}
