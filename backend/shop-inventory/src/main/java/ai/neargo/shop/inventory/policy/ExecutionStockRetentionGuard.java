package ai.neargo.shop.inventory.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.entity.InvOutboundOrder;
import ai.neargo.shop.inventory.entity.InvReservation;
import ai.neargo.shop.inventory.entity.InvReservationLine;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ExecutionRetentionMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ExecutionRetentionClaimMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboundOrderMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ReservationMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ReservationLineMapper;
import ai.neargo.shop.inventory.mapper.RefundRestoreMapper;
import ai.neargo.shop.inventory.mapper.ReturnReceiptMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Local mapper-only fence: never calls Core, HOST, a stock service or another transaction under source roots. */
@Component("inventoryExecutionStockRetentionGuard")
@ConditionalOnInventory
public final class ExecutionStockRetentionGuard {
    private final ExecutionRetentionMapper links;
    private final ExecutionRetentionClaimMapper claims;
    private final ExecutionStockRetentionSchemaVerifier schema;
    private final ReservationMapper heads;
    private final ReservationLineMapper lines;
    private final OutboundOrderMapper outbound;
    private final RefundRestoreMapper refunds;
    private final ReturnReceiptMapper returns;

    public ExecutionStockRetentionGuard(ExecutionRetentionMapper links, ExecutionRetentionClaimMapper claims,
            ExecutionStockRetentionSchemaVerifier schema, ReservationMapper heads, ReservationLineMapper lines,
            OutboundOrderMapper outbound, RefundRestoreMapper refunds, ReturnReceiptMapper returns) {
        this.links = links;
        this.claims = claims;
        this.schema = schema;
        this.heads = heads;
        this.lines = lines;
        this.outbound = outbound;
        this.refunds = refunds;
        this.returns = returns;
    }

    /** Caller has completed all canonical roots; acquire every line before checking any link or effect. */
    public void requireUnretained(List<InvReservation> originals) {
        var installation = schema.installation();
        if (installation == ExecutionStockRetentionSchemaVerifier.Installation.ABSENT) {
            return;
        }
        if (installation != ExecutionStockRetentionSchemaVerifier.Installation.INSTALLED) {
            throw conflict();
        }
        var all = new ArrayList<InvReservationLine>();
        for (var head : originals) {
            all.addAll(lines.lockOriginal(head.getReservationId()));
        }
        for (var head : originals) {
            var memberIds = all.stream().filter(row -> head.getReservationId().equals(row.getReservationId()))
                    .map(InvReservationLine::getId).toList();
            for (var link : links.lockReservation(head.getOwnerId(), head.getReservationId())) {
                if (!memberIds.contains(link.getOriginalLineId()) || link.getRetentionRevision() == null
                        || link.getRetentionRevision() < 0 || !List.of("RETAINED", "RELEASED_NO_EFFECT")
                                .contains(link.getState())) {
                    throw conflict();
                }
                var claim = claims.lockComponent(link.getComponentNo());
                if (claim == null || !Objects.equals(claim.getRequestHash(), link.getRequestHash())
                        || !Objects.equals(claim.getReferenceHash(), link.getReferenceHash())
                        || !Objects.equals(claim.getComponentDescriptorHash(), link.getComponentDescriptorHash())
                        || !Objects.equals(claim.getState(), "RETAINED".equals(link.getState())
                                ? "RETAINED" : "SEALED_NO_EFFECT")) {
                    throw conflict();
                }
                if ("RETAINED".equals(link.getState())) {
                    throw conflict();
                }
            }
        }
    }

    /** Nonlocking candidate -> complete current roots/lines/links/claims -> outbound current revalidation. */
    public InvOutboundOrder lockReversibleOutbound(String owner, String document) {
        if (!schema.ownsCurrentTransaction()) {
            throw new IllegalStateException("SALE reversal guard requires its actual Inventory transaction");
        }
        for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof LockedOutbound locked && locked.guard() == this
                    && owner.equals(locked.owner()) && document.equals(locked.document())) {
                if (!locked.identity().equals(identity(locked.row()))) {
                    throw conflict();
                }
                // void -> public reverse shares the already-locked exact source/outbound, not a second root scan.
                // Synchronizations suspend with REQUIRES_NEW and disappear at transaction completion.
                return locked.row();
            }
        }
        var candidate = outbound.findReversalCandidate(owner, document);
        if (candidate == null) {
            // A stale RR absence must not turn an OUT sale into an unguarded reversal.
            // Public reverse permits absence only for an actual all-IN non-SALE ledger original.
            return null;
        }
        if ("SALE".equals(candidate.getPurpose())) {
            if (candidate.getSourceRef() == null || candidate.getSourceRef().isBlank()
                    || candidate.getReservationId() == null || candidate.getReservationId().isBlank()) {
                throw conflict();
            }
            var originals = heads.lockOriginal(candidate.getSourceRef());
            var members = new ArrayList<InvReservationLine>();
            for (var head : originals) {
                members.addAll(lines.lockOriginal(head.getReservationId()));
            }
            requireUnretained(originals);
            if (originals.isEmpty() || originals.stream().noneMatch(head ->
                    Objects.equals(head.getOwnerId(), candidate.getOwnerId())
                            && Objects.equals(head.getExternalRef(), candidate.getSourceRef())
                            && Objects.equals(head.getReservationId(), candidate.getReservationId()))) {
                throw conflict();
            }
            var ids = members.stream().map(InvReservationLine::getId).toList();
            if (ids.isEmpty()) {
                throw conflict();
            }
            var refundClaims = refunds.lockByOriginalLines(ids);
            var returnClaims = returns.lockByOriginalLines(ids);
            if (!refundClaims.isEmpty() || !returnClaims.isEmpty()) {
                throw conflict();
            }
        }
        var actual = outbound.selectOne(Wrappers.<InvOutboundOrder>lambdaQuery()
                .eq(InvOutboundOrder::getOwnerId, owner).eq(InvOutboundOrder::getOutboundNo, document)
                .last("FOR UPDATE"));
        if (actual == null || !Objects.equals(candidate.getId(), actual.getId())
                || !Objects.equals(candidate.getOwnerId(), actual.getOwnerId())
                || !Objects.equals(candidate.getOutboundNo(), actual.getOutboundNo())
                || !Objects.equals(candidate.getPurpose(), actual.getPurpose())
                || !Objects.equals(candidate.getSourceRef(), actual.getSourceRef())
                || !Objects.equals(candidate.getReservationId(), actual.getReservationId())) {
            // Never acquire another/lower source root after this outbound lock. Caller rolls back/retries.
            throw conflict();
        }
        TransactionSynchronizationManager.registerSynchronization(
                new LockedOutbound(this, owner, document, identity(actual), actual));
        return actual;
    }

    /** Unforgeable ephemeral validated lock handle, tied to Spring's current owner transaction, never persisted. */
    private record LockedOutbound(ExecutionStockRetentionGuard guard, String owner, String document,
            OutboundIdentity identity, InvOutboundOrder row) implements TransactionSynchronization {
    }

    private record OutboundIdentity(Long id, String owner, String document, String source, String reservation,
            String purpose, String location) {
    }

    private static OutboundIdentity identity(InvOutboundOrder row) {
        return new OutboundIdentity(row.getId(), row.getOwnerId(), row.getOutboundNo(), row.getSourceRef(),
                row.getReservationId(), row.getPurpose(), row.getLocationId());
    }

    private static BizException conflict() {
        return BizException.of(ErrorCode.CONFLICT);
    }
}
