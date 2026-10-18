package ai.neargo.shop.trade.entitlement.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.trade.entitlement.entity.OrdEntAuthorization;
import ai.neargo.shop.trade.entitlement.entity.OrdEntSourceDecision;
import ai.neargo.shop.trade.mapper.TradeMappers.EntitlementAuthorizationMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.EntitlementSourceDecisionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Core-owned durable source truth. Missing rows are UNKNOWN, never guessed cancellation. */
@Component
public class EntitlementSourceDecisionPortImpl implements SourceDecisionPort {
    private final EntitlementAuthorizationMapper authorizations;
    private final EntitlementSourceDecisionMapper decisions;
    private final ObjectMapper json;

    public EntitlementSourceDecisionPortImpl(EntitlementAuthorizationMapper authorizations,
            EntitlementSourceDecisionMapper decisions, ObjectMapper json) {
        this.authorizations = authorizations;
        this.decisions = decisions;
        this.json = json;
    }

    @Override
    public SourceDecision query(EntitlementContext context, SourceRef source) {
        var row = DataScopeContext.executeWithoutScope(() -> decisions.selectOne(
                Wrappers.<OrdEntSourceDecision>lambdaQuery().eq(OrdEntSourceDecision::getTenantNo,
                        context.tenantNo()).eq(OrdEntSourceDecision::getPurpose, purpose(source))
                        .eq(OrdEntSourceDecision::getSourceKey, sourceKey(source)).last("limit 1")));
        if (row == null && "USE".equals(purpose(source))) {
            row = DataScopeContext.executeWithoutScope(() -> decisions.selectOne(
                    Wrappers.<OrdEntSourceDecision>lambdaQuery().eq(OrdEntSourceDecision::getTenantNo,
                            context.tenantNo()).eq(OrdEntSourceDecision::getPurpose, "USE")
                            .eq(OrdEntSourceDecision::getSettledSourceKey, source.settledKey())
                            .eq(OrdEntSourceDecision::getStatus, "COMMITTED").last("limit 1")));
        }
        if (row == null) return new SourceDecision(source, State.UNKNOWN, null, null, null, null, null, null, null);
        SourceRef persisted = readSource(row.getSourceSnapshot());
        var type = DecisionRef.DecisionType.valueOf(row.getDecisionType());
        var ref = new DecisionRef(row.getDecisionNo(), row.getOccurredAt().toInstant(ZoneOffset.UTC),
                row.getSourceRevision(), type);
        return new SourceDecision(persisted, "COMMITTED".equals(row.getStatus()) ? State.COMMITTED : State.CANCELLED,
                ref, row.getUserNo(), row.getEntityNo(), row.getStoreNo(), row.getCurrency(), row.getAmountMinor(),
                row.getSnapshotHash());
    }

    @Override
    public AuthorizationDecision authorization(EntitlementContext context, String authorizationRef) {
        var row = DataScopeContext.executeWithoutScope(() -> authorizations.selectOne(
                Wrappers.<OrdEntAuthorization>lambdaQuery().eq(OrdEntAuthorization::getTenantNo,
                        context.tenantNo()).eq(OrdEntAuthorization::getAuthorizationNo, authorizationRef)
                        .last("limit 1")));
        if (row == null) return unknownAuthorization(authorizationRef);
        var now = LocalDateTime.now(ZoneOffset.UTC);
        if ("PENDING".equals(row.getStatus())) {
            if (!now.isBefore(row.getExpiresAt())) {
                return authDecision(row, AuthorizationState.EXPIRED);
            }
            return unknownAuthorization(authorizationRef);
        }
        if ("EXPIRED".equals(row.getStatus()) || !now.isBefore(row.getExpiresAt())) {
            return authDecision(row, AuthorizationState.EXPIRED);
        }
        return authDecision(row, AuthorizationState.valueOf(row.getStatus()));
    }

    private AuthorizationDecision authDecision(OrdEntAuthorization row, AuthorizationState state) {
        SourceRef source = readSource(row.getSourceSnapshot());
        return new AuthorizationDecision(row.getAuthorizationNo(), state, source, row.getUserNo(), row.getEntityNo(),
                row.getStoreNo(), row.getCurrency(), row.getAmountMinor(), row.getSelectionDigest(),
                row.getOrderSnapshotHash(),
                row.getExpiresAt().toInstant(ZoneOffset.UTC));
    }

    private AuthorizationDecision unknownAuthorization(String ref) {
        return new AuthorizationDecision(ref, AuthorizationState.UNKNOWN, null, null, null, null, null, null, null,
                null, null);
    }

    private SourceRef readSource(String snapshot) {
        try {
            return json.readValue(snapshot, SourceRef.class);
        } catch (Exception e) {
            throw new IllegalStateException("Stored entitlement source snapshot is invalid", e);
        }
    }

    private static String purpose(SourceRef source) {
        if (source.sourceType().contains("REVERSE")) return "REVERSE";
        return source.sourceType().contains("USE") ? "USE" : "GRANT";
    }

    private static String sourceKey(SourceRef source) {
        return switch (purpose(source)) {
            case "USE" -> source.useIntentKey();
            case "REVERSE" -> source.settledKey();
            default -> source.grantKey();
        };
    }
}
