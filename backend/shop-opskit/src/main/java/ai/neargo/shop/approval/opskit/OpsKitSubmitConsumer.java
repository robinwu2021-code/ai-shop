package ai.neargo.shop.approval.opskit;

import ai.neargo.opskit.connector.OpsKitCallException;
import ai.neargo.opskit.connector.OpsKitConnector.OnBehalfOf;
import ai.neargo.opskit.connector.approval.ApprovalClient;
import ai.neargo.opskit.connector.approval.Fact;
import ai.neargo.opskit.connector.approval.Submit;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.event.outbox.RetryableOutboxException;
import ai.neargo.shop.spi.approval.ApprovalSubmitRequested;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 发件箱 → OpsKit 送审（B1）。至少一次投递：OpsKit 按幂等键 {@code subjectType:ref:revision} 去重，重投拿同一张单。
 * 网络错误 / 5xx / 429 抛 {@link RetryableOutboxException} 退避重试；其余 4xx（模板不存在、字段不合法）
 * 把关联记为 SUBMIT_FAILED 并记错误，不重试 —— 重试也不会变好。
 */
public class OpsKitSubmitConsumer implements OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(OpsKitSubmitConsumer.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    /** 拟定结果在 OpsKit 实例字段里的键；执行节点从这里取回（ActionContext.fields）。 */
    public static final String PROPOSAL_FIELD = "proposal";

    private final ApprovalClient client;
    private final ApprovalSubjects subjects;
    private final ApprovalLinkStore links;

    public OpsKitSubmitConsumer(ApprovalClient client, ApprovalSubjects subjects, ApprovalLinkStore links) {
        this.client = client;
        this.subjects = subjects;
        this.links = links;
    }

    @Override
    public String handlerId() {
        return "opskit-approval-submit";
    }

    @Override
    public boolean externalWorkflow() {
        return true;
    }

    @Override
    public boolean supports(String eventType) {
        return ApprovalSubmitRequested.TYPE.equals(eventType);
    }

    @Override
    public void consume(SysOutbox event) {
        JsonNode p = JSON.readTree(event.getPayload());
        String type = p.path("subjectType").asString();
        String ref = p.path("subjectRef").asString();
        long revision = p.path("revision").asLong();
        var row = links.find(type, ref, revision).orElse(null);
        if (row == null || !ApprovalLinkStore.PENDING_SUBMIT.equals(row.status())) {
            return; // 已投递过，或业务已撤销
        }
        var subject = subjects.find(type).orElse(null);
        if (subject == null) {
            links.markSubmitFailed(type, ref, revision, "业务未启用审批：" + type);
            log.error("[opskit] {} {} 未启用审批，送审丢弃", type, ref);
            return;
        }
        Map<String, Object> proposal = row.proposal() == null ? null : JSON.readValue(row.proposal(), MAP);
        var snap = proposal == null ? subject.snapshot(ref) : subject.snapshot(ref, proposal);
        var fields = new LinkedHashMap<String, Object>(snap.fields());
        fields.put("revision", revision);
        if (proposal != null) {
            fields.put(PROPOSAL_FIELD, proposal);
        }
        Submit submit = Submit.of(subject.processCode(), type, ref, snap.title(), OnBehalfOf.of(row.submittedBy(), row.submittedName()))
                .withSummary(snap.summary()).withFields(fields).withScopes(snap.scopes()).withFacts(facts(snap));
        try {
            var done = client.submit(submit, type + ":" + ref + ":" + revision);
            links.markSubmitted(type, ref, revision, done.approvalNo(), done.instanceNo());
            log.info("[opskit] 送审 {} {} revision {} → 实例 {} 审批单 {}{}", type, ref, revision, done.instanceNo(), done.approvalNo(),
                    done.replayed() ? "（重放）" : "");
        } catch (OpsKitCallException e) {
            if (e.retryable()) {
                throw new RetryableOutboxException("opskit submit " + e.status() + " " + e.errorKey());
            }
            links.markSubmitFailed(type, ref, revision, e.errorKey() + " " + e.getMessage());
            log.error("[opskit] 送审被拒 {} {} revision {}: HTTP {} {} trace {}", type, ref, revision, e.status(), e.errorKey(), e.traceId());
        }
    }

    /** 申请内容（契约 C8）：OpsKit 每单最多收 50 条，多出的截掉并在最后一条注明。 */
    static List<Fact> facts(ai.neargo.shop.spi.approval.ApprovalSubject.Snapshot snap) {
        var all = snap.facts().stream().map(f -> new Fact(f.label(), f.value(), f.before(), f.hint())).toList();
        if (all.size() <= MAX_FACTS) {
            return all;
        }
        var kept = new java.util.ArrayList<>(all.subList(0, MAX_FACTS - 1));
        kept.add(Fact.of("…", "另有 " + (all.size() - MAX_FACTS + 1) + " 项未列出"));
        return kept;
    }

    static final int MAX_FACTS = 50;
}
