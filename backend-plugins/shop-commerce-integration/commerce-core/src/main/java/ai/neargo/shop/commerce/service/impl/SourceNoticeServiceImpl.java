package ai.neargo.shop.commerce.service.impl;

import ai.neargo.shop.commerce.dto.SourceNoticeContracts.Notice;
import ai.neargo.shop.commerce.dto.SourceNoticeContracts.Receipt;
import ai.neargo.shop.commerce.entity.CimCatalogScope;
import ai.neargo.shop.commerce.entity.CimSourceNotice;
import ai.neargo.shop.commerce.mapper.CommerceMappers.SourceNoticeMapper;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.commerce.policy.SourceWithdrawalPolicy;
import ai.neargo.shop.commerce.service.CommerceRecoveryService;
import ai.neargo.shop.commerce.service.SourceNoticeService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelRecoveryPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Kind;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Source;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Status;
import ai.neargo.shop.spi.commerce.ChannelSourceWithdrawalProvider;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * ACK follows local commit; replay, future activation and bounded store scans share the original
 * recovery worker.
 */
public final class SourceNoticeServiceImpl implements SourceNoticeService, ChannelRecoveryPort {
    @Override
    public String workflow() {
        return "SOURCE_NOTICE";
    }

    private final CatalogPersistence db;
    private final SourceNoticeMapper notices;
    private final Supplier<CommerceRecoveryService> recovery;

    public SourceNoticeServiceImpl(
            CatalogPersistence db,
            SourceNoticeMapper notices,
            Supplier<CommerceRecoveryService> recovery) {
        this.db = db;
        this.notices = notices;
        this.recovery = recovery;
    }

    @Override
    public Receipt receive(Notice notice) {
        if (notice == null || notice.owner() == null || notice.effectiveAt() == null)
            throw BizException.of(ErrorCode.BAD_REQUEST);
        CommerceValidation.text(notice.sourceSystem(), 32);
        CommerceValidation.text(notice.eventNo(), 64);
        CommerceValidation.text(notice.entityNo(), 32);
        if (notice.storeNo() != null) CommerceValidation.text(notice.storeNo(), 32);
        if (notice.sourceRef() != null) CommerceValidation.text(notice.sourceRef(), 128);
        if (!"PLATFORM".equals(notice.sourceSystem()) && !"INVENTORY".equals(notice.sourceSystem()))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        if (notice.kind() != null && !Set.of("CHANGE", "WITHDRAWAL").contains(notice.kind()))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        if ("WITHDRAWAL".equals(notice.kind())
                && (!"PLATFORM".equals(notice.sourceSystem())
                        || notice.owner() != Owner.FOOD
                        || notice.sourceRef() == null))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        String key =
                "SN:"
                        + PricingSnapshotHash.of(
                                List.of(notice.sourceSystem(), notice.eventNo(), notice.owner()));
        return db.commands.execute(
                notice.entityNo(),
                notice.storeNo(),
                "SYSTEM",
                "SOURCE_NOTICE",
                key,
                notice,
                Receipt.class,
                () -> {
                    var row = CatalogPersistence.audit(new CimSourceNotice(), "SYSTEM");
                    row.setNoticeNo(BizKey.next("CINT"));
                    row.setEntityNo(notice.entityNo());
                    row.setStoreNo(notice.storeNo());
                    row.setSourceSystem(notice.sourceSystem());
                    row.setSourceEventNo(notice.eventNo());
                    row.setNoticeKind(notice.kind() == null ? "CHANGE" : notice.kind());
                    row.setSourceOwner(notice.owner().name());
                    row.setSourceRef(notice.sourceRef());
                    row.setPayloadHash(PricingSnapshotHash.of(notice));
                    var effective = notice.effectiveAt().truncatedTo(ChronoUnit.MILLIS);
                    if (effective.isBefore(notice.effectiveAt()))
                        effective = effective.plusMillis(1);
                    row.setEffectiveAt(LocalDateTime.ofInstant(effective, ZoneOffset.UTC));
                    row.setNextCheckAt(
                            row.getEffectiveAt().isAfter(CatalogPersistence.now())
                                    ? row.getEffectiveAt()
                                    : CatalogPersistence.now());
                    row.setScopeCursor(0L);
                    row.setStatus("PENDING");
                    row.setAttempts(0);
                    notices.insert(row);
                    return new Receipt(
                            row.getNoticeNo(), row.getNextCheckAt().toInstant(ZoneOffset.UTC));
                },
                Receipt::noticeNo);
    }

    @Override
    public int recoverDue(int limit) {
        if (limit < 1 || limit > 100) throw BizException.of(ErrorCode.BAD_REQUEST);
        var due =
                db.commands.query(
                        () ->
                                notices.selectList(
                                        Wrappers.<CimSourceNotice>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq("status", "PENDING")
                                                .le("next_check_at", CatalogPersistence.now())
                                                .le("effective_at", CatalogPersistence.now())
                                                .orderByAsc("next_check_at", "id")
                                                .last("LIMIT " + limit)));
        int processed = 0;
        for (var row : due) {
            try {
                var scopes =
                        db.commands.query(
                                () ->
                                        db.scopes.selectList(
                                                Wrappers.<CimCatalogScope>query()
                                                        .eq("tenant_no", "MAIN")
                                                        .eq("entity_no", row.getEntityNo())
                                                        .eq(
                                                                row.getStoreNo() != null,
                                                                "store_no",
                                                                row.getStoreNo())
                                                        .eq("status", "ACTIVE")
                                                        .gt("id", row.getScopeCursor())
                                                        .orderByAsc("id")
                                                        .last("LIMIT 101")));
                long cursor = row.getScopeCursor();
                int size = Math.min(scopes.size(), 100);
                for (int i = 0; i < size; i++) {
                    var scope = scopes.get(i);
                    var policy = db.commands.query(() -> db.policy(scope.getScopeNo()));
                    if (!policy.getEnabled() || !policy.getOnPublish()) {
                        cursor = scope.getId();
                        continue;
                    }
                    var sources = db.commands.query(() -> db.sourceRows(scope.getScopeNo()));
                    for (var source : sources) {
                        if (!source.sourceOwner().name().equals(row.getSourceOwner())
                                || row.getSourceRef() != null
                                        && !source.sourceRef().equals(row.getSourceRef())) continue;
                        var owner = source.sourceOwner();
                        var nativeQuery =
                                new ChannelSourceWithdrawalProvider.Query(
                                        scope.getEntityNo(),
                                        scope.getStoreNo(),
                                        owner,
                                        source.sourceRef(),
                                        source.selectionRevision());
                        ChannelSourceWithdrawalProvider.Disposition originalState = null;
                        boolean recorded =
                                !db.knownWithdrawals(
                                                scope.getEntityNo(),
                                                scope.getStoreNo(),
                                                List.of(source))
                                        .isEmpty();
                        if (db.withdrawalsEnabled()
                                && ("WITHDRAWAL".equals(row.getNoticeKind()) || recorded)) {
                            originalState = db.withdrawalOwner(owner).disposition(nativeQuery);
                            SourceWithdrawalPolicy.disposition(
                                    nativeQuery, originalState, Clock.systemUTC());
                            if (originalState.state()
                                    == ChannelSourceWithdrawalProvider.State.WITHDRAWN) {
                                db.recordWithdrawal(originalState.withdrawal(), row.getNoticeNo());
                                String event =
                                        "SW:"
                                                + PricingSnapshotHash.of(
                                                        List.of(
                                                                row.getNoticeNo(),
                                                                scope.getScopeNo(),
                                                                originalState.withdrawal().data()));
                                recovery.get()
                                        .onWithdrawnScope(
                                                scope.getEntityNo(),
                                                scope.getStoreNo(),
                                                scope.getScopeNo(),
                                                owner,
                                                source.sourceRef(),
                                                source.selectionRevision(),
                                                event);
                                continue;
                            }
                        }
                        // Ordinary positive errors still block. No catch converts them into an
                        // empty catalog.
                        var current =
                                db.authority
                                        .get()
                                        .current(
                                                new Source(
                                                        scope.getEntityNo(),
                                                        scope.getStoreNo(),
                                                        owner,
                                                        owner == Owner.FOOD
                                                                ? Kind.MENU
                                                                : Kind.SELECTION,
                                                        source.sourceRef(),
                                                        source.selectionRevision()));
                        if (current == null || current.status() != Status.VALID)
                            throw new IllegalStateException(
                                    "Original current revision unavailable");
                        if (originalState != null) db.activeDisposition(originalState, current);
                        String event =
                                "SN:"
                                        + PricingSnapshotHash.of(
                                                List.of(
                                                        row.getNoticeNo(),
                                                        scope.getScopeNo(),
                                                        source.sourceRef(),
                                                        current.canonicalRevision()));
                        recovery.get()
                                .onPublishedScope(
                                        scope.getEntityNo(),
                                        scope.getStoreNo(),
                                        scope.getScopeNo(),
                                        owner,
                                        source.sourceRef(),
                                        current.canonicalRevision(),
                                        event);
                    }
                    cursor = scope.getId();
                }
                long next = cursor;
                db.commands.atomic(
                        () ->
                                notices.update(
                                        Wrappers.<CimSourceNotice>update()
                                                .eq("tenant_no", "MAIN")
                                                .eq("id", row.getId())
                                                .eq("version", row.getVersion())
                                                .eq("status", "PENDING")
                                                .set("scope_cursor", next)
                                                .set("error_code", null)
                                                .set(
                                                        "status",
                                                        scopes.size() > 100
                                                                ? "PENDING"
                                                                : "COMPLETE")
                                                .set("next_check_at", CatalogPersistence.now())
                                                .set("version", row.getVersion() + 1)));
                processed++;
            } catch (RuntimeException failure) {
                db.commands.atomic(
                        () ->
                                notices.update(
                                        Wrappers.<CimSourceNotice>update()
                                                .eq("tenant_no", "MAIN")
                                                .eq("id", row.getId())
                                                .eq("version", row.getVersion())
                                                .eq("status", "PENDING")
                                                .set(
                                                        "next_check_at",
                                                        CatalogPersistence.now().plusMinutes(1))
                                                .set(
                                                        "attempts",
                                                        Math.min(
                                                                        Integer.MAX_VALUE - 1,
                                                                        row.getAttempts())
                                                                + 1)
                                                .set("error_code", "ORIGINAL_SOURCE_NOT_READY")
                                                .set("version", row.getVersion() + 1)));
            }
        }
        return processed;
    }
}
