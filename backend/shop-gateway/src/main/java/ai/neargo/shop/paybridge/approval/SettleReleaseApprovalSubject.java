package ai.neargo.shop.paybridge.approval;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.pay.SettleBatchService;
import ai.neargo.shop.pay.SettleBatchService.BatchRevision;
import ai.neargo.shop.pay.entity.StlSettleBatch;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * T7a · 结算批次放行（风控 / 对账挂起后）接入审批（方案-第一批审批工作流 T7）。二级：审核 {@code finance:settle:execute}。
 * 修订号 = 批次 {@code updated_at}。生效 = 原「放行」；审批未通过 = 继续挂起，原因写入批次（Q4），不自动再送，
 * 由运营在批次页再点「放行」重新送审。
 */
@Component
public class SettleReleaseApprovalSubject implements ApprovalSubject {

    private static final Logger log = LoggerFactory.getLogger(SettleReleaseApprovalSubject.class);
    public static final String SUBJECT_TYPE = "shop.settle_release";

    private final SettleBatchService batches;

    public SettleReleaseApprovalSubject(SettleBatchService batches) {
        this.batches = batches;
    }

    @Override public String subjectType() { return SUBJECT_TYPE; }
    @Override public String processCode() { return "shop_settle_release"; }
    @Override public String processName() { return "结算批次放行"; }
    @Override public String approverPerm() { return Perms.FINANCE_SETTLE_EXECUTE; }
    @Override public Level level() { return Level.IMPORTANT; }

    @Override
    public Snapshot snapshot(String batchNo) {
        var b = find(batchNo).batch();
        return new Snapshot("结算放行 · " + batchNo, "商户 " + b.entityNo() + " · 净额 " + PayApprovalSupport.money(b.netMinor()),
                Set.of("merchant:" + b.entityNo()), Map.of("netMinor", b.netMinor()),
                SettleFacts.of(b, List.of(Fact.change("批次状态", "挂起", "可放款"),
                        Fact.of("挂起原因", b.blockedReason() == null ? "—" : b.blockedReason()))));
    }

    @Override
    public void apply(String batchNo, long revision) {
        BatchRevision r = find(batchNo);
        String status = r.batch().status();
        if (StlSettleBatch.RECONCILED.equals(status) || StlSettleBatch.RELEASED.equals(status)) return; // 已放行（重投或已有人处理）
        if (!StlSettleBatch.BLOCKED.equals(status) && !StlSettleBatch.RECONCILING.equals(status) || r.revision() != revision) {
            throw new ApprovalApplyException(ApprovalApplyException.STALE, "批次已变化：status=" + status);
        }
        try {
            PayApprovalSupport.asApprovalService(() -> batches.approve(batchNo, PayApprovalSupport.ACTOR, "审批通过"));
        } catch (RuntimeException e) {
            throw PayApprovalSupport.failure(e);
        }
    }

    @Override
    public void onClosed(String batchNo, long revision, Outcome outcome, String reason) {
        BatchRevision r = find(batchNo);
        if (!StlSettleBatch.BLOCKED.equals(r.batch().status()) || r.revision() != revision) return;
        String why = reason == null || reason.isBlank() ? "审批未通过（" + outcome + "），继续挂起" : reason;
        try {
            PayApprovalSupport.asApprovalService(() -> batches.hold(batchNo, PayApprovalSupport.ACTOR, why));
        } catch (RuntimeException e) {
            log.warn("[approval] 批次 {} 挂起回写失败：{}", batchNo, e.toString());
        }
    }

    BatchRevision find(String batchNo) {
        return batches.revision(batchNo).orElseThrow(() -> new ApprovalApplyException("NOT_FOUND", "结算批次不存在：" + batchNo));
    }
}
