package ai.neargo.shop.approval.opskit;

import ai.neargo.opskit.connector.approval.ApprovalClient;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * 启动时登记每个已启用业务的默认流程（B6）：按业务的等级生成审批步骤（{@link #defaultTemplate}），最后是执行节点 {@code <subjectType>.apply}。
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

    /**
     * 按等级生成默认流程（审核需求清单 §8）：默认可以本人审批、多步也允许同一人（用户 2026-11-23 定）；
     * 业务声明 {@link ApprovalSubject#separateApprovers()} 的，提交人不得审批、各步不同人（Q12）。
     * <ul>
     *   <li>一级：审核（72 小时）→ 生效</li>
     *   <li>二级：审核（24 小时）→ 复核（仅当送审字段 {@code recheck=true}）→ 生效</li>
     *   <li>三级：业务审核 → 复核（固定，各 24 小时）→ 生效</li>
     * </ul>
     */
    static Map<String, Object> defaultTemplate(ApprovalSubject s, int actionTimeoutMinutes) {
        var level = s.level();
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(approval("review", level == ApprovalSubject.Level.FUNDS ? "业务审核" : "审核", s.approverPerm(), level.dueHours(), null));
        if (level == ApprovalSubject.Level.IMPORTANT) {
            steps.add(approval("recheck", "复核", s.recheckPerm(), level.dueHours(),
                    Map.of("field", ApprovalSubject.RECHECK_FIELD, "op", "TRUE")));
        } else if (level == ApprovalSubject.Level.FUNDS) {
            steps.add(approval("recheck", "复核", s.recheckPerm(), level.dueHours(), null));
        }
        steps.add(Map.of("code", "apply", "type", "ACTION", "name", Map.of("zh", "生效"),
                "action", Map.of("code", ApprovalSubjects.actionOf(s), "timeoutMinutes", actionTimeoutMinutes)));
        return Map.of(
                "code", s.processCode(),
                "name", Map.of("zh", s.processName()),
                "steps", List.copyOf(steps),
                "rules", Map.of("selfApproval", !s.separateApprovers(), "crossStepExclusive", s.separateApprovers()),
                "note", "业务登记的默认流程（" + label(level) + "）");
    }

    private static Map<String, Object> approval(String code, String name, String perm, int dueHours, Map<String, Object> when) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("code", code);
        step.put("type", "APPROVAL");
        step.put("name", Map.of("zh", name));
        step.put("approver", Map.of("kind", "PERM", "value", perm));
        step.put("mode", "ANY");
        step.put("dueHours", dueHours);
        if (when != null) {
            step.put("when", when);
        }
        return step;
    }

    private static String label(ApprovalSubject.Level level) {
        return switch (level) {
            case GENERAL -> "一级 · 一般";
            case IMPORTANT -> "二级 · 重要";
            case FUNDS -> "三级 · 资金与密钥";
        };
    }
}
