package ai.neargo.shop.message.notify.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.entity.MsgSceneChannel;
import ai.neargo.shop.message.entity.StoredValueNotifyTask;
import ai.neargo.shop.message.mapper.StoredValueNotifyTaskMapper;
import ai.neargo.shop.message.notify.SceneChannelRouting;
import ai.neargo.shop.message.notify.StoredValueNotificationService;
import ai.neargo.shop.message.notify.WxSubscribeSender;
import ai.neargo.shop.spi.notify.WxSubscribePort;
import ai.neargo.shop.spi.notify.WxSubscribePort.WxSubscribeException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Stores intent before acknowledging the event; network calls run after the claim transaction commits. */
@Service
public class StoredValueNotificationServiceImpl implements StoredValueNotificationService {
    private static final String SCENE = WxSubscribePort.SCENE_STORED_VALUE_REDEEMED;
    private final StoredValueNotifyTaskMapper tasks;
    private final MessageService messages;
    private final WxSubscribeSender sender;
    private final WxSubscribePort port;
    private final SceneChannelRouting routing;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public StoredValueNotificationServiceImpl(StoredValueNotifyTaskMapper tasks, MessageService messages,
            WxSubscribeSender sender, WxSubscribePort port, SceneChannelRouting routing, ObjectMapper json,
            PlatformTransactionManager transactionManager) {
        this.tasks = tasks;
        this.messages = messages;
        this.sender = sender;
        this.port = port;
        this.routing = routing;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public Capability capability() {
        if (!port.configuredForApp(SCENE)) {
            return new Capability("WXSUB", false, null, "TEMPLATE_UNCONFIGURED");
        }
        if (!routing.enabled(SCENE, MsgSceneChannel.AUD_C_USER, MsgSceneChannel.CH_WXSUB)) {
            return new Capability("WXSUB", false, null, "SCENE_DISABLED");
        }
        return new Capability("WXSUB", true, port.templateId(SCENE), null);
    }

    @Override
    @Transactional
    public void enqueue(String tenant, String eventNo, String usageNo, String recipient, String storeNo,
                        String documentNo, Map<String, String> values) {
        // Existing v1 entitlement ingress is explicitly MAIN-only; no client-controlled tenant is accepted.
        if (!"MAIN".equals(tenant)) { throw new IllegalArgumentException("Unsupported event tenant"); }
        boolean hasRecipient = recipient != null && !recipient.isBlank();
        recipient = hasRecipient ? recipient : "-";
        StoredValueNotifyTask task = new StoredValueNotifyTask();
        task.setTenantNo(tenant);
        task.setUsageNo(usageNo);
        task.setRecipient(recipient);
        task.setScene(SCENE);
        task.setEventNo(eventNo);
        task.setStoreNo(storeNo);
        task.setDocumentNo(documentNo);
        task.setValuesJson(json.writeValueAsString(values));
        if (tasks.insertOnce(task) == 0) { return; }
        StoredValueNotifyTask stored = tasks.selectOne(Wrappers.<StoredValueNotifyTask>lambdaQuery()
                .eq(StoredValueNotifyTask::getTenantNo, tenant).eq(StoredValueNotifyTask::getUsageNo, usageNo)
                .eq(StoredValueNotifyTask::getRecipient, recipient).eq(StoredValueNotifyTask::getScene, SCENE));
        String reason = hasRecipient ? capability().reasonCode() : "NO_RECIPIENT";
        WxSubscribeSender.Reservation reserved = reason == null ? sender.reserve(SCENE, recipient) : null;
        if (reason == null) { reason = reserved.reasonCode(); }
        tasks.update(null, Wrappers.<StoredValueNotifyTask>lambdaUpdate()
                .eq(StoredValueNotifyTask::getId, stored.getId())
                .set(StoredValueNotifyTask::getStatus, reason == null ? "QUEUED" : "SKIPPED")
                .set(StoredValueNotifyTask::getReasonCode, reason)
                .set(StoredValueNotifyTask::getOpenId, reserved == null ? null : reserved.openId())
                .set(StoredValueNotifyTask::getTemplateId, reserved == null ? null : reserved.templateId())
                .set(StoredValueNotifyTask::getNextAttemptAt, LocalDateTime.now()));
        if (hasRecipient) { messages.push(recipient, MessageService.TRADE, "储值消费成功",
                values.get("store") + " · " + values.get("title") + " · " + values.get("amount"),
                page(storeNo, documentNo), "SVN:" + stored.getId()); }
    }

    @Override
    public int dispatchDue() {
        return DataScopeContext.executeWithoutScope(() -> {
            LocalDateTime now = LocalDateTime.now();
            tasks.update(null, Wrappers.<StoredValueNotifyTask>lambdaUpdate()
                    .eq(StoredValueNotifyTask::getStatus, "SENDING")
                    .lt(StoredValueNotifyTask::getUpdatedAt, now.minusMinutes(5))
                    .set(StoredValueNotifyTask::getStatus, "UNKNOWN")
                    .set(StoredValueNotifyTask::getReasonCode, "WORKER_INTERRUPTED"));
            var due = tasks.selectList(Wrappers.<StoredValueNotifyTask>lambdaQuery()
                    .in(StoredValueNotifyTask::getStatus, "QUEUED", "RETRY")
                    .le(StoredValueNotifyTask::getNextAttemptAt, now)
                    .orderByAsc(StoredValueNotifyTask::getId).last("limit 50"));
            for (StoredValueNotifyTask task : due) { dispatch(task); }
            return due.size();
        });
    }

    private void dispatch(StoredValueNotifyTask task) {
        Boolean claimed = tx.execute(status -> tasks.update(null, Wrappers.<StoredValueNotifyTask>lambdaUpdate()
                .eq(StoredValueNotifyTask::getId, task.getId())
                .in(StoredValueNotifyTask::getStatus, "QUEUED", "RETRY")
                .set(StoredValueNotifyTask::getStatus, "SENDING")
                .setSql("attempts = attempts + 1")
                .set(StoredValueNotifyTask::getUpdatedAt, LocalDateTime.now())) == 1);
        if (!Boolean.TRUE.equals(claimed)) { return; }
        String result = "SENT";
        String reason = null;
        try {
            if (!capability().available() || !task.getTemplateId().equals(port.templateId(SCENE))) {
                result = "SKIPPED";
                reason = "CONFIGURATION_CHANGED";
            } else {
                Map<String, String> values = json.readValue(task.getValuesJson(), new TypeReference<>() { });
                port.sendFielded(task.getOpenId(), SCENE, values, page(task.getStoreNo(), task.getDocumentNo()).substring(1));
            }
        } catch (WxSubscribeException e) {
            result = e.outcomeUnknown() ? "UNKNOWN"
                    : e.retryable() && task.getAttempts() < 2 ? "RETRY" : "FAILED";
            reason = e.outcomeUnknown() ? "OUTCOME_UNKNOWN" : e.retryable() ? "PROVIDER_TEMPORARY" : "PROVIDER_REJECTED";
        } catch (RuntimeException e) {
            result = "UNKNOWN";
            reason = "OUTCOME_UNKNOWN";
        }
        tasks.update(null, Wrappers.<StoredValueNotifyTask>lambdaUpdate()
                .eq(StoredValueNotifyTask::getId, task.getId()).eq(StoredValueNotifyTask::getStatus, "SENDING")
                .set(StoredValueNotifyTask::getStatus, result).set(StoredValueNotifyTask::getReasonCode, reason)
                .set(StoredValueNotifyTask::getUpdatedAt, LocalDateTime.now())
                .set(StoredValueNotifyTask::getNextAttemptAt, LocalDateTime.now().plusSeconds(
                        30L * (task.getAttempts() + 1))));
    }

    /** Registered C-side page; its read API verifies the authenticated owner again after login. */
    public static String page(String storeNo, String documentNo) {
        if(documentNo!=null&&!documentNo.isBlank())return "/pages/stored-value-records/index?focusDocumentNo="+encode(documentNo);
        return "/pages/stored-value-records/index?storeNo="+encode(storeNo);
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
