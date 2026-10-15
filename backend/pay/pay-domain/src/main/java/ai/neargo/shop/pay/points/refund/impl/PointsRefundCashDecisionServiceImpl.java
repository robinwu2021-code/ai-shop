package ai.neargo.shop.pay.points.refund.impl;

import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.refund.PointsRefundCashDecisionService;
import ai.neargo.shop.pay.points.refund.impl.PointsRefundPreparationServiceImpl.RefundSnapshot;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** Freezes one resolved cash decision; this service neither creates cash payments nor calls the bank. */
@Service
@Transactional("payTxManager")
public class PointsRefundCashDecisionServiceImpl implements PointsRefundCashDecisionService {
    private final PointsReadStore read;
    private final PointsConfigurationStore configuration;
    private final PointsPostingService posting;
    private final ObjectMapper json = new ObjectMapper();

    public PointsRefundCashDecisionServiceImpl(PointsReadStore read, PointsConfigurationStore configuration,
                                               PointsPostingService posting) {
        this.read = read;
        this.configuration = configuration;
        this.posting = posting;
    }

    @Override
    public Decision authorize(String preparationNo, String inputHash, long revision) {
        require(preparationNo != null && !preparationNo.isBlank() && inputHash != null && revision > 0,
                "POINTS_REFUND_CASH_AUTHORIZATION_INVALID");
        var initial = read.position(PositionMove.Kind.REFUND.table(), preparationNo);
        require(initial != null, "POINTS_REFUND_PREPARATION_UNKNOWN");
        configuration.lockTypeAndPool((String) initial.get("type_code"));
        var head = configuration.lockRefundPreparation(preparationNo);
        require(head != null, "POINTS_REFUND_PREPARATION_UNKNOWN");
        var snapshot = json.readValue((String) head.get("payload_json"), RefundSnapshot.class);
        var preparation = snapshot.preparation();
        require(inputHash.equals(preparation.inputHash()), "POINTS_REFUND_INPUT_CONFLICT");
        require(revision == preparation.revision(), "POINTS_REFUND_REVISION_CONFLICT");
        var command = snapshot.command();
        var decision = new Decision(preparationNo, inputHash, revision, command.afterSaleNo(),
                command.subOrderNo(), command.userNo(), command.currency(), command.payment().channel(),
                preparation.netCashMinor(), preparation.cashOffsetMinor(), preparation.collectionTicket(), command.payment());
        String state = (String) head.get("state");
        if ("CASH_AUTHORIZED".equals(state) || "CONFIRMED".equals(state)) {
            return decision;
        }
        boolean collected = "COLLECTED".equals(state) && preparation.collectionTicket() != null
                && snapshot.collectionCommand() != null && snapshot.collectionResult() != null
                && snapshot.collectionResult().status() == FundsStatus.SUCCEEDED
                && preparation.collectionTicket().equals(snapshot.collectionCommand().ticketNo())
                && preparation.collectionTicket().equals(snapshot.collectionResult().ticketNo())
                && preparation.cashOffsetMinor() == snapshot.collectionResult().amountMinor()
                && snapshot.collectionResult().evidenceNo() != null;
        boolean noCollection = "PREPARED".equals(state) && preparation.collectionTicket() == null
                && preparation.cashOffsetMinor() == 0;
        require(collected || noCollection, "POINTS_REFUND_CASH_DECISION_NOT_READY");
        require(preparation.netCashMinor() > 0, "POINTS_REFUND_ZERO_CASH_PROOF_REQUIRED");
        var type = read.type(command.typeCode()).orElseThrow();
        posting.post(new Voucher("REFUND_CASH_AUTHORIZE:" + preparationNo, type.typeCode(), type.currency(),
                type.rate(), List.of(), List.of(), List.of(new PositionMove(PositionMove.Kind.REFUND,
                preparationNo, ((Number) head.get("version")).longValue(), state, "CASH_AUTHORIZED",
                ((Number) head.get("original_points")).longValue(), 0, (String) head.get("payload_json"))),
                0, List.of(), json.writeValueAsString(decision)));
        return decision;
    }

    private static void require(boolean condition, String reason) {
        if (!condition) {
            throw new IllegalArgumentException(reason);
        }
    }
}
