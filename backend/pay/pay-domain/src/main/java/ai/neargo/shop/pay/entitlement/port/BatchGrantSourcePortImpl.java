package ai.neargo.shop.pay.entitlement.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.BatchSnapshot;
import ai.neargo.shop.pay.mapper.entitlement.EntIssueBatchMapper;
import ai.neargo.shop.pay.entitlement.service.EntitlementGrantWriter;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantCommand;
import ai.neargo.shop.spi.entitlement.EntitlementGrantSourcePort;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import org.springframework.stereotype.Component;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import ai.neargo.shop.pay.entitlement.entity.EntIssueBatch;
import tools.jackson.databind.ObjectMapper;

/** Pay-owned targeted batches use a persisted core audience snapshot, never the request's recipient list. */
@Component
public class BatchGrantSourcePortImpl implements EntitlementGrantSourcePort {
    private final EntIssueBatchMapper batches;
    private final ObjectMapper json;
    public BatchGrantSourcePortImpl(EntIssueBatchMapper batches, ObjectMapper json) { this.batches = batches; this.json = json; }
    @Override public boolean supports(SourceRef source) {
        return "PAY".equals(source.sourceSystem()) && "TARGETED".equals(source.sourceType());
    }
    @Override public SourceFact resolve(EntitlementContext context, GrantCommand c) {
        var row = batches.find(context.tenantNo(), c.entityNo(), c.sourceRef().sourceNo());
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var snapshot = json.readValue(row.getAudienceSnapshot(), BatchSnapshot.class);
        int index;
        try { index = Integer.parseInt(c.sourceRef().sourceLineNo()) - 1; }
        catch (RuntimeException invalid) { throw BizException.of(ErrorCode.CONFLICT); }
        if (index < 0 || index >= snapshot.audience().recipients().size()) throw BizException.of(ErrorCode.CONFLICT);
        var recipient = snapshot.audience().recipients().get(index);
        if (!recipient.eligible()) throw BizException.of(ErrorCode.CONFLICT);
        var expected = command(row.getBatchNo(), row.getEntityNo(), index, recipient.userNo(), snapshot);
        if (!expected.equals(c)) throw BizException.of(ErrorCode.CONFLICT);
        return new SourceFact(context.tenantNo(), expected, null);
    }
    @Override public SourceDecisionPort.SourceDecision decision(EntitlementContext context, SourceRef source) {
        // Resolve entity through the grant preparation instead; the terminal query uses the unique batch source.
        var candidates = batches.selectList(Wrappers.<EntIssueBatch>lambdaQuery()
                .eq(EntIssueBatch::getTenantNo, context.tenantNo()).eq(EntIssueBatch::getBatchNo, source.sourceNo()));
        if (candidates.size() != 1) throw BizException.of(ErrorCode.NOT_FOUND);
        var row = candidates.getFirst(); var snap = json.readValue(row.getAudienceSnapshot(), BatchSnapshot.class);
        int index;
        try { index = Integer.parseInt(source.sourceLineNo()) - 1; }
        catch (RuntimeException invalid) { throw BizException.of(ErrorCode.CONFLICT); }
        if (index < 0 || index >= snap.audience().recipients().size()) throw BizException.of(ErrorCode.CONFLICT);
        var c = command(row.getBatchNo(), row.getEntityNo(), index, snap.audience().recipients().get(index).userNo(), snap);
        resolve(context, c);
        return new SourceDecisionPort.SourceDecision(source, SourceDecisionPort.State.COMMITTED, decisionRef(source, snap),
                c.recipientUserNo(), c.entityNo(), null, snap.definition().currency(), 0L, c.benefitSnapshotHash());
    }
    public static GrantCommand command(String batch, String entity, int index, String user, BatchSnapshot snap) {
        var source = new SourceRef("PAY", "TARGETED", batch, Integer.toString(index + 1), null, null, 0);
        return new GrantCommand("BG" + EntitlementGrantWriter.digest(batch + ":" + index).substring(0, 60),
                source, null, snap.templateVersionNo(), user, entity, 1, snap.benefitHash(), null);
    }
    public static DecisionRef decisionRef(SourceRef source, BatchSnapshot snap) {
        return new DecisionRef("BD" + source.grantKey().substring(0, 60), snap.createdAt(),
                source.sourceRevision(), DecisionRef.DecisionType.GRANT_COMMIT);
    }
}
