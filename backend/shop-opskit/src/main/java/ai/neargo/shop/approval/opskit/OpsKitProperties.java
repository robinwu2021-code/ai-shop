package ai.neargo.shop.approval.opskit;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpsKit 审批对接配置（TDD-OpsKit审批对接 §1.2）。凭证经部署环境变量注入，<b>不进仓库</b>。
 *
 * @param enabled              总开关（默认关）
 * @param baseUrl              OpsKit 基址，含 {@code /api/opskit/v1}，如 {@code http://192.168.3.80:8090/api/opskit/v1}
 * @param workspace            空间，默认 {@code ext:PLATFORM}
 * @param credential           应用凭证（{@code OPSKIT_APP_AISHOP_CREDENTIAL}）
 * @param signingSecret        webhook 签名密钥（{@code OPSKIT_APP_AISHOP_SIGNING_SECRET}）
 * @param timeout              单次调用超时，默认 5 秒
 * @param enabledSubjects      已切到审批的业务对象类型，如 {@code shop.store_opening}；不在列表里的业务走原流程
 * @param actionTimeoutMinutes 默认流程里执行节点的超时，默认 10 分钟
 */
@ConfigurationProperties(prefix = "shop.opskit")
public record OpsKitProperties(boolean enabled, String baseUrl, String workspace, String credential, String signingSecret,
                               Duration timeout, Set<String> enabledSubjects, Integer actionTimeoutMinutes) {

    public OpsKitProperties {
        workspace = blank(workspace) ? "ext:PLATFORM" : workspace.trim();
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        enabledSubjects = enabledSubjects == null ? Set.of() : Set.copyOf(enabledSubjects);
        actionTimeoutMinutes = actionTimeoutMinutes == null ? 10 : actionTimeoutMinutes;
        if (enabled && (blank(baseUrl) || blank(credential) || blank(signingSecret))) {
            throw new IllegalStateException("shop.opskit.enabled=true 需要 base-url、credential、signing-secret");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
