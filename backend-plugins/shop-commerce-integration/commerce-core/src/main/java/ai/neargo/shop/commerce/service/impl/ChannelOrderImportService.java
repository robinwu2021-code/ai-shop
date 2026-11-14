package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.audit;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.now;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.require;

import ai.neargo.shop.commerce.entity.CimAuthorizationRevision;
import ai.neargo.shop.commerce.entity.CimConnectionRevision;
import ai.neargo.shop.commerce.entity.CimOrderLineLink;
import ai.neargo.shop.commerce.entity.CimOrderLink;
import ai.neargo.shop.commerce.policy.ChannelOrderAllocationPolicy;
import ai.neargo.shop.commerce.policy.ChannelOrderEconomicPolicy;
import ai.neargo.shop.commerce.policy.ChannelOrderPolicy;
import ai.neargo.shop.commerce.policy.ChannelPreparationPolicy;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;
import ai.neargo.shop.spi.commerce.ChannelRecoveryPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Durable original import intent precedes owner preparation. All cross-owner calls happen outside
 * local transactions.
 */
public final class ChannelOrderImportService implements ChannelRecoveryPort {
    private final ChannelOrderServiceImpl data;
    private final ChannelOrderMappingResolver mappings;
    private final Supplier<List<ChannelOrderIngestPort>> owners;
    private final Clock clock;

    public ChannelOrderImportService(
            ChannelOrderServiceImpl data,
            ChannelOrderMappingResolver mappings,
            Supplier<List<ChannelOrderIngestPort>> owners,
            Clock clock) {
        this.data = data;
        this.mappings = mappings;
        this.owners = owners;
        this.clock = clock;
    }

    @Override
    public String workflow() {
        return "ORIGINAL_ORDER_IMPORT";
    }

    private record Claim(
            CimOrderLink row,
            long fence,
            ChannelOrderGateway.Snapshot snapshot,
            ChannelOrderGateway.Query query,
            boolean automatic) {}

    /**
     * A crashed prepare is recovered with the same import reference; no second local/core order is
     * invented.
     */
    @Override
    public int recoverDue(int limit) {
        require(limit >= 1 && limit <= 100);
        var due =
                data.db.commands.query(
                        () ->
                                data.orders.selectList(
                                        Wrappers.<CimOrderLink>query()
                                                .eq("tenant_no", "MAIN")
                                                .in(
                                                        "import_status",
                                                        "RECEIVED",
                                                        "WAITING_MAPPING",
                                                        "WAITING_OWNER",
                                                        "PREPARED",
                                                        "WAITING_CONFIRMATION",
                                                        "WAITING_CANCELLATION")
                                                .le("next_attempt_at", now())
                                                .orderByAsc("next_attempt_at", "id")
                                                .last("LIMIT " + limit)));
        int completed = 0;
        for (var candidate : due) {
            Claim claim = null;
            try {
                claim = data.db.commands.atomic(() -> claim(candidate));
                if (claim != null && process(claim)) completed++;
            } catch (RuntimeException unavailable) {
                if (claim != null) defer(claim, "WAITING_OWNER");
            }
        }
        return completed;
    }

    private Claim claim(CimOrderLink candidate) {
        var c =
                data.db.connection(
                        candidate.getEntityNo(),
                        candidate.getStoreNo(),
                        candidate.getConnectionNo(),
                        true,
                        false);
        var row =
                data.scoped(
                        candidate.getEntityNo(),
                        candidate.getStoreNo(),
                        candidate.getChannelOrderNo(),
                        true);
        if (!Set.of(
                                "RECEIVED",
                                "WAITING_MAPPING",
                                "WAITING_OWNER",
                                "PREPARED",
                                "WAITING_CONFIRMATION",
                                "WAITING_CANCELLATION")
                        .contains(row.getImportStatus())
                || row.getNextAttemptAt() == null
                || row.getNextAttemptAt().isAfter(now())
                || row.getLeaseUntil() != null && row.getLeaseUntil().isAfter(now())) return null;
        var snapshot =
                data.db.json.readValue(
                        data.revision(row).getNormalizedSnapshot(),
                        ChannelOrderGateway.Snapshot.class);
        var query =
                row.getImportSnapshot() == null
                        ? scope(c, snapshot.document().externalOrderRef(), null)
                        : null;
        var route =
                data.db.revisions.selectOne(
                        Wrappers.<CimConnectionRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", row.getEntityNo())
                                .eq("connection_no", row.getConnectionNo())
                                .eq("revision_no", row.getConnectionRevisionNo()));
        require(route != null && route.getSourceOwner().equals(row.getSourceOwner()));
        long fence = Math.addExact(row.getFence(), 1);
        require(
                data.orders.update(
                                Wrappers.<CimOrderLink>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("id", row.getId())
                                        .eq("version", row.getVersion())
                                        .set("fence", fence)
                                        .set("attempt_no", row.getAttemptNo() + 1)
                                        .set("version", row.getVersion() + 1)
                                        .set("lease_until", now().plusMinutes(2))
                                        .set("next_attempt_at", now().plusMinutes(2)))
                        == 1);
        return new Claim(row, fence, snapshot, query, "AUTO".equals(route.getAcceptMode()));
    }

    private ChannelOrderGateway.Query scope(
            CatalogPersistence.Context c, String order, String revision) {
        require(
                "ACTIVE".equals(c.connection().getStatus())
                        && "ACTIVE".equals(c.account().getStatus()));
        var grant =
                data.db.authorizations.selectOne(
                        Wrappers.<CimAuthorizationRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", c.connection().getEntityNo())
                                .eq("account_no", c.account().getAccountNo())
                                .eq("authorization_no", c.account().getActiveAuthorizationNo()));
        require(
                grant != null
                        && "GRANTED".equals(grant.getStatus())
                        && grant.getCredentialRef() != null
                        && (grant.getExpiresAt() == null || grant.getExpiresAt().isAfter(now())));
        var granted = data.db.json.readTree(grant.getGrantedScopes());
        require(granted.isArray());
        boolean orderGranted = false;
        for (var item : granted)
            if (item.isString() && "ORDER".equals(item.asString())) orderGranted = true;
        require(orderGranted);
        return new ChannelOrderGateway.Query(
                c.connection().getEntityNo(),
                c.connection().getStoreNo(),
                c.connection().getConnectionNo(),
                c.connection().getProfileNo(),
                c.account().getExternalAccountRef(),
                c.connection().getExternalStoreRef(),
                order,
                revision,
                grant.getAuthorizationNo(),
                grant.getCredentialRef());
    }

    private boolean process(Claim claim) {
        var row = claim.row();
        var providers =
                owners.get().stream()
                        .filter(owner -> owner.owner().name().equals(row.getSourceOwner()))
                        .toList();
        require(providers.size() <= 1);
        if (providers.isEmpty()) {
            defer(claim, "WAITING_OWNER");
            return false;
        }
        var owner = providers.getFirst();
        ChannelOrderIngestPort.Command command;
        if (row.getImportSnapshot() == null) {
            var gateway = data.gateways.get(claim.query().profileNo());
            if (gateway == null) {
                defer(claim, "WAITING_OWNER");
                return false;
            }
            var latest = gateway.readOrder(claim.query());
            ChannelOrderPolicy.validate(claim.query(), latest, clock);
            if (!latest.documentHash().equals(claim.snapshot().documentHash())) {
                defer(claim, "WAITING_REVISION");
                return false;
            }
            List<ChannelOrderIngestPort.MappedLine> lines;
            try {
                lines = mappings.resolve(row, latest.document());
            } catch (RuntimeException unresolved) {
                defer(claim, "WAITING_MAPPING");
                return false;
            }
            command =
                    new ChannelOrderIngestPort.Command(
                            row.getImportRef(),
                            row.getEntityNo(),
                            row.getStoreNo(),
                            row.getConnectionNo(),
                            row.getConnectionRevisionNo(),
                            row.getActiveRevisionNo(),
                            latest,
                            lines);
            var frozen = command;
            boolean saved =
                    data.db.commands.atomic(
                            () -> {
                                var current = current(claim);
                                if (current == null) return false;
                                return data.orders.update(
                                                Wrappers.<CimOrderLink>update()
                                                        .eq("tenant_no", "MAIN")
                                                        .eq("id", current.getId())
                                                        .eq("fence", claim.fence())
                                                        .isNull("import_snapshot")
                                                        .set(
                                                                "import_snapshot",
                                                                data.db.json.writeValueAsString(
                                                                        frozen)))
                                        == 1;
                            });
            if (!saved) return false;
        } else
            command =
                    data.db.json.readValue(
                            row.getImportSnapshot(), ChannelOrderIngestPort.Command.class);
        var reference =
                new ChannelOrderIngestPort.Reference(
                        command.importNo(),
                        command.entityNo(),
                        command.storeNo(),
                        command.original().documentHash());
        if ("WAITING_CANCELLATION".equals(row.getImportStatus())) {
            ChannelOrderIngestPort.Prepared actual = null;
            try {
                actual = owner.getImport(reference);
            } catch (BizException missing) {
                if (missing.errorCode() != ErrorCode.NOT_FOUND) throw missing;
            }
            if (actual != null) {
                require(reference.equals(actual.reference()) && actual.status() != null);
                if (actual.status() == ChannelOrderIngestPort.Status.COMMITTED
                        || actual.status() == ChannelOrderIngestPort.Status.ACCEPTED) {
                    defer(claim, "WAITING_AFTER_SALE");
                    return false;
                }
            }
            // The original owner fences even an absent preparation; a delayed prepare cannot
            // resurrect it.
            var cancelled =
                    owner.cancelPreparedImport(
                            reference,
                            claim.snapshot().document().state()
                                            == ChannelOrderGateway.State.CANCELLED
                                    ? "PLATFORM_CANCELLED"
                                    : "PLATFORM_REJECTED");
            require(cancelled != null && reference.equals(cancelled.reference()));
            CommerceValidation.text(cancelled.cancellationReceiptRef(), 256);
            return data.db.commands.atomic(
                    () -> {
                        var current = current(claim);
                        if (current == null) return false;
                        String terminal =
                                claim.snapshot().document().state()
                                                == ChannelOrderGateway.State.CANCELLED
                                        ? "CANCELLED"
                                        : "REJECTED";
                        return data.orders.update(
                                        Wrappers.<CimOrderLink>update()
                                                .eq("tenant_no", "MAIN")
                                                .eq("id", current.getId())
                                                .eq("fence", claim.fence())
                                                .set("import_status", terminal)
                                                .set(
                                                        "acceptance_receipt_ref",
                                                        cancelled.cancellationReceiptRef())
                                                .set("lease_until", null)
                                                .set("next_attempt_at", null)
                                                .set("version", current.getVersion() + 1))
                                == 1;
                    });
        }
        ChannelOrderIngestPort.Prepared prepared;
        try {
            prepared = owner.getImport(reference);
        } catch (BizException missing) {
            if (missing.errorCode() != ErrorCode.NOT_FOUND) throw missing;
            prepared = owner.prepareImport(command);
        }
        require(
                prepared != null
                        && reference.equals(prepared.reference())
                        && prepared.status() != null);
        if (prepared.status() == ChannelOrderIngestPort.Status.WAITING_OWNER) {
            defer(claim, "WAITING_OWNER");
            return false;
        }
        if (prepared.status() == ChannelOrderIngestPort.Status.CANCELLED) {
            defer(claim, "CANCELLED");
            return false;
        }
        if (prepared.status() == ChannelOrderIngestPort.Status.PREPARED)
            CommerceValidation.text(prepared.preparationReceiptRef(), 256);
        var proof = prepared;
        boolean sameBusiness =
                ChannelOrderEconomicPolicy.sameBusiness(
                        command.original().document(), claim.snapshot().document());
        boolean externallyAccepted =
                command.original().document().state() == ChannelOrderGateway.State.ACCEPTED
                        || sameBusiness
                                && claim.snapshot().document().state()
                                        == ChannelOrderGateway.State.ACCEPTED
                        || row.getPlatformAcceptanceReceiptRef() != null;
        if (!externallyAccepted
                && !ChannelPreparationPolicy.ready(prepared, reference, clock.instant())) {
            defer(claim, "WAITING_OWNER");
            return false;
        }
        if (externallyAccepted) {
            var accepted =
                    owner.confirmAcceptance(
                            new ChannelOrderIngestPort.Confirmation(
                                    reference,
                                    row.getPlatformAcceptanceReceiptRef() == null
                                            ? command.original().originalReceiptRef()
                                            : row.getPlatformAcceptanceReceiptRef()));
            ChannelOrderAllocationPolicy.validate(command, accepted);
            return data.db.commands.atomic(
                    () -> {
                        var current = current(claim);
                        if (current == null) return false;
                        persistAllocations(current, command, accepted);
                        return data.orders.update(
                                        Wrappers.<CimOrderLink>update()
                                                .eq("tenant_no", "MAIN")
                                                .eq("id", current.getId())
                                                .eq("fence", claim.fence())
                                                .set(
                                                        "import_status",
                                                        (current.getActiveRevisionNo()
                                                                                .equals(
                                                                                        command
                                                                                                .externalSnapshotNo())
                                                                        || ChannelOrderEconomicPolicy
                                                                                .sameBusiness(
                                                                                        command.original()
                                                                                                .document(),
                                                                                        data.db
                                                                                                .json
                                                                                                .readValue(
                                                                                                        data.revision(
                                                                                                                        current)
                                                                                                                .getNormalizedSnapshot(),
                                                                                                        ChannelOrderGateway
                                                                                                                .Snapshot
                                                                                                                .class)
                                                                                                .document()))
                                                                ? "CONFIRMED"
                                                                : "WAITING_AMENDMENT")
                                                .set("acceptance_status", "CONFIRMED")
                                                .set(
                                                        "preparation_receipt_ref",
                                                        proof.preparationReceiptRef())
                                                .set(
                                                        "preparation_valid_until",
                                                        proof.validUntil() == null
                                                                ? null
                                                                : LocalDateTime.ofInstant(
                                                                        proof.validUntil(),
                                                                        ZoneOffset.UTC))
                                                .set("core_order_ref", accepted.orderNo())
                                                .set(
                                                        "source_order_ref",
                                                        accepted.businessDocumentNo())
                                                .set(
                                                        "acceptance_receipt_ref",
                                                        accepted.acceptanceReceiptRef())
                                                .set("lease_until", null)
                                                .set("next_attempt_at", null)
                                                .set("version", current.getVersion() + 1))
                                == 1;
                    });
        }
        return data.db.commands.atomic(
                () -> {
                    var current = current(claim);
                    if (current == null) return false;
                    return data.orders.update(
                                    Wrappers.<CimOrderLink>update()
                                            .eq("tenant_no", "MAIN")
                                            .eq("id", current.getId())
                                            .eq("fence", claim.fence())
                                            .set(
                                                    "import_status",
                                                    claim.automatic()
                                                                    || current
                                                                                    .getDecisionOperationNo()
                                                                            != null
                                                            ? "PREPARED"
                                                            : "WAITING_DECISION")
                                            .set(
                                                    "preparation_receipt_ref",
                                                    proof.preparationReceiptRef())
                                            .set(
                                                    "preparation_valid_until",
                                                    proof.validUntil() == null
                                                            ? null
                                                            : LocalDateTime.ofInstant(
                                                                    proof.validUntil(),
                                                                    ZoneOffset.UTC))
                                            .set("lease_until", null)
                                            .set(
                                                    "next_attempt_at",
                                                    claim.automatic()
                                                                    || current
                                                                                    .getDecisionOperationNo()
                                                                            != null
                                                            ? now().plusSeconds(30)
                                                            : null)
                                            .set("version", current.getVersion() + 1))
                            == 1;
                });
    }

    private void persistAllocations(
            CimOrderLink order,
            ChannelOrderIngestPort.Command command,
            ChannelOrderIngestPort.Accepted accepted) {
        require(data.lineLinks != null);
        require(
                data.lineLinks.selectCount(
                                Wrappers.<CimOrderLineLink>query()
                                        .eq("tenant_no", "MAIN")
                                        .eq("entity_no", order.getEntityNo())
                                        .eq("channel_order_no", order.getChannelOrderNo())
                                        .eq("order_revision_no", command.externalSnapshotNo()))
                        == 0);
        for (var allocation : accepted.allocations()) {
            var link = audit(new CimOrderLineLink(), "SYSTEM");
            link.setLinkNo(BizKey.next("CIOL"));
            link.setEntityNo(order.getEntityNo());
            link.setChannelOrderNo(order.getChannelOrderNo());
            link.setOrderRevisionNo(command.externalSnapshotNo());
            link.setExternalLineRef(allocation.externalLineRef());
            link.setParentExternalLineRef(allocation.parentExternalLineRef());
            link.setComponentKind(allocation.kind());
            link.setComponentRef(allocation.componentRef());
            link.setSourceLineRef(allocation.sourceLineRef());
            link.setCoreOrderRef(accepted.orderNo());
            link.setCoreItemRef(allocation.coreItemRef());
            link.setSaleQuantity(new BigDecimal(allocation.quantity()));
            link.setQuantityUnit(allocation.unit());
            link.setAmountMinor(allocation.amountMinor());
            link.setCurrency(allocation.currency());
            link.setAllocationEvidenceRef(allocation.allocationEvidenceRef());
            data.lineLinks.insert(link);
        }
    }

    private CimOrderLink current(Claim claim) {
        var row =
                data.scoped(
                        claim.row().getEntityNo(),
                        claim.row().getStoreNo(),
                        claim.row().getChannelOrderNo(),
                        true);
        return row.getFence() == claim.fence()
                        && Objects.equals(
                                row.getActiveRevisionNo(), claim.row().getActiveRevisionNo())
                        && row.getLeaseUntil() != null
                        && row.getLeaseUntil().isAfter(now())
                ? row
                : null;
    }

    private void defer(Claim claim, String status) {
        data.db.commands.atomic(
                () -> {
                    var row = current(claim);
                    if (row == null) return null;
                    String nextStatus =
                            "WAITING_CANCELLATION".equals(claim.row().getImportStatus())
                                            && "WAITING_OWNER".equals(status)
                                    ? "WAITING_CANCELLATION"
                                    : status;
                    boolean terminal =
                            Set.of("CANCELLED", "WAITING_REVISION", "WAITING_AFTER_SALE")
                                    .contains(nextStatus);
                    data.orders.update(
                            Wrappers.<CimOrderLink>update()
                                    .eq("tenant_no", "MAIN")
                                    .eq("id", row.getId())
                                    .eq("fence", claim.fence())
                                    .set("import_status", nextStatus)
                                    .set("lease_until", null)
                                    .set("next_attempt_at", terminal ? null : now().plusMinutes(1))
                                    .set("version", row.getVersion() + 1));
                    return null;
                });
    }
}
