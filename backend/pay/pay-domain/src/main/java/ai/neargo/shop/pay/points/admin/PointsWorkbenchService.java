package ai.neargo.shop.pay.points.admin;

import ai.neargo.shop.pay.points.admin.PointsOperatorJournal.OperatorResult;
import ai.neargo.shop.pay.points.job.PointsRecoveryService.Operation;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import ai.neargo.shop.pay.points.statement.StoreStatementService.Snapshot;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundPreparation;

import java.util.List;

/** Store-scoped operating actions cannot manufacture source orders or cash confirmations. */
public interface PointsWorkbenchService {
    StatementView statement(Reference request);

    StatementLinePage statementLines(StatementLines request);

    record StatementLines(String typeCode, String storeNo, String statementNo, long revision, long after, int limit) {}
    record StatementLine(String orderNo, String subOrderNo, long points) {}
    record StatementLinePage(List<StatementLine> rows, long next, boolean hasMore) {}

    StatementPage statements(String typeCode, String storeNo, long after, int limit);

    OperatorResult draftStatements(StatementDraft request, String actor);

    OperatorResult changeStatement(StatementChange request, String actor);

    RefundView refund(Reference request);

    OperatorResult reviseRefund(Revision request, String actor);

    OperatorResult prepareReceivable(ReceivableRecovery request, String actor);

    FundsView funds(FundsReference request);

    Execution executeRecovery(Recovery request, String actor);

    EventPage events(String typeCode, long after, int limit);

    OperatorResult retryEvent(EventRetry request, String actor);

    enum StatementAction {
        CONFIRM,
        DISPUTE,
        REDRAFT,
        PREPARE_PAYOUT
    }

    record StatementView(long id, Snapshot snapshot, long version) {}

    record StatementPage(List<StatementView> rows, long next, boolean hasMore) {
        public StatementPage {
            rows = List.copyOf(rows);
        }
    }

    record StatementDraft(
            String requestId,
            String typeCode,
            String storeNo,
            long fromMillis,
            long toMillis,
            String reason) {}

    record StatementChange(
            String requestId,
            String typeCode,
            String storeNo,
            String statementNo,
            long revision,
            StatementAction action,
            String reason) {}

    record Reference(String typeCode, String storeNo, String reference) {}

    record Revision(
            String requestId,
            String typeCode,
            String storeNo,
            String reference,
            long expectedVersion,
            String reason) {}

    record RefundView(
            String typeCode,
            String storeNo,
            String afterSaleNo,
            String subOrderNo,
            String state,
            long version,
            RefundPreparation preparation) {}

    record ReceivableRecovery(
            String requestId,
            String typeCode,
            String storeNo,
            String receivableNo,
            long expectedVersion,
            long amountMinor,
            String reason) {}

    record FundsReference(String typeCode, String storeNo, Operation operation, String reference) {}

    record FundsView(
            String typeCode,
            String storeNo,
            Operation operation,
            String reference,
            String state,
            long version,
            long grossMinor,
            long offsetMinor,
            long cashMinor) {}

    record Recovery(
            String requestId,
            String typeCode,
            String storeNo,
            Operation operation,
            String reference,
            long expectedVersion,
            String reason) {}

    record Execution(String reference, FundsStatus status) {}

    record EventView(
            long id,
            String eventNo,
            String kind,
            String state,
            long attempt,
            long nextAttempt,
            long version) {}

    record EventPage(List<EventView> rows, long next, boolean hasMore) {
        public EventPage {
            rows = List.copyOf(rows);
        }
    }

    record EventRetry(
            String requestId, String typeCode, long eventId, long expectedVersion, String reason) {}
}
