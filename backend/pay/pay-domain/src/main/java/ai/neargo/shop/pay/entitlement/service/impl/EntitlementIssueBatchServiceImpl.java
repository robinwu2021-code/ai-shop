package ai.neargo.shop.pay.entitlement.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.BatchSnapshot;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.BatchView;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.CreateBatch;
import ai.neargo.shop.pay.entitlement.service.EntitlementCatalogService;
import ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator;
import ai.neargo.shop.pay.entitlement.service.EntitlementGrantWriter;
import ai.neargo.shop.pay.entitlement.service.EntitlementIssueBatchService;
import ai.neargo.shop.pay.entitlement.service.EntitlementIssueBatchWriter;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Audience lookup is outside pay transactions; bounded resumes are safe after a worker restart. */
@Service
public class EntitlementIssueBatchServiceImpl implements EntitlementIssueBatchService {
    private final EntitlementCatalogService catalog;
    private final EntitlementCommandDeduplicator commands;
    private final EntitlementIssueBatchWriter writer;
    private final ObjectProvider<EntitlementIssueAudiencePort> audiences;
    private final ObjectMapper json;
    public EntitlementIssueBatchServiceImpl(EntitlementCatalogService catalog, EntitlementCommandDeduplicator commands,
            EntitlementIssueBatchWriter writer, ObjectProvider<EntitlementIssueAudiencePort> audiences, ObjectMapper json) {
        this.catalog = catalog; this.commands = commands; this.writer = writer; this.audiences = audiences; this.json = json;
    }
    @Override public BatchView create(CatalogScope scope, CreateBatch c, String key) {
        if (c == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        ContractChecks.id(c.templateVersionNo()); ContractChecks.id(c.audienceFactRef());
        var replay = commands.replay(scope, "ISSUE_BATCH", key, c, BatchView.class);
        if (replay.isPresent()) return replay.get();
        if (!"MAIN".equals(scope.tenantNo())) unavailable();
        var version = catalog.getVersion(scope, c.templateVersionNo());
        if (!version.definition().allowedIssueModes().contains("TARGETED")
                || "PAID_CARD".equals(version.definition().kind())) throw BizException.of(ErrorCode.CONFLICT);
        var owner = audiences.getIfAvailable(); if (owner == null) unavailable();
        var fact = owner.resolve(new EntitlementContext(scope.tenantNo(), scope.actorNo(), key,
                EntitlementGrantWriter.digest(json.writeValueAsString(c))), scope.entityNo(), c.audienceFactRef());
        if (fact == null || !scope.tenantNo().equals(fact.tenantNo()) || !scope.entityNo().equals(fact.entityNo())
                || !c.audienceFactRef().equals(fact.factRef()) || fact.recipients().size() > 1000
                || fact.recipients().stream().map(r -> r.userNo()).distinct().count() != fact.recipients().size()) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        fact.recipients().forEach(r -> {
            ContractChecks.id(r.userNo());
            if (!r.eligible()) ContractChecks.id(r.reasonCode());
        });
        var snapshot = new BatchSnapshot(fact, c.templateVersionNo(), version.definition(),
                EntitlementGrantWriter.digest(json.writeValueAsString(c) + json.writeValueAsString(fact)),
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
        return writer.create(scope, c, key, "EB" + UUID.randomUUID().toString().replace("-", ""), snapshot);
    }
    @Override public BatchView resume(CatalogScope scope, String no, int limit) {
        if (limit < 1 || limit > 100) throw BizException.of(ErrorCode.BAD_REQUEST);
        for (int i = 0; i < limit; i++) {
            var before = writer.owned(scope, no, false);
            if (before.getPendingCount() == 0) break;
            try { writer.step(scope, no, before.getCursorOffset()); }
            catch (BizException rejected) {
                if (rejected.errorCode() == ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE) break;
                if (rejected.errorCode() != ErrorCode.CONFLICT) throw rejected;
                writer.skipConflict(scope, no, before.getCursorOffset());
            }
        }
        return get(scope, no);
    }
    @Override public BatchView get(CatalogScope scope, String no) { return writer.view(writer.owned(scope, no, false)); }
    private static void unavailable() { throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE); }
}
