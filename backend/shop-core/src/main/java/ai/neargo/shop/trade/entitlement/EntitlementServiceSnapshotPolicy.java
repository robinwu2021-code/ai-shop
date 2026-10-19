package ai.neargo.shop.trade.entitlement;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseItem;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort.UseSourceFact;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.trade.dto.ServiceOrderSnapshot;
import ai.neargo.shop.trade.dto.ServiceOrderSnapshot.PreviewBinding;
import ai.neargo.shop.trade.entitlement.entity.OrdEntServiceOrder;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

/** The exact original JSON is hashed; technical state/version updates cannot change source facts. */
public final class EntitlementServiceSnapshotPolicy {
    private EntitlementServiceSnapshotPolicy() { }
    public record FrozenSource(String tenantNo, SourceRef source, String userNo, String entityNo, String storeNo,
            String currency, List<UseItem> items, String storeName, String staffName,
            Map<String, List<String>> categories, Instant performedAt) { }
    public static ServiceOrderSnapshot snapshot(OrdEntServiceOrder row, ObjectMapper json) {
        if (!hash(row.getSourceSnapshot()).equals(row.getSourceHash())) throw BizException.of(ErrorCode.CONFLICT);
        var source = json.readValue(row.getSourceSnapshot(), FrozenSource.class);
        if (!source.tenantNo().equals(row.getTenantNo()) || !source.userNo().equals(row.getUserNo())
                || !source.entityNo().equals(row.getEntityNo()) || !source.storeNo().equals(row.getStoreNo())
                || !source.source().equals(reference(row.getServiceOrderNo()))) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        var fact = new UseSourceFact(source.tenantNo(), source.source(), source.userNo(), source.entityNo(),
                source.storeNo(), source.currency(), source.items(), row.getSourceHash(), source.storeName(),
                source.staffName(), null, 0, source.categories(), source.performedAt(),
                row.getCommandSnapshot() != null &&
                        Set.of("PENDING", "COMMIT_DECIDED", "COMMITTED").contains(row.getStatus())
                        ? row.getOperationNo() : null);
        return new ServiceOrderSnapshot(row.getServiceOrderNo(), row.getOperationNo(), row.getStatus(), fact,
                json.readValue(row.getContextSnapshot(), EntitlementContext.class),
                row.getPreviewSnapshot() == null ? null
                        : json.readValue(row.getPreviewSnapshot(), PreviewBinding.class),
                row.getCommandSnapshot() == null ? null
                        : json.readValue(row.getCommandSnapshot(), UseCommand.class),
                row.getDecisionSnapshot() == null ? null
                        : json.readValue(row.getDecisionSnapshot(), DecisionRef.class),
                row.getDeadline().toInstant(ZoneOffset.UTC), row.getCancellationReason());
    }
    public static String orderNo(EntitlementContext ctx, String entity, String key, ObjectMapper json) {
        String digest = hash(json.writeValueAsString(List.of(ctx.tenantNo(), entity, ctx.actorNo(), key)));
        return "ES" + digest.substring(0, 60);
    }
    public static String operationNo(String no) { return "SU" + hash(no).substring(0, 60); }
    public static SourceRef reference(String no) { return new SourceRef("CORE", "SERVICE_USE", no, null, null, null, 0);
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }
}
