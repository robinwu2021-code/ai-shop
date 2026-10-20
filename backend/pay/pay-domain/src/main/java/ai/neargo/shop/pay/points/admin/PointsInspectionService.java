package ai.neargo.shop.pay.points.admin;

import ai.neargo.shop.pay.points.admin.PointsOperatorJournal.OperatorResult;
import ai.neargo.shop.pay.points.model.AccountSnapshot;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.query.PointsStatsService.Snapshot;
import ai.neargo.shop.pay.points.query.PointsStatsService.PeriodSnapshot;
import ai.neargo.shop.pay.points.recon.PointsReconService.Report;

import java.time.LocalDate;
import java.util.List;

/** Whitelisted financial inspection; reads never repair balances or manufacture evidence. */
public interface PointsInspectionService {
    AccountView account(String typeCode, HolderType holderType, String holderNo);

    AccountPage accounts(String typeCode, HolderType holderType, String holderNo, long after, int limit);

    TransactionPage transactions(String typeCode, TransactionDirection direction, LocalDate from, LocalDate to,
            String storeNo, String userNo, long after, int limit);

    TransactionDetail transaction(String typeCode, String voucherNo);

    OperatorResult freeze(Freeze request, String actor);

    VoucherPage vouchers(String typeCode, long after, int limit);

    EntryPage entries(
            String typeCode,
            HolderType holderType,
            String holderNo,
            String voucherNo,
            long after,
            int limit);

    FlowPage flows(String typeCode, long after, int limit);

    PositionView position(String typeCode, PositionMove.Kind kind, String reference);

    Snapshot stats(String typeCode, LocalDate day, StatsScope scope, String subjectNo);

    PeriodSnapshot periodStats(String typeCode, LocalDate from, LocalDate to);

    OperatorResult rebuildStats(StatsRebuild request, String actor);

    Report reconciliation(String typeCode, LocalDate day);

    OperatorResult reconcile(ReconRun request, String actor);

    enum StatsScope {
        TOTAL,
        STORE,
        USER
    }

    record AccountView(AccountSnapshot snapshot, boolean exists, long version) {}

    record AccountPage(List<AccountView> rows, long next, boolean hasMore) {
        public AccountPage { rows = List.copyOf(rows); }
    }

    enum TransactionDirection { EARN, REDEEM, REVERSAL }

    record TransactionRow(TransactionDirection direction, VoucherView voucher) {}

    record TransactionPage(List<TransactionRow> rows, long next, boolean hasMore) {
        public TransactionPage { rows = List.copyOf(rows); }
    }

    record TransactionDetail(TransactionDirection direction, VoucherView voucher, EntryPage entries) {}

    record Freeze(
            String requestId,
            String typeCode,
            String userNo,
            long expectedVersion,
            boolean frozen,
            String reason) {}

    record VoucherView(
            long id,
            String voucherNo,
            String bizRef,
            String currency,
            long ratePoints,
            long rateMinor) {}

    record VoucherPage(List<VoucherView> rows, long next, boolean hasMore) {
        public VoucherPage {
            rows = List.copyOf(rows);
        }
    }

    record EntryView(
            long id,
            String entryNo,
            String voucherNo,
            HolderType holderType,
            String holderNo,
            Bucket bucket,
            long deltaPoints,
            long afterPoints,
            long at) {}

    record EntryPage(List<EntryView> rows, long next, boolean hasMore) {
        public EntryPage {
            rows = List.copyOf(rows);
        }
    }

    enum FlowBucket {
        U,
        C,
        D,
        B,
        RESERVED_B,
        LOCKED_U,
        LOCKED_B
    }

    record FlowView(
            long id,
            String flowNo,
            String voucherNo,
            FlowBucket bucket,
            long deltaMinor,
            long afterMinor) {}

    record FlowPage(List<FlowView> rows, long next, boolean hasMore) {
        public FlowPage {
            rows = List.copyOf(rows);
        }
    }

    record PositionView(
            String typeCode,
            PositionMove.Kind kind,
            String reference,
            String state,
            long originalPoints,
            long processedPoints,
            long version) {}

    record StatsRebuild(String requestId, String typeCode, LocalDate day, String reason) {}

    record ReconRun(String requestId, String typeCode, String reason) {}
}
