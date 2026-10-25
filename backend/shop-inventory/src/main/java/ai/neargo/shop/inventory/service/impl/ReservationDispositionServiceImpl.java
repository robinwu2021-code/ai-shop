package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.entity.InvOutboundLine;
import ai.neargo.shop.inventory.entity.InvOutboundOrder;
import ai.neargo.shop.inventory.entity.InvOwner;
import ai.neargo.shop.inventory.entity.InvReservation;
import ai.neargo.shop.inventory.entity.InvReservationLine;
import ai.neargo.shop.inventory.entity.InvStockBalance;
import ai.neargo.shop.inventory.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboundLineMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboundOrderMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ReservationLineMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ReservationMapper;
import ai.neargo.shop.inventory.service.ReservationDispositionService;
import ai.neargo.shop.inventory.service.ReservationService;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockDispositionPort.Confirmation;
import ai.neargo.shop.spi.product.StockDispositionPort.CurrentState;
import ai.neargo.shop.spi.product.StockDispositionPort.Disposition;
import ai.neargo.shop.spi.product.StockDispositionPort.Lookup;
import ai.neargo.shop.spi.product.StockDispositionPort.Original;
import ai.neargo.shop.spi.product.StockDispositionPort.Outcome;
import ai.neargo.shop.spi.product.StockDispositionPort.OwnerLine;
import ai.neargo.shop.spi.product.StockDispositionPort.Reservation;
import ai.neargo.shop.spi.product.StockDispositionPort.ReservationLookup;
import ai.neargo.shop.spi.product.StockDispositionPort.ReservationView;
import ai.neargo.shop.spi.product.StockDispositionPort.ReleaseView;
import ai.neargo.shop.spi.product.StockPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Original reservation rows, protected balances and actual outbound documents prove inventory
 * disposition.
 */
@Service
@ConditionalOnInventory
public class ReservationDispositionServiceImpl implements ReservationDispositionService {
    private final ReservationMapper heads;
    private final ReservationLineMapper lines;
    private final OwnerMapper owners;
    private final BalanceMapper balances;
    private final OutboundOrderMapper outbound;
    private final OutboundLineMapper outboundLines;
    private final ReservationService reservations;
    private final ObjectMapper json;

    public ReservationDispositionServiceImpl(
            ReservationMapper heads,
            ReservationLineMapper lines,
            OwnerMapper owners,
            BalanceMapper balances,
            OutboundOrderMapper outbound,
            OutboundLineMapper outboundLines,
            ReservationService reservations,
            ObjectMapper json) {
        this.heads = heads;
        this.lines = lines;
        this.owners = owners;
        this.balances = balances;
        this.outbound = outbound;
        this.outboundLines = outboundLines;
        this.reservations = reservations;
        this.json = json;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public ReleaseView releaseOriginal(ReservationLookup request) {
        var original = request.original();
        var snapshot = load(original);
        String invalid = invalid(original, snapshot);
        var before = snapshot;
        boolean held =
                invalid == null
                        && before.heads().stream()
                                .allMatch(
                                        head ->
                                                "HELD".equals(head.getStatus())
                                                        && head.getConfirmationOperationNo() == null
                                                        && protectedOriginal(
                                                                head,
                                                                before.lines()
                                                                        .get(
                                                                                head
                                                                                        .getReservationId())))
                        && before.lines().values().stream()
                                .flatMap(List::stream)
                                .allMatch(
                                        row ->
                                                nz(row.getIssuedQty()) == 0
                                                        && nz(row.getReleasedQty()) == 0);
        if (held) {
            for (var head : snapshot.heads()) {
                reservations.cancelByRef(head.getOwnerId(), head.getExternalRef());
            }
            snapshot = load(original);
        }
        var terminal = snapshot;
        boolean released =
                invalid(original, terminal) == null
                        && terminal.heads().stream()
                                .allMatch(
                                        head ->
                                                ("RELEASED".equals(head.getStatus())
                                                                || "EXPIRED"
                                                                        .equals(head.getStatus()))
                                                        && head.getConfirmationOperationNo()
                                                                == null)
                        && terminal.lines().values().stream()
                                .flatMap(List::stream)
                                .allMatch(
                                        row ->
                                                nz(row.getIssuedQty()) == 0
                                                        && nz(row.getReleasedQty())
                                                                == row.getQty());
        String outcome =
                released ? "RELEASED" : snapshot.heads().isEmpty() ? "UNKNOWN" : "REJECTED";
        String reason = released ? null : invalid == null ? "STOCK_NOT_RELEASABLE" : invalid;
        var evidence =
                result(
                        original,
                        request.reservationOperationNo(),
                        snapshot,
                        Outcome.REJECTED,
                        released ? CurrentState.RELEASED : CurrentState.INCONSISTENT,
                        reason);
        String hash =
                StockDispositionContract.hash(
                        json.writeValueAsString(
                                Arrays.asList(
                                        "STOCK_RESERVATION_RELEASE_V1",
                                        original,
                                        request.reservationOperationNo(),
                                        outcome,
                                        evidence.lines(),
                                        reason)));
        return new ReleaseView(
                original,
                request.reservationOperationNo(),
                outcome,
                evidence.lines(),
                hash,
                Instant.now(),
                reason);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public ReservationView observeReservation(ReservationLookup request) {
        var original = request.original();
        var snapshot = load(original);
        String invalid = invalid(original, snapshot);
        boolean held =
                invalid == null
                        && snapshot.heads().stream()
                                .allMatch(
                                        head ->
                                                "HELD".equals(head.getStatus())
                                                        && head.getConfirmationOperationNo() == null
                                                        && head.getExpiresAt() != null
                                                        && head.getExpiresAt()
                                                                .isAfter(LocalDateTime.now())
                                                        && protectedOriginal(
                                                                head,
                                                                snapshot.lines()
                                                                        .get(
                                                                                head
                                                                                        .getReservationId())))
                        && snapshot.lines().values().stream()
                                .flatMap(List::stream)
                                .allMatch(
                                        row ->
                                                nz(row.getIssuedQty()) == 0
                                                        && nz(row.getReleasedQty()) == 0);
        String outcome = held ? "RESERVED" : snapshot.heads().isEmpty() ? "UNKNOWN" : "REJECTED";
        String reason = held ? null : invalid == null ? "STOCK_NOT_HELD" : invalid;
        var evidence =
                result(
                        original,
                        request.reservationOperationNo(),
                        snapshot,
                        held
                                ? Outcome.PENDING
                                : snapshot.heads().isEmpty() ? Outcome.UNKNOWN : Outcome.REJECTED,
                        held ? CurrentState.RESERVED : CurrentState.INCONSISTENT,
                        reason);
        String hash =
                StockDispositionContract.hash(
                        json.writeValueAsString(
                                Arrays.asList(
                                        "STOCK_RESERVATION_V1",
                                        original,
                                        request.reservationOperationNo(),
                                        outcome,
                                        evidence.lines(),
                                        reason)));
        return new ReservationView(
                original,
                request.reservationOperationNo(),
                outcome,
                evidence.lines(),
                hash,
                Instant.now(),
                reason);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public Disposition confirmOriginal(Confirmation command) {
        var snapshot = load(command.original());
        var before = inspect(command.original(), command.operationNo(), snapshot);
        if (before.outcome() != Outcome.PENDING) {
            return before;
        }
        for (var head : snapshot.heads()) {
            if (head.getConfirmationOperationNo() == null) {
                if (!"HELD".equals(head.getStatus())) {
                    return rejected(
                            command.original(),
                            command.operationNo(),
                            snapshot,
                            CurrentState.INCONSISTENT,
                            "UNTRACKED_COMPLETED_ORIGINAL");
                }
                head.setConfirmationOperationNo(command.operationNo());
                head.setDispositionRevision(Math.addExact(head.getDispositionRevision(), 1));
                changed(heads.updateById(head));
            }
        }
        // V2 dispatch remains the real HOST InventoryStockPort's durable ALLOCATE command.
        // Claiming this operation does not allocate a single unit.
        if (command.original().lifecycleVersion() == 1) {
            reservations.commitByRef(command.original().orderNo(), "MANUAL_SERVICE");
        }
        return inspect(command.original(), command.operationNo(), load(command.original()));
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public Disposition observeOriginal(Lookup query) {
        return inspect(query.original(), query.operationNo(), load(query.original()));
    }

    private Snapshot load(Original original) {
        var originalHeads = heads.lockOriginal(original.orderNo());
        var all = new LinkedHashMap<String, List<InvReservationLine>>();
        for (var head : originalHeads) {
            all.put(head.getReservationId(), lines.lockOriginal(head.getReservationId()));
        }
        return new Snapshot(originalHeads, all);
    }

    private Disposition inspect(Original original, String operation, Snapshot snapshot) {
        String invalid = invalid(original, snapshot);
        if (invalid != null) {
            return rejected(
                    original,
                    operation,
                    snapshot,
                    snapshot.heads().isEmpty() ? CurrentState.MISSING : CurrentState.INCONSISTENT,
                    invalid);
        }
        if (snapshot.heads().stream().anyMatch(head -> "EXPIRED".equals(head.getStatus()))) {
            return rejected(original, operation, snapshot, CurrentState.EXPIRED, "STOCK_EXPIRED");
        }
        if (snapshot.heads().stream().anyMatch(head -> "RELEASED".equals(head.getStatus()))
                || snapshot.lines().values().stream()
                        .flatMap(List::stream)
                        .anyMatch(row -> nz(row.getReleasedQty()) > 0)) {
            return rejected(original, operation, snapshot, CurrentState.RELEASED, "STOCK_RELEASED");
        }
        if (snapshot.heads().stream()
                .anyMatch(
                        head ->
                                head.getConfirmationOperationNo() != null
                                        && !operation.equals(head.getConfirmationOperationNo()))) {
            return rejected(
                    original,
                    operation,
                    snapshot,
                    CurrentState.INCONSISTENT,
                    "STOCK_OPERATION_CONFLICT");
        }
        boolean held = snapshot.heads().stream().allMatch(head -> "HELD".equals(head.getStatus()));
        boolean allocated =
                original.lifecycleVersion() == 2
                        && snapshot.heads().stream()
                                .allMatch(head -> "ALLOCATED".equals(head.getStatus()));
        boolean consumed =
                snapshot.heads().stream().allMatch(head -> "COMMITTED".equals(head.getStatus()));
        if (held || allocated) {
            for (var head : snapshot.heads()) {
                var rows = snapshot.lines().get(head.getReservationId());
                if (rows.stream().anyMatch(row -> nz(row.getIssuedQty()) != 0)
                        || !protectedOriginal(head, rows)) {
                    return rejected(
                            original,
                            operation,
                            snapshot,
                            CurrentState.INCONSISTENT,
                            "STOCK_PROTECTION_MISSING");
                }
            }
        }
        boolean marked =
                snapshot.heads().stream()
                        .allMatch(head -> operation.equals(head.getConfirmationOperationNo()));
        if (consumed) {
            if (!marked || !consumedEvidence(snapshot, original.lifecycleVersion())) {
                return rejected(
                        original,
                        operation,
                        snapshot,
                        CurrentState.INCONSISTENT,
                        "STOCK_OUTBOUND_UNPROVEN");
            }
            return result(
                    original, operation, snapshot, Outcome.APPLIED, CurrentState.CONSUMED, null);
        }
        if (allocated && marked) {
            return result(
                    original, operation, snapshot, Outcome.APPLIED, CurrentState.ALLOCATED, null);
        }
        if (held) {
            return result(
                    original,
                    operation,
                    snapshot,
                    Outcome.PENDING,
                    CurrentState.RESERVED,
                    "STOCK_RESERVED");
        }
        return rejected(
                original,
                operation,
                snapshot,
                CurrentState.INCONSISTENT,
                "STOCK_MIXED_DISPOSITION");
    }

    private String invalid(Original original, Snapshot snapshot) {
        if (!"INVENTORY".equals(original.authority()) || snapshot.heads().isEmpty()) {
            return "STOCK_ORIGINAL_MISSING";
        }
        var needs = new ArrayList<StockPort.SkuQty>();
        for (var head : snapshot.heads()) {
            var owner =
                    owners.selectOne(
                            Wrappers.<InvOwner>lambdaQuery()
                                    .eq(InvOwner::getOwnerId, head.getOwnerId()));
            if (owner == null
                    || !original.entityNo().equals(owner.getExternalRef())
                    || !Objects.equals(original.lifecycleVersion(), head.getLifecycleVersion())
                    || !original.manifestHash().equals(head.getSourceManifestHash())
                    || head.getDispositionRevision() == null
                    || head.getDispositionRevision() < 1) {
                return "STOCK_OWNER_MANIFEST_CONFLICT";
            }
            var rows = snapshot.lines().get(head.getReservationId());
            if (rows.isEmpty()) {
                return "STOCK_MANIFEST_COVERAGE";
            }
            for (var row : rows) {
                if (!head.getOwnerId().equals(row.getOwnerId())
                        || row.getSourceSkuNo() == null
                        || row.getFulfillmentRef() == null
                        || row.getQty() == null
                        || row.getQty() <= 0
                        || row.getItemId() == null
                        || row.getLocationId() == null
                        || nz(row.getIssuedQty()) < 0
                        || nz(row.getReleasedQty()) < 0
                        || nz(row.getIssuedQty()) + nz(row.getReleasedQty()) > row.getQty()) {
                    return "STOCK_LINE_CONFLICT";
                }
                needs.add(
                        new StockPort.SkuQty(
                                row.getSourceSkuNo(),
                                row.getQty(),
                                row.getSourceStoreNo(),
                                row.getFulfillmentRef(),
                                head.getLifecycleVersion()));
            }
        }
        return StockDispositionContract.manifestHash(StockDispositionContract.needs(needs))
                        .equals(original.manifestHash())
                ? null
                : "STOCK_MANIFEST_COVERAGE";
    }

    private boolean protectedOriginal(InvReservation head, List<InvReservationLine> rows) {
        var totals = new TreeMap<String, Integer>();
        for (var row : rows) {
            totals.merge(row.getItemId() + ":" + row.getLocationId(), row.getQty(), Math::addExact);
        }
        for (var row : rows) {
            var balance =
                    balances.selectOne(
                            Wrappers.<InvStockBalance>lambdaQuery()
                                    .eq(InvStockBalance::getOwnerId, head.getOwnerId())
                                    .eq(InvStockBalance::getItemId, row.getItemId())
                                    .eq(InvStockBalance::getLocationId, row.getLocationId())
                                    .last("FOR UPDATE"));
            int need = totals.get(row.getItemId() + ":" + row.getLocationId());
            if (balance == null
                    || balance.getReserved() == null
                    || balance.getOnHand() == null
                    || balance.getReserved() < need
                    || balance.getOnHand() < need) {
                return false;
            }
        }
        return true;
    }

    private boolean consumedEvidence(Snapshot snapshot, int lifecycle) {
        for (var head : snapshot.heads()) {
            var rows = snapshot.lines().get(head.getReservationId());
            if (lifecycle == 1) {
                if (!outboundMatches(head, head.getOutboundNo(), rows)) {
                    return false;
                }
            } else {
                if (rows.stream()
                        .anyMatch(
                                row ->
                                        nz(row.getIssuedQty()) != row.getQty()
                                                || row.getOutboundNo() == null)) {
                    return false;
                }
                var groups = new LinkedHashMap<String, List<InvReservationLine>>();
                for (var row : rows) {
                    groups.computeIfAbsent(row.getOutboundNo(), key -> new ArrayList<>()).add(row);
                }
                for (var entry : groups.entrySet()) {
                    if (!outboundMatches(head, entry.getKey(), entry.getValue())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean outboundMatches(
            InvReservation head, String no, List<InvReservationLine> expected) {
        if (no == null) {
            return false;
        }
        var record =
                outbound.selectOne(
                        Wrappers.<InvOutboundOrder>lambdaQuery()
                                .eq(InvOutboundOrder::getOutboundNo, no));
        if (record == null
                || !"POSTED".equals(record.getStatus())
                || !"SALE".equals(record.getPurpose())
                || !head.getOwnerId().equals(record.getOwnerId())
                || !head.getExternalRef().equals(record.getSourceRef())
                || !head.getReservationId().equals(record.getReservationId())
                || expected.stream()
                        .anyMatch(row -> !record.getLocationId().equals(row.getLocationId()))) {
            return false;
        }
        var wanted = new TreeMap<String, Integer>();
        for (var row : expected) {
            wanted.merge(row.getItemId(), row.getQty(), Math::addExact);
        }
        var actual = new TreeMap<String, Integer>();
        var actualLines =
                outboundLines.selectList(
                        Wrappers.<InvOutboundLine>lambdaQuery()
                                .eq(InvOutboundLine::getOutboundNo, no));
        for (var row : actualLines) {
            if (!head.getOwnerId().equals(row.getOwnerId())
                    || row.getQty() == null
                    || row.getQty() <= 0) {
                return false;
            }
            actual.merge(row.getItemId(), row.getQty(), Math::addExact);
        }
        return wanted.equals(actual)
                && Objects.equals(
                        record.getTotalQty(),
                        wanted.values().stream().mapToInt(Integer::intValue).sum());
    }

    private Disposition rejected(
            Original original,
            String operation,
            Snapshot snapshot,
            CurrentState state,
            String reason) {
        return result(original, operation, snapshot, Outcome.REJECTED, state, reason);
    }

    private Disposition result(
            Original original,
            String operation,
            Snapshot snapshot,
            Outcome outcome,
            CurrentState state,
            String reason) {
        var result = new ArrayList<OwnerLine>();
        for (var head : snapshot.heads()) {
            for (var row : snapshot.lines().get(head.getReservationId())) {
                int qty = nz(row.getQty()), released = nz(row.getReleasedQty());
                boolean consumed = "COMMITTED".equals(head.getStatus());
                var lineState =
                        consumed
                                ? CurrentState.CONSUMED
                                : "ALLOCATED".equals(head.getStatus())
                                        ? CurrentState.ALLOCATED
                                        : "HELD".equals(head.getStatus())
                                                ? CurrentState.RESERVED
                                                : "EXPIRED".equals(head.getStatus())
                                                        ? CurrentState.EXPIRED
                                                        : "RELEASED".equals(head.getStatus())
                                                                ? CurrentState.RELEASED
                                                                : CurrentState.INCONSISTENT;
                result.add(
                        new OwnerLine(
                                head.getReservationId() + ":" + row.getLineNo(),
                                row.getSourceSkuNo(),
                                row.getSourceStoreNo(),
                                row.getFulfillmentRef(),
                                head.getOwnerId(),
                                row.getItemId(),
                                row.getLocationId(),
                                null,
                                qty,
                                lineState == CurrentState.ALLOCATED ? qty : 0,
                                consumed ? qty : nz(row.getIssuedQty()),
                                released,
                                head.getDispositionRevision() == null
                                        ? 0
                                        : head.getDispositionRevision(),
                                lineState));
            }
        }
        String hash =
                StockDispositionContract.hash(
                        json.writeValueAsString(
                                Arrays.asList(
                                        "STOCK_DISPOSITION_V1",
                                        original,
                                        operation,
                                        outcome,
                                        state,
                                        result,
                                        reason)));
        return new Disposition(
                original, operation, outcome, state, result, hash, Instant.now(), reason);
    }

    private static void changed(int rows) {
        if (rows != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private record Snapshot(
            List<InvReservation> heads, Map<String, List<InvReservationLine>> lines) {}
}
