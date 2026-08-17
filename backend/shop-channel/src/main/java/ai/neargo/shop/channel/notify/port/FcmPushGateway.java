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
 * Google FCM 推送 gateway（海外 Android，设计 · 需求 2）。
 *
 * <p><b>P2 骨架，真实 HTTP 在 P3 接入</b>：类与路由接线到位（{@code provider()=FCM}，
 * {@code PushRouter} 已能把 FCM 设备分发到这里），凭据校验也在，唯独 {@link #push} 的
 * FCM HTTP v1（OAuth2 服务账号 JWT + {@code /v1/projects/{id}/messages:send}）留给 P3。
 *
 * <p>默认不启用（{@code shop.push.fcm.stub} 默认 true → 本 bean 不创建，FCM 设备回落桩）。
 * 一旦 {@code shop.push.fcm.stub=false} 而 P3 未接入，这里**显式抛错**而不是静默丢 ——
 * 站内信兜底，日志留痕，比「以为发了其实没发」强。
 */
@Component
@ConditionalOnProperty(name = "shop.push.fcm.stub", havingValue = "false")
public class FcmPushGateway implements PushGateway {

    private static final Logger log = LoggerFactory.getLogger(FcmPushGateway.class);

    private final String projectId;

    public FcmPushGateway(@Value("${shop.push.fcm.project-id:}") String projectId,
                          @Value("${shop.push.fcm.credentials:}") String credentials) {
        this.projectId = projectId;
        require(projectId, "FCM_PROJECT_ID");
        require(credentials, "FCM_CREDENTIALS");
        log.info("[push] FCM 通道已启用 projectId={}（P2 骨架，HTTP 接入见 P3）", projectId);
    }

    private static void require(String v, String envName) {
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "FCM 通道已开启（shop.push.fcm.stub=false）但缺少配置：" + envName);
        }
    }

    @Override
    public String provider() {
        return PushProvider.FCM;
    }

    @Override
    public SendResult push(String clientId, String title, String body, String link, String level) {
        // P3 在此实现 FCM HTTP v1：取服务账号 access token → messages:send（data + notification）
        throw new PushException("FCM 通道骨架就位，HTTP 接入待 P3（projectId=" + projectId + "）", false);
    }
}
