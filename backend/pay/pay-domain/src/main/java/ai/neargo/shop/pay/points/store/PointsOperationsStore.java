package ai.neargo.shop.pay.points.store;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded operational reads and replaceable metadata; no account, fund or position writes. */
public interface PointsOperationsStore {
    enum Kind {
        ACCOUNT("pts_account"),
        VOUCHER("pts_voucher"),
        ENTRY("pts_ledger_entry"),
        FLOW("pts_pool_flow"),
        RESERVE("pts_refund_reserve"),
        GRANT("pts_grant_position"),
        REDEEM("pts_redeem_position"),
        REFUND("pts_refund_preparation"),
        PAYOUT("pts_payout_ticket"),
        TRANSFER("pts_fund_transfer"),
        RECEIVABLE("pts_receivable"),
        LIFECYCLE("pts_lifecycle_fact"),
        INBOX("pts_inbox"),
        OUTBOX("pts_outbox"),
        STATEMENT("pts_store_statement"),
        REQUEST("pts_pool_request"),
        PLAN("pts_topup_plan");
        private final String table;

        Kind(String table) {
            this.table = table;
        }

        public String table() {
            return table;
        }
    }

    List<Map<String, Object>> receivables(String typeCode, String storeNo, long after, int limit);

    List<Map<String, Object>> statements(String typeCode, String storeNo, long after, int limit);

    Map<String, Object> lockEvent(long id);

    Map<String, Object> accountHead(
            String typeCode,
            ai.neargo.shop.pay.points.model.HolderType holderType,
            String holderNo);

    List<Map<String, Object>> entries(
            String typeCode,
            ai.neargo.shop.pay.points.model.HolderType holderType,
            String holderNo,
            String voucherNo,
            long after,
            int limit);

    long outstandingReceivable(String typeCode);

    List<Map<String, Object>> switches(String typeCode, long after, int limit);

    List<Map<String, Object>> agreements(String typeCode, String storeNo, long after, int limit);

    long manualEvents(String typeCode);

    Map<String, Object> plan(String planNo, String period);

    Map<String, Object> lockPlan(String planNo, String period);

    List<Map<String, Object>> plans(String typeCode, long after, int limit, boolean configurations);

    void savePlan(
            String typeCode,
            String planNo,
            String period,
            String state,
            String payload,
            long expectedVersion);

    List<Map<String, Object>> pending(
            String typeCode, Kind kind, List<String> states, long after, int limit);

    List<Map<String, Object>> lifecycleAccounts(String typeCode, long after, int limit);

    List<Map<String, Object>> page(String typeCode, Kind kind, long after, int limit);

    List<Map<String, Object>> vouchers(String typeCode, LocalDate day, long after, int limit);

    List<Map<String, Object>> dayRows(
            String typeCode, Kind kind, LocalDate day, long after, int limit);

    List<Map<String, Object>> postingEvents(String typeCode, int limit);

    void projected(long id, long version);

    long evidenceTotal(String typeCode);

    Map<String, Object> stat(String typeCode, LocalDate day, String scope);

    void saveStat(String typeCode, LocalDate day, String scope, String state, String payload);

    void clearStats(String typeCode, LocalDate day);

    List<String> heldSubOrders(long olderThan, int limit);

    Set<String> grantedSubOrders(Collection<String> subOrders);
}
