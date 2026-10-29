package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.audit;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.now;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.require;

import ai.neargo.shop.commerce.dto.CatalogContracts.CatalogPreviewVO;
import ai.neargo.shop.commerce.dto.CatalogContracts.PreviewCatalogReq;
import ai.neargo.shop.commerce.entity.CimCatalogSnapshot;
import ai.neargo.shop.commerce.policy.CatalogDocumentPolicy;
import ai.neargo.shop.commerce.policy.CatalogSourceTransportPolicy;
import ai.neargo.shop.commerce.service.CatalogPublicationService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.CaptureCommand;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 事务外捕获原目录，事务内复核版本并冻结，避免跨网络持有数据库锁。 */
public final class CatalogPublicationServiceImpl implements CatalogPublicationService {
    private final CatalogPersistence db;

    public CatalogPublicationServiceImpl(CatalogPersistence db) {
        this.db = db;
    }

    public CatalogPreviewVO preview(
            String e, String store, String no, String actor, PreviewCatalogReq req, String key) {
        require(req != null && req.expectedScopeVersion() != null);
        var replay =
                db.commands.replay(
                        e, store, actor, "CATALOG_PREVIEW:" + no, key, req, CatalogPreviewVO.class);
        if (replay.isPresent()) return replay.get();
        var before = db.commands.query(() -> db.scope(e, store, no, false));
        require(req.expectedScopeVersion().equals(before.scope().getVersion()));
        var refs = db.commands.query(() -> db.sourceRows(no));
        var selection = db.sourceSelection(before, refs);
        var proofs = selection.active();
        Owner owner = Owner.valueOf(before.revision().getSourceOwner());
        var port = db.owners.get(owner);
        require(port != null);
        Document document;
        try {
            var command =
                    new CaptureCommand(
                            e,
                            store,
                            owner,
                            before.connection().getConnectionNo(),
                            before.connection().getActiveRevisionNo(),
                            proofs,
                            selection.withdrawn().isEmpty() ? null : selection.withdrawn());
            var captured = port.capture(command);
            // Every removal must preserve the exact verified proof set and explicit replacement
            // metadata.
            document =
                    !selection.withdrawn().isEmpty()
                                    || captured != null && captured.replacement() != null
                            ? CatalogSourceTransportPolicy.validateDocument(
                                    command, captured, db.json, Clock.systemUTC())
                            : CatalogDocumentPolicy.freeze(captured, proofs, db.json, e, store);
        } catch (RuntimeException failure) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        require(document.owner() == owner);
        String hash = CatalogDocumentPolicy.hash(document, db.json);
        Instant expires = Instant.now().plusSeconds(300);
        if (document.validUntil().isBefore(expires)) expires = document.validUntil();
        for (var proof : proofs)
            if (proof.proof().validUntil().isBefore(expires)) expires = proof.proof().validUntil();
        for (var proof : selection.withdrawn())
            if (proof.validUntil().isBefore(expires)) expires = proof.validUntil();
        final Instant expiry = expires;
        return db.commands.execute(
                e,
                store,
                actor,
                "CATALOG_PREVIEW:" + no,
                key,
                req,
                CatalogPreviewVO.class,
                () -> {
                    var c = db.scope(e, store, no, true);
                    db.unchanged(before, c);
                    db.selectionFresh(selection);
                    require(expiry.isAfter(Instant.now()));
                    var s = audit(new CimCatalogSnapshot(), "SYSTEM");
                    s.setSnapshotNo(BizKey.next("CISN"));
                    s.setEntityNo(e);
                    s.setScopeNo(no);
                    s.setSchemaVersion(CatalogDocumentPolicy.version(document));
                    s.setSourceManifest(
                            db.json.writeValueAsString(
                                    selection.withdrawn().isEmpty() ? proofs : selection));
                    s.setPayloadRef("DB:" + s.getSnapshotNo());
                    s.setPayloadJson(db.json.writeValueAsString(document));
                    s.setContentHash(hash);
                    s.setItemCount(CatalogDocumentPolicy.itemCount(document));
                    s.setCurrency(document.currency());
                    s.setCapturedAt(now());
                    s.setExpiresAt(LocalDateTime.ofInstant(expiry, ZoneOffset.UTC));
                    s.setScopeVersion(c.scope().getVersion());
                    s.setConnectionRevisionNo(c.connection().getActiveRevisionNo());
                    s.setCreatedBy(actor);
                    s.setUpdatedBy(actor);
                    db.snapshots.insert(s);
                    return new CatalogPreviewVO(
                            s.getSnapshotNo(),
                            no,
                            hash,
                            document.currency(),
                            s.getItemCount(),
                            expiry,
                            s.getScopeVersion(),
                            document);
                },
                CatalogPreviewVO::snapshotNo);
    }
}
