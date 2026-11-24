package ai.neargo.shop.approval.opskit;

import ai.neargo.opskit.connector.Envelope;
import ai.neargo.shop.spi.notify.OpsAlertPort;
import ai.neargo.shop.spi.platform.OpsStaffPort;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * 新待办 → 站内消息（TDD-OpsKit审批对接 §1.2，契约 §4.2 K3）。{@code task.created} 带 {@code candidates[]}：
 * {@code perm:<码>}（OpsKit 已按通配展开，同时带 {@code perm:a:b:*} 等）、{@code member:} / {@code group:} / {@code role:}。
 * ai-shop 审批人一律配 PERM，所以只认<b>不含通配</b>的 {@code perm:} 键，按权限码反查运营员工；其余键忽略。
 * 失败只记日志：通知是尽力而为，不能让回调失败重投。
 */
public class OpsKitTodoNotifier {

    private static final Logger log = LoggerFactory.getLogger(OpsKitTodoNotifier.class);
    static final String LINK = "/approvals?tab=todo";

    private final OpsStaffPort staff;
    private final OpsAlertPort alerts;

    public OpsKitTodoNotifier(OpsStaffPort staff, OpsAlertPort alerts) {
        this.staff = staff;
        this.alerts = alerts;
    }

    public void taskCreated(Envelope e) {
        JsonNode d = e.data();
        String taskNo = d.path("taskNo").asString("");
        if (taskNo.isEmpty()) {
            return;
        }
        Set<String> receivers = new LinkedHashSet<>();
        for (String perm : perms(d.path("candidates"))) {
            receivers.addAll(staff.staffNosWithPerm(perm));
        }
        if (receivers.isEmpty()) {
            return;
        }
        JsonNode subject = d.path("subject");
        String title = subject.path("display").path("title").asString(subject.path("ref").asString(taskNo));
        String process = d.path("processName").asString(d.path("processCode").asString(""));
        try {
            alerts.approvalTodo(new OpsAlertPort.ApprovalTodo(taskNo, title, process), List.copyOf(receivers), LINK);
        } catch (RuntimeException ex) {
            log.warn("[opskit] 待办 {} 站内消息失败：{}", taskNo, ex.toString());
        }
    }

    static List<String> perms(JsonNode candidates) {
        Set<String> out = new LinkedHashSet<>();
        if (candidates != null && candidates.isArray()) {
            for (JsonNode c : candidates) {
                String key = c.asString("");
                if (key.startsWith("perm:") && !key.endsWith("*")) {
                    out.add(key.substring("perm:".length()));
                }
            }
        }
        return List.copyOf(out);
    }
}
