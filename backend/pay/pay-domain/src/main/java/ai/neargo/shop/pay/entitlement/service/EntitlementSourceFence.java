package ai.neargo.shop.pay.entitlement.service;

import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.audit;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.changed;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.conflict;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.now;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.utc;

import ai.neargo.shop.pay.entitlement.entity.PayEntSourceFence;
import ai.neargo.shop.pay.mapper.entitlement.PayEntSourceFenceMapper;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort.SourceDecision;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort.State;
import ai.neargo.shop.spi.entitlement.SourceRef;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Acquire before grant/use rows. Certified cancellation and late prepare share this same pay lock. */
@Component
public class EntitlementSourceFence {
    private final PayEntSourceFenceMapper fences;
    private final ObjectMapper json;
    public EntitlementSourceFence(PayEntSourceFenceMapper fences, ObjectMapper json) {
        this.fences = fences;
        this.json = json;
    }
    public PayEntSourceFence find(EntitlementContext context, String purpose, String operationNo) {
        var row = fences.find(context.tenantNo(), purpose, operationNo);
        return row != null && operationNo.equals(row.getOperationNo()) ? row : null;
    }
    @Transactional(value = "payTxManager", propagation = Propagation.MANDATORY)
    public PayEntSourceFence lock(EntitlementContext context, String purpose, String operationNo,
                                  SourceRef source, String user, String entity) {
        if (!List.of("GRANT", "USE").contains(purpose)) throw new IllegalArgumentException("Invalid source purpose");
        var row = new PayEntSourceFence();
        audit(row, context);
        row.setPurpose(purpose);
        row.setOperationNo(operationNo);
        row.setSourceKey("GRANT".equals(purpose) ? source.grantKey() : source.useIntentKey());
        row.setSourceSnapshot(json.writeValueAsString(source));
        row.setUserNo(user);
        row.setEntityNo(entity);
        fences.claim(row);
        var original = fences.lock(context.tenantNo(), purpose, operationNo);
        if (original == null || !operationNo.equals(original.getOperationNo())
                || !original.getSourceKey().equals(row.getSourceKey())
                || !original.getUserNo().equals(user) || !original.getEntityNo().equals(entity)) conflict();
        requireSource(original, source);
        return original;
    }
    public void requireSource(PayEntSourceFence row, SourceRef source) {
        if (!json.readValue(row.getSourceSnapshot(), SourceRef.class).equals(source)) conflict();
    }
    public void requireDecision(PayEntSourceFence row, DecisionRef ref) {
        if (!cancelled(row)
                || !json.readValue(row.getDecisionSnapshot(), SourceDecision.class).decision().equals(ref)) {
            conflict();
        }
    }
    public boolean cancelled(PayEntSourceFence row) { return row != null && "CANCELLED".equals(row.getStatus()); }

    /** Only aggregate writers call this after validating terminal source truth and checking their own row. */
    @Transactional(value = "payTxManager", propagation = Propagation.MANDATORY)
    public void cancel(EntitlementContext context, PayEntSourceFence row, SourceDecision truth) {
        if (truth == null || truth.status() != State.CANCELLED || !row.getUserNo().equals(truth.userNo())
                || !row.getEntityNo().equals(truth.entityNo())) conflict();
        requireSource(row, truth.source());
        truth.decision().requireFor(truth.source(), "USE".equals(row.getPurpose())
                ? DecisionRef.DecisionType.USE_CANCEL : DecisionRef.DecisionType.GRANT_ABORT);
        if (cancelled(row)) {
            if (!json.readValue(row.getDecisionSnapshot(), SourceDecision.class).equals(truth)) conflict();
            return;
        }
        row.setStatus("CANCELLED");
        row.setDecisionSnapshot(json.writeValueAsString(truth));
        row.setCancelledAt(utc(truth.decision().occurredAt()));
        row.setUpdatedAt(now());
        row.setUpdatedBy(context.actorNo());
        changed(fences.updateById(row));
    }
}
