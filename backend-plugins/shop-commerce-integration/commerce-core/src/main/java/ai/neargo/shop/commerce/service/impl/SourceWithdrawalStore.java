package ai.neargo.shop.commerce.service.impl;

import ai.neargo.shop.commerce.dto.CatalogContracts.SourceReq;
import ai.neargo.shop.commerce.entity.CimSourceWithdrawal;
import ai.neargo.shop.commerce.mapper.SourceWithdrawalMapper;
import ai.neargo.shop.commerce.policy.SourceWithdrawalPolicy;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Status;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Validation;
import ai.neargo.shop.spi.commerce.ChannelSourceWithdrawalProvider;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * The source proof/action is append-only; current projection changes never fabricate a native
 * withdrawal.
 */
public final class SourceWithdrawalStore {
    private final CatalogPersistence db;
    private final SourceWithdrawalMapper rows;

    public SourceWithdrawalStore(CatalogPersistence db, SourceWithdrawalMapper rows) {
        this.db = db;
        this.rows = rows;
    }

    public List<ChannelSourceWithdrawalProvider.Data> current(
            String entity, String store, List<SourceReq> configured) {
        return db.commands.query(
                () -> {
                    var selected =
                            rows.selectList(
                                    Wrappers.<CimSourceWithdrawal>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("entity_no", entity)
                                            .eq("store_no", store)
                                            .eq("status", "CURRENT")
                                            .last("LIMIT 10001"));
                    if (selected.size() > 10000)
                        throw new IllegalStateException("Too many original withdrawal proofs");
                    return selected.stream()
                            .map(
                                    row -> {
                                        var proof =
                                                db.json.readValue(
                                                        row.getProofSnapshot(),
                                                        ChannelSourceWithdrawalProvider.Facts
                                                                .class);
                                        if (proof.data() == null
                                                || !row.getContentHash()
                                                        .equals(
                                                                PricingSnapshotHash.of(
                                                                        proof.data()))
                                                || !entity.equals(proof.data().query().entityNo())
                                                || !store.equals(proof.data().query().storeNo()))
                                            throw new IllegalStateException(
                                                    "Corrupt original source withdrawal");
                                        return proof.data();
                                    })
                            .filter(
                                    data ->
                                            configured.stream()
                                                    .anyMatch(source -> configured(data, source)))
                            .toList();
                });
    }

    private static boolean configured(ChannelSourceWithdrawalProvider.Data data, SourceReq source) {
        var query = data.query();
        return source.sourceOwner() == query.owner()
                && source.sourceRef().equals(query.sourceRef())
                && source.selectionRevision().equals(query.previousRevision());
    }

    public String record(ChannelSourceWithdrawalProvider.Facts proof, String notice) {
        SourceWithdrawalPolicy.validate(proof.data().query(), proof, Clock.systemUTC());
        return db.commands.atomic(
                () -> {
                    var q = proof.data().query();
                    SourceWithdrawalPolicy.validate(q, proof, Clock.systemUTC());
                    var original =
                            rows.selectOne(
                                    Wrappers.<CimSourceWithdrawal>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("entity_no", q.entityNo())
                                            .eq("store_no", q.storeNo())
                                            .eq("source_owner", q.owner().name())
                                            .eq("source_ref", q.sourceRef())
                                            .eq("previous_revision", q.previousRevision())
                                            .eq(
                                                    "withdrawal_revision",
                                                    proof.data().withdrawalRevision())
                                            .last("FOR UPDATE"));
                    if (original != null) {
                        if (!original.getContentHash().equals(proof.contentHash()))
                            throw new IllegalStateException("Original withdrawal changed");
                        return original.getWithdrawalNo();
                    }
                    rows.update(
                            Wrappers.<CimSourceWithdrawal>update()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", q.entityNo())
                                    .eq("store_no", q.storeNo())
                                    .eq("source_owner", q.owner().name())
                                    .eq("source_ref", q.sourceRef())
                                    .eq("previous_revision", q.previousRevision())
                                    .eq("status", "CURRENT")
                                    .set("status", "SUPERSEDED")
                                    .setSql("version=version+1"));
                    var row = CatalogPersistence.audit(new CimSourceWithdrawal(), "SYSTEM");
                    row.setWithdrawalNo(BizKey.next("CISW"));
                    row.setEntityNo(q.entityNo());
                    row.setStoreNo(q.storeNo());
                    row.setSourceOwner(q.owner().name());
                    row.setSourceRef(q.sourceRef());
                    row.setPreviousRevision(q.previousRevision());
                    row.setWithdrawalRevision(proof.data().withdrawalRevision());
                    row.setContentHash(proof.contentHash());
                    row.setProofSnapshot(db.json.writeValueAsString(proof));
                    row.setNoticeNo(notice);
                    row.setStatus("CURRENT");
                    rows.insert(row);
                    return row.getWithdrawalNo();
                });
    }

    /**
     * Only an explicit native ACTIVE disposition plus independently valid current publication calls
     * this.
     */
    public void active(ChannelSourceWithdrawalProvider.Disposition current, Validation positive) {
        var query = current.query();
        SourceWithdrawalPolicy.disposition(query, current, Clock.systemUTC());
        if (current.state() != ChannelSourceWithdrawalProvider.State.ACTIVE
                || positive == null
                || positive.status() != Status.VALID
                || !query.sourceRef().equals(positive.canonicalRef())
                || positive.validUntil() == null
                || !positive.validUntil().isAfter(Instant.now()))
            throw new IllegalStateException("Original active source proof is required");
        db.commands.atomic(
                () ->
                        rows.update(
                                Wrappers.<CimSourceWithdrawal>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("entity_no", query.entityNo())
                                        .eq("store_no", query.storeNo())
                                        .eq("source_owner", query.owner().name())
                                        .eq("source_ref", query.sourceRef())
                                        .eq("status", "CURRENT")
                                        .set("status", "SUPERSEDED")
                                        .setSql("version=version+1")));
    }
}
