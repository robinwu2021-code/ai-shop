package ai.neargo.shop.approval.opskit;

import ai.neargo.opskit.connector.approval.ApprovalClient;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * 启动时登记每个已启用业务的默认流程（B6）：审批 1 步（{@code PERM:<审批权限码>}，允许本人审批）→ 执行节点 {@code <subjectType>.apply}。
 * <b>只在不存在时创建</b>，不覆盖运营在流程配置里改过的版本。登记失败只记错误、不阻止启动 —— OpsKit 暂不可用时业务照样起来。
 */
public class OpsKitProcessRegistrar {

    private static final Logger log = LoggerFactory.getLogger(OpsKitProcessRegistrar.class);

    private final ApprovalClient client;
    private final ApprovalSubjects subjects;
    private final OpsKitProperties props;

    public OpsKitProcessRegistrar(ApprovalClient client, ApprovalSubjects subjects, OpsKitProperties props) {
        this.client = client;
        this.subjects = subjects;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerDefaults() {
        for (ApprovalSubject s : subjects.all()) {
            try {
                var r = client.ensureProcess(s.processCode(), defaultTemplate(s, props.actionTimeoutMinutes()));
                log.info("[opskit] 流程 {} {}（版本 {}）", s.processCode(), r.created() ? "已创建" : "已存在，未覆盖", r.version());
            } catch (RuntimeException e) {
                log.error("[opskit] 流程 {} 登记失败：{}", s.processCode(), e.getMessage());
            }
        }
    }

    static Map<String, Object> defaultTemplate(ApprovalSubject s, int actionTimeoutMinutes) {
        return Map.of(
                "code", s.processCode(),
                "name", Map.of("zh", s.processName()),
                "steps", List.of(
                        Map.of("code", "review", "type", "APPROVAL", "name", Map.of("zh", "审核"),
                                "approver", Map.of("kind", "PERM", "value", s.approverPerm()), "mode", "ANY"),
                        Map.of("code", "apply", "type", "ACTION", "name", Map.of("zh", "生效"),
                                "action", Map.of("code", ApprovalSubjects.actionOf(s), "timeoutMinutes", actionTimeoutMinutes))),
                "rules", Map.of("selfApproval", true, "crossStepExclusive", false));
    }
}
