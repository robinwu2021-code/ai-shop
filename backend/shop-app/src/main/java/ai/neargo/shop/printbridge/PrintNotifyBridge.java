package ai.neargo.shop.printbridge;

import ai.neargo.shop.event.*;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.notify.PushSender;
import ai.neargo.shop.print.api.*;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.util.*;

@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "shop.print.enabled",
        havingValue = "true")
@Component
public class PrintNotifyBridge implements OutboxConsumer {
    private final MessageService messages;
    private final PrintNotificationRecipientsPort recipients;
    private final PushSender push;
    private final ObjectMapper json;

    public PrintNotifyBridge(
            MessageService m, PrintNotificationRecipientsPort r, PushSender p, ObjectMapper j) {
        messages = m;
        recipients = r;
        push = p;
        json = j;
    }

    public String handlerId() {
        return "print-notify";
    }

    public boolean supports(String type) {
        return Set.of("PRINT_BATCH_RESOLVED", "PRINT_INCIDENT_OPENED", "PRINT_INCIDENT_RESOLVED")
                .contains(type);
    }

    public void consume(SysOutbox row) {
        PrintScope scope;
        String title, body, target;
        boolean sendPush = false;
        var roleKeys = new HashSet<String>();
        roleKeys.add("STORE_MANAGER");
        if (row.getEventType().equals("PRINT_BATCH_RESOLVED")) {
            var e = json.readValue(row.getPayload(), PrintBatchResolved.class);
            scope = e.scope();
            target = e.batchNo();
            var p = json.readTree(e.notificationPolicy().canonicalJson());
            if (!p.path("onBatchResolved").asBoolean(false)) return;
            roleKeys.clear();
            p.path("recipientRoleKeys").forEach(n -> roleKeys.add(n.asText()));
            for (var n : p.path("channels")) if (n.asText().equals("PUSH")) sendPush = true;
            title =
                    e.resolvedStatus().equals("REPLACEMENT_RESOLVED")
                            ? "打印问题已由补票解决"
                            : e.batchKind().equals("REPRINT")
                                    ? "补打已完成"
                                    : e.batchKind().equals("EXTRA") ? "加印已完成" : "打印已完成";
            body = "批次 " + target + " 已有明确完成证据，可在打印任务中查看。";
        } else if (row.getEventType().equals("PRINT_INCIDENT_OPENED")) {
            var e = json.readValue(row.getPayload(), PrintIncidentOpened.class);
            scope = e.scope();
            target = e.targetNo();
            var policy = json.readTree(e.notificationPolicy().canonicalJson());
            if (!policy.path("onIncident").asBoolean(false)) return;
            roleKeys.clear();
            policy.path("recipientRoleKeys").forEach(n -> roleKeys.add(n.asText()));
            for (var n : policy.path("channels")) if (n.asText().equals("PUSH")) sendPush = true;
            title = "打印异常待处理";
            body = "任务或设备 " + target + " 需要核实，请查看打印中心。";
        } else {
            var e = json.readValue(row.getPayload(), PrintIncidentResolved.class);
            scope = e.scope();
            target = e.incidentNo();
            var policy = json.readTree(e.notificationPolicy().canonicalJson());
            if (!policy.path("onIncident").asBoolean(false)) return;
            roleKeys.clear();
            policy.path("recipientRoleKeys").forEach(n -> roleKeys.add(n.asText()));
            for (var n : policy.path("channels")) if (n.asText().equals("PUSH")) sendPush = true;
            title = "打印异常已解决";
            body = "异常 " + target + " 已有处置证据。";
        }
        String link =
                "/pages/print-center/index?storeNo="
                        + java.net.URLEncoder.encode(
                                scope.storeNo(), java.nio.charset.StandardCharsets.UTF_8);
        for (String receiver : recipients.recipients(scope, roleKeys)) {
            messages.pushTo(
                    "STAFF",
                    receiver,
                    MessageService.SYSTEM,
                    title,
                    body,
                    link,
                    "PRINT:" + PrintHashes.sha256(row.getEventNo() + ":" + receiver));
            if (sendPush) push.notify("STAFF", receiver, title, body, link);
        }
    }
}
