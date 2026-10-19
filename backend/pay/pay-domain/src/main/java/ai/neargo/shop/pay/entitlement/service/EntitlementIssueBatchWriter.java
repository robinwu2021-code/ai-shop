package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.BatchSnapshot;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.BatchView;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.CreateBatch;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.Item;
import ai.neargo.shop.pay.entitlement.entity.EntIssueBatch;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.entitlement.port.BatchGrantSourcePortImpl;
import ai.neargo.shop.pay.mapper.entitlement.EntIssueBatchMapper;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantDecision;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.State;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Each item owns one pay transaction containing its grant and cursor; failure rolls both back before recording a skip. */
@Component
public class EntitlementIssueBatchWriter {
    private final EntIssueBatchMapper batches;
    private final EntitlementGrantService grants;
    private final EntitlementCommandDeduplicator commands;
    private final PayEntitlementFeatureGate features;
    private final ObjectMapper json;
    public EntitlementIssueBatchWriter(EntIssueBatchMapper batches, EntitlementGrantService grants,
            EntitlementCommandDeduplicator commands, PayEntitlementFeatureGate features, ObjectMapper json) {
        this.batches = batches; this.grants = grants; this.commands = commands; this.features = features; this.json = json;
    }
    @Transactional("payTxManager")
    public BatchView create(CatalogScope scope, CreateBatch c, String key, String no, BatchSnapshot snapshot) {
        return commands.execute(scope, "ISSUE_BATCH", key, c, no, BatchView.class, () -> {
            features.requireIssuance();
            var row = new EntIssueBatch(); row.setTenantNo(scope.tenantNo()); row.setEntityNo(scope.entityNo());
            row.setBatchNo(no); row.setTemplateVersionNo(c.templateVersionNo()); row.setAudienceFactRef(c.audienceFactRef());
            row.setAudienceSnapshot(json.writeValueAsString(snapshot)); row.setPlannedCount(snapshot.audience().recipients().size());
            row.setIssuedCount(0); row.setSkippedCount(0); row.setFailedCount(0); row.setPendingCount(row.getPlannedCount());
            row.setCursorOffset(0); row.setSkipReasons("{}"); row.setRecipientResults("[]");
            row.setStatus(row.getPendingCount() == 0 ? "COMPLETED" : "PROCESSING"); row.setOperatorNo(scope.actorNo());
            row.setVersion(0L); row.setDeleted(0); row.setCreatedBy(scope.actorNo()); row.setUpdatedBy(scope.actorNo());
            row.setCreatedAt(LocalDateTime.ofInstant(snapshot.createdAt(), ZoneOffset.UTC)); row.setUpdatedAt(row.getCreatedAt());
            changed(batches.insert(row)); return view(row);
        });
    }
    @Transactional("payTxManager")
    public boolean step(CatalogScope scope, String no, int expectedCursor) {
        var row = owned(scope, no, true);
        if (row.getPendingCount() == 0 || row.getCursorOffset() != expectedCursor) return false;
        var snap = json.readValue(row.getAudienceSnapshot(), BatchSnapshot.class);
        var recipient = snap.audience().recipients().get(expectedCursor);
        String grant = null;
        if (recipient.eligible()) {
            var c = BatchGrantSourcePortImpl.command(no, row.getEntityNo(), expectedCursor, recipient.userNo(), snap);
            grant = c.grantNo();
            var context = new EntitlementContext(scope.tenantNo(), row.getOperatorNo(), grant, snap.benefitHash());
            // These facts are PAY/TARGETED and fully local; no cross-owner lookup is made while this row is locked.
            grants.prepare(context, c);
            var receipt = grants.commit(context, new GrantDecision(grant, c.sourceRef(),
                    BatchGrantSourcePortImpl.decisionRef(c.sourceRef(), snap)));
            if (receipt.status() != State.COMMITTED) throw BizException.of(ErrorCode.CONFLICT);
        }
        append(row, new Item(recipient.userNo(), grant, recipient.eligible() ? "ISSUED" : "SKIPPED",
                recipient.eligible() ? null : recipient.reasonCode()));
        return true;
    }
    @Transactional("payTxManager")
    public void skipConflict(CatalogScope scope, String no, int expectedCursor) {
        var row = owned(scope, no, true);
        if (row.getPendingCount() == 0 || row.getCursorOffset() != expectedCursor) return;
        var snap = json.readValue(row.getAudienceSnapshot(), BatchSnapshot.class);
        var recipient = snap.audience().recipients().get(expectedCursor);
        append(row, new Item(recipient.userNo(), null, "SKIPPED", "CAPACITY_OR_SOURCE_CONFLICT"));
    }
    private void append(EntIssueBatch row, Item item) {
        var results = new ArrayList<>(Arrays.asList(json.readValue(row.getRecipientResults(), Item[].class)));
        results.add(item); row.setRecipientResults(json.writeValueAsString(results));
        if ("ISSUED".equals(item.status())) row.setIssuedCount(row.getIssuedCount() + 1);
        else row.setSkippedCount(row.getSkippedCount() + 1);
        Map<String, Long> reasons = results.stream().filter(i -> i.reasonCode() != null)
                .collect(Collectors.groupingBy(Item::reasonCode, Collectors.counting()));
        row.setSkipReasons(json.writeValueAsString(reasons)); row.setCursorOffset(row.getCursorOffset() + 1);
        row.setPendingCount(row.getPendingCount() - 1);
        row.setStatus(row.getPendingCount() > 0 ? "PROCESSING" : row.getSkippedCount() > 0 ? "PARTIAL" : "COMPLETED");
        row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC)); changed(batches.updateById(row));
    }
    public EntIssueBatch owned(CatalogScope scope, String no, boolean lock) {
        var row = lock ? batches.lock(scope.tenantNo(), scope.entityNo(), no) : batches.find(scope.tenantNo(), scope.entityNo(), no);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }
    public BatchView view(EntIssueBatch row) {
        return new BatchView(row.getBatchNo(), row.getStatus(), row.getPlannedCount(), row.getIssuedCount(),
                row.getSkippedCount(), row.getFailedCount(), row.getPendingCount(),
                Arrays.asList(json.readValue(row.getRecipientResults(), Item[].class)));
    }
    private static void changed(int n) { if (n != 1) throw BizException.of(ErrorCode.CONFLICT); }
}
