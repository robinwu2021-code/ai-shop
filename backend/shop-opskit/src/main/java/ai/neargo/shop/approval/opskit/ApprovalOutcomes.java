package ai.neargo.shop.approval.opskit;

import ai.neargo.opskit.connector.Reconciler;
import ai.neargo.opskit.connector.approval.ActionContext;
import ai.neargo.opskit.connector.approval.ApprovalActionException;
import ai.neargo.opskit.connector.approval.Finished;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** OpsKit 回调落到业务：执行节点 → {@link ApprovalSubject#apply}；流程结束 → {@code approval_link} 与 {@link ApprovalSubject#onClosed}。 */
public class ApprovalOutcomes {

    private static final Logger log = LoggerFactory.getLogger(ApprovalOutcomes.class);

    private final ApprovalSubjects subjects;
    private final ApprovalLinkStore links;

    public ApprovalOutcomes(ApprovalSubjects subjects, ApprovalLinkStore links) {
        this.subjects = subjects;
        this.links = links;
    }

    /** 执行节点。返回 = 生效成功（client 回复 CONFIRM）；抛 {@link ApprovalActionException} = 生效失败。 */
    public Map<String, Object> apply(ApprovalSubject subject, ActionContext ctx) {
        Object rev = ctx.field("revision");
        if (!(rev instanceof Number n)) {
            throw new ApprovalActionException(ApprovalApplyException.STALE, "送审字段缺少 revision");
        }
        try {
            Object proposal = ctx.field(OpsKitSubmitConsumer.PROPOSAL_FIELD);
            if (proposal instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) m;
                subject.apply(ctx.subjectRef(), n.longValue(), p);
            } else {
                subject.apply(ctx.subjectRef(), n.longValue());
            }
        } catch (ApprovalApplyException e) {
            log.warn("[opskit] {} {} 生效失败 {}: {}", ctx.subjectType(), ctx.subjectRef(), e.code(), e.getMessage());
            throw new ApprovalActionException(e.code(), e.getMessage());
        }
        log.info("[opskit] {} {} revision {} 已生效（{}）", ctx.subjectType(), ctx.subjectRef(), n, ctx.idempotencyKey());
        return Map.of();
    }

    /** 流程结束（instance.transitioned 进入 DONE / CANCELLED，或撤回）。 */
    public void finished(Finished f) {
        var subject = subjects.find(f.subjectType()).orElse(null);
        var row = links.forInstance(f.instanceNo(), f.subjectType(), f.subjectRef()).orElse(null);
        if (subject == null || row == null) {
            log.warn("[opskit] 结束回调找不到业务或关联：{} {} {} {}", f.subjectType(), f.subjectRef(), f.instanceNo(), f.result());
            return;
        }
        boolean changed = links.close(row.subjectType(), row.subjectRef(), row.revision(), f.instanceNo(), f.result().name(), f.reason());
        if (!changed) {
            return; // 重复回调，或已经结束
        }
        switch (f.result()) {
            case APPLIED, APPROVED -> { }
            case REJECTED -> subject.onClosed(row.subjectRef(), row.revision(), ApprovalSubject.Outcome.REJECTED, f.reason());
            case WITHDRAWN -> subject.onClosed(row.subjectRef(), row.revision(), ApprovalSubject.Outcome.WITHDRAWN, f.reason());
            case CANCELLED -> subject.onClosed(row.subjectRef(), row.revision(), ApprovalSubject.Outcome.CANCELLED, f.reason());
        }
    }

    /**
     * 对账结论落地（回调丢失时补上）。与回调走同一段收尾逻辑：{@link #finished} 按「只从未结束推进」去重，回调与对账谁先到都只生效一次。
     * 还在审批中、或 OpsKit 查不到的不在这里处理：PENDING_SUBMIT 由 {@link OpsKitReconcileJob} 补投，PENDING 查不到只告警。
     */
    public void reconciled(ApprovalLinkStore.Row row, Reconciler.Outcome o) {
        Finished.Result r = switch (o.state()) {
            case REJECTED -> Finished.Result.REJECTED;
            case WITHDRAWN -> Finished.Result.WITHDRAWN;
            case APPROVED, CLOSED -> "DONE".equals(o.statusCategory())
                    ? ("applied".equals(o.status()) ? Finished.Result.APPLIED : Finished.Result.APPROVED)
                    : "CANCELLED".equals(o.statusCategory()) ? Finished.Result.CANCELLED : null;
            case IN_PROGRESS, NOT_FOUND -> null;
        };
        if (r == null) {
            if (o.state() == Reconciler.State.NOT_FOUND && ApprovalLinkStore.PENDING.equals(row.status())) {
                log.warn("[opskit] 对账：{} {} revision {} 已送审但 OpsKit 查不到，需人工核对", row.subjectType(), row.subjectRef(), row.revision());
            }
            return;
        }
        log.warn("[opskit] 对账补结论（回调丢过）：{} {} revision {} → {}", row.subjectType(), row.subjectRef(), row.revision(), r);
        finished(new Finished(o.instanceNo(), row.processCode(), row.subjectType(), row.subjectRef(), r, null, List.of()));
    }
}
