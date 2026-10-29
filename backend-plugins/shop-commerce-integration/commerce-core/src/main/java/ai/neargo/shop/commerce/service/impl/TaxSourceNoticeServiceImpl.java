package ai.neargo.shop.commerce.service.impl;

import ai.neargo.shop.commerce.dto.SourceNoticeContracts;
import ai.neargo.shop.commerce.dto.TaxSourceNoticeContracts;
import ai.neargo.shop.commerce.entity.CimCatalogScope;
import ai.neargo.shop.commerce.entity.CimTaxSourceNotice;
import ai.neargo.shop.commerce.mapper.TaxSourceNoticeMapper;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.commerce.service.SourceNoticeService;
import ai.neargo.shop.commerce.service.TaxSourceNoticeService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelRecoveryPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Source scope fanout is committed before ACK and resumes by original scope cursor after restart.
 */
public final class TaxSourceNoticeServiceImpl
        implements TaxSourceNoticeService, ChannelRecoveryPort {
    private final CatalogPersistence db;
    private final TaxSourceNoticeMapper rows;
    private final SourceNoticeService targets;

    public TaxSourceNoticeServiceImpl(
            CatalogPersistence db, TaxSourceNoticeMapper rows, SourceNoticeService targets) {
        this.db = db;
        this.rows = rows;
        this.targets = targets;
    }

    @Override
    public String workflow() {
        return "TAX_SOURCE_NOTICE";
    }

    @Override
    public SourceNoticeContracts.Receipt receive(TaxSourceNoticeContracts.Notice notice) {
        if (notice == null
                || !"PLATFORM".equals(notice.sourceSystem())
                || !"MAIN".equals(notice.tenantNo())
                || notice.version() == null
                || notice.version() < 0
                || notice.effectiveAt() == null
                || notice.contentHash() == null
                || !notice.contentHash().matches("[a-f0-9]{64}"))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        CommerceValidation.text(notice.eventNo(), 64);
        CommerceValidation.text(notice.settingKey(), 128);
        if (notice.entityNo() == null) {
            if (!"pricing.tax.default".equals(notice.settingKey()))
                throw BizException.of(ErrorCode.BAD_REQUEST);
        } else {
            CommerceValidation.text(notice.entityNo(), 32);
            if (!("pricing.tax.merchant." + notice.entityNo()).equals(notice.settingKey()))
                throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return db.commands.executePlatform(
                "SYSTEM",
                "TAX_SOURCE_NOTICE",
                notice.eventNo(),
                notice,
                SourceNoticeContracts.Receipt.class,
                () -> {
                    var row = CatalogPersistence.audit(new CimTaxSourceNotice(), "SYSTEM");
                    row.setNoticeNo(BizKey.next("CITX"));
                    row.setSourceEventNo(notice.eventNo());
                    row.setEntityNo(notice.entityNo());
                    row.setSettingKey(notice.settingKey());
                    row.setSourceVersion(notice.version());
                    row.setContentHash(notice.contentHash());
                    row.setPayloadHash(PricingSnapshotHash.of(notice));
                    var effective = notice.effectiveAt().truncatedTo(ChronoUnit.MILLIS);
                    if (effective.isBefore(notice.effectiveAt()))
                        effective = effective.plusMillis(1);
                    row.setEffectiveAt(LocalDateTime.ofInstant(effective, ZoneOffset.UTC));
                    row.setScopeCursor(0L);
                    row.setStatus("PENDING");
                    row.setNextCheckAt(row.getEffectiveAt());
                    row.setAttempts(0);
                    rows.insert(row);
                    return new SourceNoticeContracts.Receipt(
                            row.getNoticeNo(), row.getNextCheckAt().toInstant(ZoneOffset.UTC));
                },
                SourceNoticeContracts.Receipt::noticeNo);
    }

    @Override
    public int recoverDue(int limit) {
        if (limit < 1 || limit > 100) throw BizException.of(ErrorCode.BAD_REQUEST);
        var due =
                db.commands.query(
                        () ->
                                rows.selectList(
                                        Wrappers.<CimTaxSourceNotice>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq("status", "PENDING")
                                                .le("next_check_at", CatalogPersistence.now())
                                                .le("effective_at", CatalogPersistence.now())
                                                .orderByAsc("id")
                                                .last("LIMIT " + limit)));
        int processed = 0;
        for (var notice : due)
            try {
                var scopes =
                        db.commands.query(
                                () ->
                                        db.scopes.selectList(
                                                Wrappers.<CimCatalogScope>query()
                                                        .eq("tenant_no", "MAIN")
                                                        .eq("status", "ACTIVE")
                                                        .eq(
                                                                notice.getEntityNo() != null,
                                                                "entity_no",
                                                                notice.getEntityNo())
                                                        .gt("id", notice.getScopeCursor())
                                                        .orderByAsc("id")
                                                        .last("LIMIT 101")));
                long cursor = notice.getScopeCursor();
                for (int i = 0; i < Math.min(100, scopes.size()); i++) {
                    var scope = scopes.get(i);
                    for (var owner : Owner.values())
                        targets.receive(
                                new SourceNoticeContracts.Notice(
                                        "PLATFORM",
                                        notice.getSourceEventNo(),
                                        scope.getEntityNo(),
                                        scope.getStoreNo(),
                                        owner,
                                        null,
                                        notice.getEffectiveAt().toInstant(ZoneOffset.UTC)));
                    cursor = scope.getId();
                }
                long next = cursor;
                db.commands.atomic(
                        () ->
                                rows.update(
                                        Wrappers.<CimTaxSourceNotice>update()
                                                .eq("tenant_no", "MAIN")
                                                .eq("id", notice.getId())
                                                .eq("version", notice.getVersion())
                                                .set("scope_cursor", next)
                                                .set(
                                                        "status",
                                                        scopes.size() > 100
                                                                ? "PENDING"
                                                                : "COMPLETE")
                                                .set("next_check_at", CatalogPersistence.now())
                                                .setSql("version=version+1")));
                processed++;
            } catch (RuntimeException unavailable) {
                db.commands.atomic(
                        () ->
                                rows.update(
                                        Wrappers.<CimTaxSourceNotice>update()
                                                .eq("tenant_no", "MAIN")
                                                .eq("id", notice.getId())
                                                .eq("version", notice.getVersion())
                                                .set(
                                                        "next_check_at",
                                                        CatalogPersistence.now().plusMinutes(1))
                                                .set(
                                                        "attempts",
                                                        Math.min(
                                                                        Integer.MAX_VALUE - 1,
                                                                        notice.getAttempts())
                                                                + 1)
                                                .setSql("version=version+1")));
            }
        return processed;
    }
}
