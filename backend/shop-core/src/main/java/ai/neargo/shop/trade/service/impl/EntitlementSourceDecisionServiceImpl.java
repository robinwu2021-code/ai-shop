package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.trade.entitlement.entity.OrdEntSourceDecision;
import ai.neargo.shop.trade.mapper.TradeMappers.EntitlementSourceDecisionMapper;
import ai.neargo.shop.trade.service.EntitlementSourceDecisionService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class EntitlementSourceDecisionServiceImpl implements EntitlementSourceDecisionService {
    private final EntitlementSourceDecisionMapper decisions;
    private final ObjectMapper json;

    public EntitlementSourceDecisionServiceImpl(EntitlementSourceDecisionMapper decisions, ObjectMapper json) {
        this.decisions = decisions;
        this.json = json;
    }

    @Override
    @Transactional
    public DecisionRef record(String tenantNo, SourceRef source, String purpose, DecisionRef.DecisionType type,
            String authorizationRef, String paidFactRef, String userNo, String entityNo, String storeNo,
            String currency, long amountMinor, String snapshotHash, Instant occurredAt) {
        if (tenantNo == null || source == null || purpose == null || type == null || userNo == null
                || entityNo == null || currency == null || amountMinor < 0 || !hash(snapshotHash)
                || occurredAt == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        boolean cancelled = type == DecisionRef.DecisionType.USE_CANCEL || type == DecisionRef.DecisionType.GRANT_ABORT;
        if (!(purpose.equals("USE") && (type == DecisionRef.DecisionType.USE_COMMIT || cancelled)
                || purpose.equals("GRANT") && (type == DecisionRef.DecisionType.GRANT_COMMIT || cancelled)
                || purpose.equals("REVERSE") && type == DecisionRef.DecisionType.ORDER_REVERSE)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String sourceKey = switch (purpose) {
            case "USE" -> source.useIntentKey();
            case "GRANT" -> source.grantKey();
            case "REVERSE" -> source.settledKey();
            default -> throw BizException.of(ErrorCode.BAD_REQUEST);
        };
        String settledKey = cancelled ? null : source.settledKey();
        var existing = DataScopeContext.executeWithoutScope(() -> decisions.selectOne(
                Wrappers.<OrdEntSourceDecision>lambdaQuery().eq(OrdEntSourceDecision::getTenantNo, tenantNo)
                        .eq(OrdEntSourceDecision::getPurpose, purpose)
                        .eq(OrdEntSourceDecision::getSourceKey, sourceKey).last("limit 1")));
        if (existing != null) {
            if (!existing.getDecisionType().equals(type.name()) || !java.util.Objects.equals(existing.getSettledSourceKey(),
                    settledKey) || !existing.getSnapshotHash().equals(snapshotHash)
                    || existing.getSourceRevision() != source.sourceRevision()
                    || !existing.getUserNo().equals(userNo) || !existing.getEntityNo().equals(entityNo)
                    || !java.util.Objects.equals(existing.getStoreNo(), storeNo)
                    || !existing.getCurrency().equals(currency) || existing.getAmountMinor() != amountMinor
                    || !java.util.Objects.equals(existing.getAuthorizationRef(), authorizationRef)
                    || !java.util.Objects.equals(existing.getPaidFactRef(), paidFactRef)) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            return decisionRef(existing);
        }
        String sourceSnapshot;
        try { sourceSnapshot = json.writeValueAsString(source); }
        catch (Exception e) { throw new IllegalStateException("Could not serialize source reference", e); }
        String decisionNo = "ED" + UUID.randomUUID().toString().replace("-", "");
        var row = new OrdEntSourceDecision();
        row.setTenantNo(tenantNo);
        row.setDecisionNo(decisionNo);
        row.setPurpose(purpose);
        row.setSourceKey(sourceKey);
        row.setSourceSnapshot(sourceSnapshot);
        row.setSettledSourceKey(settledKey);
        row.setSourceRevision(source.sourceRevision());
        row.setDecisionType(type.name());
        row.setStatus(cancelled ? "CANCELLED" : "COMMITTED");
        row.setAuthorizationRef(authorizationRef);
        row.setPaidFactRef(paidFactRef);
        row.setUserNo(userNo);
        row.setEntityNo(entityNo);
        row.setStoreNo(storeNo);
        row.setCurrency(currency);
        row.setAmountMinor(amountMinor);
        row.setSnapshotHash(snapshotHash);
        row.setOccurredAt(LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
        row.setVersion(0L);
        row.setDeleted(0);
        row.setCreatedAt(row.getOccurredAt());
        row.setUpdatedAt(row.getOccurredAt());
        row.setCreatedBy("SYSTEM");
        row.setUpdatedBy("SYSTEM");
        DataScopeContext.executeWithoutScope(() -> decisions.insert(row));
        return decisionRef(row);
    }

    private static DecisionRef decisionRef(OrdEntSourceDecision row) {
        return new DecisionRef(row.getDecisionNo(), row.getOccurredAt().toInstant(ZoneOffset.UTC),
                row.getSourceRevision(), DecisionRef.DecisionType.valueOf(row.getDecisionType()));
    }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
}
