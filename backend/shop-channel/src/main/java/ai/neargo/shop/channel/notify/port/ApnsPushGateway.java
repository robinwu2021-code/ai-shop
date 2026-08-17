package ai.neargo.shop.channel.notify.port;

import ai.neargo.shop.spi.notify.PushGateway;
import ai.neargo.shop.spi.notify.PushProvider;
import ai.neargo.shop.spi.notify.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Apple APNs 推送 gateway（iOS 直连，设计 · 需求 2）。
 *
 * <p><b>P2 骨架，真实 HTTP 在 P3 接入</b>：个推已透传 APNs 够日常用；这条直连是给
 * 「脱离聚合商依赖 / 富媒体 / 精细化」留的口子。类与路由接线到位，{@link #push} 的
 * APNs HTTP/2 + JWT（ES256，{@code .p8} 私钥签发 provider token）留给 P3。
 *
 * <p>默认不启用（{@code shop.push.apns.stub} 默认 true）。开启但未接入时显式抛错，
 * 由站内信兜底、日志留痕 —— 同 {@link FcmPushGateway}。
 */
@Component
@ConditionalOnProperty(name = "shop.push.apns.stub", havingValue = "false")
public class ApnsPushGateway implements PushGateway {

    private static final Logger log = LoggerFactory.getLogger(ApnsPushGateway.class);

    private final String topic;

    public ApnsPushGateway(@Value("${shop.push.apns.team-id:}") String teamId,
                           @Value("${shop.push.apns.key-id:}") String keyId,
                           @Value("${shop.push.apns.private-key:}") String privateKey,
                           @Value("${shop.push.apns.topic:}") String topic) {
        this.topic = topic;
        require(teamId, "APNS_TEAM_ID");
        require(keyId, "APNS_KEY_ID");
        require(privateKey, "APNS_PRIVATE_KEY");
        require(topic, "APNS_TOPIC");
        log.info("[push] APNs 通道已启用 topic={}（P2 骨架，HTTP 接入见 P3）", topic);
    }

    private static void require(String v, String envName) {
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "APNs 通道已开启（shop.push.apns.stub=false）但缺少配置：" + envName);
        }
    }

    @Override
    public String provider() {
        return PushProvider.APNS;
    }

    @Override
    public SendResult push(String clientId, String title, String body, String link, String level) {
        // P3 在此实现 APNs HTTP/2：ES256 JWT provider token → POST /3/device/{token}（aps + link）
        throw new PushException("APNs 通道骨架就位，HTTP 接入待 P3（topic=" + topic + "）", false);
    }
}
