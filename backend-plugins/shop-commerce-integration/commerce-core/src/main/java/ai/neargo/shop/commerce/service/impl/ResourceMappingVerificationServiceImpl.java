package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.audit;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.now;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.require;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.text;

import ai.neargo.shop.commerce.dto.MappingContracts.VerificationBatch;
import ai.neargo.shop.commerce.entity.CimOperation;
import ai.neargo.shop.commerce.entity.CimOperationAttempt;
import ai.neargo.shop.commerce.entity.CimResourceMapping;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.commerce.service.ResourceMappingVerificationService;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.ObjectType;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Proof;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Query;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Status;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Validation;
import ai.neargo.shop.spi.commerce.CommerceGateway.Capability;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.time.Instant;
import java.util.Objects;

/** 证明查询先留原意图；查询可重做，但旧证明不能越过新草稿或新授权的栅栏。 */
public final class ResourceMappingVerificationServiceImpl
        implements ResourceMappingVerificationService {
    private final MappingPersistence data;

    public ResourceMappingVerificationServiceImpl(MappingPersistence data) {
        this.data = data;
    }

    record Claim(
            MappingPersistence.Context context,
            Query query,
            ChannelResourceIdentityGateway gateway,
            long fence) {}

    public VerificationBatch verifyDue(int limit) {
        require(limit >= 1 && limit <= 100);
        int verified = 0, pending = 0, failed = 0;
        var rows =
                data.db.commands.query(
                        () ->
                                data.mappings.selectList(
                                        Wrappers.<CimResourceMapping>query()
                                                .eq("tenant_no", "MAIN")
                                                .le("next_verification_at", now())
                                                .and(
                                                        q ->
                                                                q.isNull("verification_lease_until")
                                                                        .or()
                                                                        .le(
                                                                                "verification_lease_until",
                                                                                now()))
                                                .orderByAsc("next_verification_at", "id")
                                                .last("LIMIT " + limit)));
        for (var row : rows)
            try {
                var claim = data.db.commands.atomic(() -> claim(row));
                if (claim == null) {
                    pending++;
                    defer(row);
                    continue;
                }
                Validation source = null;
                Proof remote = null;
                try {
                    source = data.validate(claim.context(), claim.context().latest());
                    remote = claim.gateway().query(claim.query());
                } catch (RuntimeException unknown) {
                    /* Outcome remains unconfirmed; only safe original proof queries may recur. */
                }
                Validation sourceProof = source;
                Proof remoteProof = remote;
                boolean ok = data.db.commands.atomic(() -> finish(claim, sourceProof, remoteProof));
                if (ok) verified++;
                else pending++;
            } catch (RuntimeException failure) {
                failed++;
                defer(row);
            }
        return new VerificationBatch(verified, pending, failed);
    }

    private Claim claim(CimResourceMapping candidate) {
        var c =
                data.scoped(
                        candidate.getEntityNo(),
                        candidate.getStoreNo(),
                        candidate.getMappingNo(),
                        true);
        var m = c.mapping();
        if (m.getNextVerificationAt() == null
                || m.getNextVerificationAt().isAfter(now())
                || !"DRAFT".equals(c.latest().getValidationStatus())
                || (m.getVerificationLeaseUntil() != null
                        && m.getVerificationLeaseUntil().isAfter(now()))) return null;
        var g = data.gateways.get(c.base().connection().getProfileNo());
        if (g == null || data.db.authority.get() == null) return null;
        require(g.capabilities().contains(Capability.CATALOG));
        var grant = data.db.authorized(c.base());
        require(
                c.latest()
                        .getConnectionRevisionNo()
                        .equals(c.base().connection().getActiveRevisionNo()));
        if (m.getVerificationOperationNo() != null) expire(m.getVerificationOperationNo());
        long fence = Math.addExact(m.getVersion(), 1);
        var op = audit(new CimOperation(), "SYSTEM");
        op.setOperationNo(BizKey.next("CIOP"));
        op.setEntityNo(m.getEntityNo());
        op.setStoreNo(m.getStoreNo());
        op.setConnectionNo(m.getConnectionNo());
        op.setDestination("CHANNEL");
        op.setOperationType("MAPPING_VERIFY");
        op.setBusinessRef(m.getMappingNo());
        op.setBusinessRevision(c.latest().getRevisionNo());
        op.setSemanticKey(ChannelIdentity.hash(op.getOperationNo()));
        op.setRequestHash(c.latest().getContentHash());
        op.setRequestRef("MAPPING:" + c.latest().getRevisionNo());
        op.setStatus("RUNNING");
        op.setCurrentAttemptNo(1L);
        op.setFence(fence);
        op.setLeaseUntil(now().plusSeconds(60));
        op.setAccountNo(c.base().account().getAccountNo());
        op.setProfileNo(c.base().connection().getProfileNo());
        op.setTargetScopeKind("CONNECTION");
        op.setTargetScopeKey(
                ChannelIdentity.hash(
                        c.base().connection().getChannelStoreKey() + ":" + m.getMappingNo()));
        data.db.operations.insert(op);
        var attempt = audit(new CimOperationAttempt(), "SYSTEM");
        attempt.setAttemptRef(BizKey.next("CIAT"));
        attempt.setEntityNo(m.getEntityNo());
        attempt.setOperationNo(op.getOperationNo());
        attempt.setAttemptNo(1L);
        attempt.setFence(fence);
        attempt.setAttemptKind("QUERY");
        attempt.setProviderRequestRef(op.getOperationNo());
        attempt.setStartedAt(now());
        attempt.setOutcome("RUNNING");
        data.db.attempts.insert(attempt);
        require(
                data.mappings.update(
                                Wrappers.<CimResourceMapping>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("id", m.getId())
                                        .eq("version", m.getVersion())
                                        .set("version", fence)
                                        .set("verification_operation_no", op.getOperationNo())
                                        .set("verification_lease_until", now().plusSeconds(60))
                                        .set("next_verification_at", now().plusSeconds(60)))
                        == 1);
        var q =
                new Query(
                        op.getOperationNo(),
                        m.getEntityNo(),
                        m.getStoreNo(),
                        m.getConnectionNo(),
                        c.base().connection().getProfileNo(),
                        c.base().account().getExternalAccountRef(),
                        c.base().connection().getExternalStoreRef(),
                        grant.getAuthorizationNo(),
                        grant.getCredentialRef(),
                        ObjectType.valueOf(m.getObjectType()),
                        data.identity(m));
        return new Claim(c, q, g, fence);
    }

    private void expire(String no) {
        var op =
                data.db.operations.selectOne(
                        Wrappers.<CimOperation>query()
                                .eq("tenant_no", "MAIN")
                                .eq("operation_no", no)
                                .last("FOR UPDATE"));
        require(op != null);
        data.db.operations.update(
                Wrappers.<CimOperation>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", op.getId())
                        .set("status", "UNKNOWN")
                        .set("lease_until", null));
        data.db.attempts.update(
                Wrappers.<CimOperationAttempt>update()
                        .eq("tenant_no", "MAIN")
                        .eq("operation_no", no)
                        .isNull("finished_at")
                        .set("finished_at", now())
                        .set("outcome", "UNKNOWN")
                        .set("error_code", "LEASE_EXPIRED"));
    }

    private boolean valid(Claim claim, Validation source, Proof proof) {
        try {
            require(
                    source != null
                            && source.validUntil().isAfter(Instant.now())
                            && proof != null
                            && proof.status() == Status.VERIFIED
                            && proof.validUntil() != null
                            && proof.validUntil().isAfter(Instant.now()));
            text(proof.evidenceRef(), 256);
            var q = claim.query();
            return q.entityNo().equals(proof.entityNo())
                    && q.storeNo().equals(proof.storeNo())
                    && q.connectionNo().equals(proof.connectionNo())
                    && q.objectType() == proof.objectType()
                    && q.identity().equals(proof.identity());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private boolean finish(Claim claim, Validation source, Proof proof) {
        var original = claim.context();
        var c =
                data.scoped(
                        original.mapping().getEntityNo(),
                        original.mapping().getStoreNo(),
                        original.mapping().getMappingNo(),
                        true);
        var m = c.mapping();
        var op =
                data.db.operations.selectOne(
                        Wrappers.<CimOperation>query()
                                .eq("tenant_no", "MAIN")
                                .eq("operation_no", claim.query().operationNo())
                                .last("FOR UPDATE"));
        require(op != null);
        var a =
                data.db.attempts.selectOne(
                        Wrappers.<CimOperationAttempt>query()
                                .eq("tenant_no", "MAIN")
                                .eq("operation_no", op.getOperationNo())
                                .eq("attempt_no", 1)
                                .last("FOR UPDATE"));
        require(a != null);
        String evidence = null;
        if (proof != null && proof.evidenceRef() != null)
            try {
                text(proof.evidenceRef(), 256);
                evidence = proof.evidenceRef();
            } catch (RuntimeException ignored) {
                /* unsafe evidence is not persisted */
            }
        boolean current =
                Objects.equals(m.getVerificationOperationNo(), op.getOperationNo())
                        && Objects.equals(m.getVersion(), claim.fence())
                        && Objects.equals(
                                m.getLatestRevisionNo(), original.latest().getRevisionNo())
                        && Objects.equals(
                                c.base().connection().getActiveRevisionNo(),
                                original.base().connection().getActiveRevisionNo())
                        && Objects.equals(
                                c.base().account().getActiveAuthorizationNo(),
                                claim.query().authorizationNo())
                        && m.getVerificationLeaseUntil() != null
                        && m.getVerificationLeaseUntil().isAfter(now());
        if (!current) {
            if (evidence != null)
                data.db.attempts.update(
                        Wrappers.<CimOperationAttempt>update()
                                .eq("tenant_no", "MAIN")
                                .eq("id", a.getId())
                                .isNull("late_evidence_ref")
                                .set("late_evidence_ref", evidence)
                                .set(
                                        "late_outcome",
                                        proof.status() == null
                                                ? "UNKNOWN"
                                                : proof.status().name()));
            if (a.getFinishedAt() == null)
                data.db.attempts.update(
                        Wrappers.<CimOperationAttempt>update()
                                .eq("tenant_no", "MAIN")
                                .eq("id", a.getId())
                                .isNull("finished_at")
                                .set("finished_at", now())
                                .set("outcome", "SUPERSEDED"));
            data.db.operations.update(
                    Wrappers.<CimOperation>update()
                            .eq("tenant_no", "MAIN")
                            .eq("id", op.getId())
                            .eq("status", "RUNNING")
                            .set("status", "SUPERSEDED")
                            .set("lease_until", null));
            return false;
        }
        boolean accepted = valid(claim, source, proof);
        try {
            data.db.authorized(c.base());
        } catch (RuntimeException invalid) {
            accepted = false;
        }
        String next = null;
        if (accepted) {
            var r =
                    data.append(
                            m,
                            Math.addExact(c.latest().getRevision(), 1),
                            BizKey.next("CIMR"),
                            Owner.valueOf(c.latest().getSourceOwner()),
                            c.latest().getSourceRef(),
                            c.latest().getSourceRevisionRef(),
                            c.latest().getSalesOfferRef(),
                            c.base().connection().getActiveRevisionNo(),
                            claim.query().authorizationNo(),
                            "TRUSTED_VERIFICATION",
                            source,
                            proof,
                            "SYSTEM");
            next = r.getRevisionNo();
        }
        String outcome = accepted ? "SUCCEEDED" : "UNKNOWN";
        data.db.attempts.update(
                Wrappers.<CimOperationAttempt>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", a.getId())
                        .isNull("finished_at")
                        .set("finished_at", now())
                        .set("outcome", outcome)
                        .set("response_ref", evidence)
                        .set("error_code", accepted ? null : "MAPPING_UNCONFIRMED"));
        data.db.operations.update(
                Wrappers.<CimOperation>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", op.getId())
                        .set("status", outcome)
                        .set("original_evidence_ref", evidence)
                        .set("lease_until", null));
        var update =
                Wrappers.<CimResourceMapping>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", m.getId())
                        .eq("version", claim.fence())
                        .eq("verification_operation_no", op.getOperationNo())
                        .set("version", claim.fence() + 1)
                        .set("verification_operation_no", null)
                        .set("verification_lease_until", null)
                        .set("next_verification_at", accepted ? null : now().plusMinutes(5));
        if (accepted) update.set("latest_revision_no", next).set("active_revision_no", next);
        require(data.mappings.update(update) == 1);
        return accepted;
    }

    private void defer(CimResourceMapping candidate) {
        data.db.commands.atomic(
                () -> {
                    var c =
                            data.scoped(
                                    candidate.getEntityNo(),
                                    candidate.getStoreNo(),
                                    candidate.getMappingNo(),
                                    true);
                    data.mappings.update(
                            Wrappers.<CimResourceMapping>update()
                                    .eq("tenant_no", "MAIN")
                                    .eq("id", candidate.getId())
                                    .eq("version", candidate.getVersion())
                                    .set("next_verification_at", now().plusMinutes(5)));
                    return null;
                });
    }
}
