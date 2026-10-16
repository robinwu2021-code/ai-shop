package ai.neargo.shop.pay.points.event.impl;

import ai.neargo.shop.pay.points.app.PointsPaidFactService;
import ai.neargo.shop.pay.points.app.PointsSettlementHook;
import ai.neargo.shop.pay.points.earn.EarnService;
import ai.neargo.shop.pay.points.event.PointsSourceFactHandler;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.ReserveMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.policy.PointsDigests;
import ai.neargo.shop.pay.points.port.PointsFactPortImpl;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.redeem.impl.RedeemServiceImpl.RedeemSnapshot;
import ai.neargo.shop.pay.points.refund.PointsRefundConfirmationService;
import ai.neargo.shop.pay.points.refund.impl.PointsRefundPreparationServiceImpl.RefundSnapshot;
import ai.neargo.shop.pay.points.store.LifecycleStore;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsFactStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.pay.points.store.StatementStore;
import ai.neargo.shop.spi.points.PointsEarnPort.GrantCommand;
import ai.neargo.shop.spi.points.PointsFactPort.LifecycleFact;
import ai.neargo.shop.spi.points.PointsFactPort.RefundConfirmationFact;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Transactional("payTxManager")
public class PointsSourceFactHandlerImpl implements PointsSourceFactHandler {
    private final PointsPaidFactService paid;
    private final PointsSettlementHook settlement;
    private final PointsConfigurationStore config;
    private final PointsReadStore read;
    private final PointsFactStore facts;
    private final LifecycleStore lifecycle;
    private final StatementStore positions;
    private final PointsPostingService posting;
    private final EarnService earning;
    private final PointsRefundConfirmationService refunds;
    private final ObjectMapper json = new ObjectMapper();

    public PointsSourceFactHandlerImpl(
            PointsPaidFactService paid,
            PointsSettlementHook settlement,
            PointsConfigurationStore config,
            PointsReadStore read,
            PointsFactStore facts,
            LifecycleStore lifecycle,
            StatementStore positions,
            PointsPostingService posting,
            EarnService earning,
            PointsRefundConfirmationService refunds) {
        this.paid = paid;
        this.settlement = settlement;
        this.config = config;
        this.read = read;
        this.facts = facts;
        this.lifecycle = lifecycle;
        this.positions = positions;
        this.posting = posting;
        this.earning = earning;
        this.refunds = refunds;
    }

    @Override
    public void apply(LifecycleFact fact) {
        require(
                fact != null && PointsFactPortImpl.digest(fact).equals(fact.payloadHash()),
                "POINTS_FACT_HASH_MISMATCH");
        config.lockTypeAndPool(fact.typeCode());
        var current = lifecycle.lockLifecycle(fact.typeCode(), fact.subOrderNo());
        String field =
                switch (fact.kind()) {
                    case PAID -> "paidFact";
                    case FINALIZED -> "finalFact";
                    case CANCELLED -> "cancelFact";
                    default -> "unsupported";
                };
        var saved =
                current == null
                        ? null
                        : json.readTree((String) current.get("payload_json")).get(field);
        if (saved != null && saved.isObject()) {
            long version = saved.path("sourceVersion").longValue();
            if (version > fact.sourceVersion()) {
                facts.record(fact, json.writeValueAsString(fact), "STALE");
                return;
            }
            if (version == fact.sourceVersion()) {
                require(saved.equals(json.valueToTree(fact)), "POINTS_FACT_VERSION_CONFLICT");
            }
        }
        switch (fact.kind()) {
            case PAID -> paid.onPaid(fact);
            case FINALIZED -> settlement.onBillFinalized(fact);
            case CANCELLED -> cancel(fact);
            default -> throw new IllegalArgumentException("POINTS_TYPED_PROOF_REQUIRED");
        }
    }

    @Override
    public void earn(GrantCommand command) {
        config.lockTypeAndPool(command.typeCode());
        String event = "EARN:" + command.eventNo(), body = json.writeValueAsString(command);
        var prior = facts.lockInbox(event);
        if (prior != null) {
            require(body.equals(prior.get("payload_json")), "POINTS_FACT_CONFLICT");
            return;
        }
        var current = lifecycle.lockLifecycle(command.typeCode(), command.subOrderNo());
        require(current != null, "POINTS_PAYMENT_FACT_REQUIRED");
        var state = json.readTree((String) current.get("payload_json"));
        require(state.hasNonNull("paidFact"), "POINTS_PAYMENT_FACT_REQUIRED");
        var paidFact = json.treeToValue(state.get("paidFact"), LifecycleFact.class);
        require(
                paidFact.userNo().equals(command.userNo())
                        && paidFact.sourceVersion() == command.sourceVersion(),
                "POINTS_EARNING_SOURCE_MISMATCH");
        require(
                read.openRefunds(command.typeCode(), command.subOrderNo()).isEmpty(),
                "POINTS_REFUND_SOURCE_BUSY");
        earning.grant(command);
        facts.recordEvent(
                command.typeCode(), event, PointsDigests.sha256(body), body, "EARN_APPLIED");
    }

    @Override
    public void confirm(RefundConfirmationFact fact) {
        require(
                fact != null
                        && fact.eventNo() != null
                        && !fact.eventNo().isBlank()
                        && fact.occurredAt() != null
                        && fact.confirmation() != null,
                "POINTS_REFUND_FACT_INVALID");
        config.lockTypeAndPool(fact.typeCode());
        String body = json.writeValueAsString(fact);
        var prior = facts.lockInbox(fact.eventNo());
        if (prior != null) {
            require(body.equals(prior.get("payload_json")), "POINTS_FACT_CONFLICT");
            return;
        }
        var preparation = config.lockRefundPreparation(fact.confirmation().preparationNo());
        require(preparation != null, "POINTS_REFUND_PREPARATION_UNKNOWN");
        var saved = json.readValue((String) preparation.get("payload_json"), RefundSnapshot.class);
        require(
                saved.command().typeCode().equals(fact.typeCode())
                        && saved.command().subOrderNo().equals(fact.subOrderNo())
                        && saved.command().userNo().equals(fact.userNo())
                        && saved.command().sourceVersion() == fact.sourceVersion(),
                "POINTS_REFUND_FACT_MISMATCH");
        refunds.confirm(fact.confirmation());
        facts.recordEvent(
                fact.typeCode(),
                fact.eventNo(),
                PointsDigests.sha256(body),
                body,
                "REFUND_APPLIED");
    }

    private void cancel(LifecycleFact fact) {
        config.lockTypeAndPool(fact.typeCode());
        String body = json.writeValueAsString(fact);
        var prior = facts.lockInbox(fact.eventNo());
        if (prior != null) {
            require(body.equals(prior.get("payload_json")), "POINTS_FACT_CONFLICT");
            return;
        }
        var head = lifecycle.lockLifecycle(fact.typeCode(), fact.subOrderNo());
        var state =
                head == null
                        ? json.createObjectNode()
                        : (ObjectNode) json.readTree((String) head.get("payload_json"));
        require(
                !state.path("paid").asBoolean(false) && !state.path("finalized").asBoolean(false),
                "POINTS_CANCEL_AFTER_PAID");
        var grant =
                read.position(
                        PositionMove.Kind.GRANT.table(),
                        PointsKeys.position("G", fact.typeCode(), fact.subOrderNo()));
        require(
                grant == null || num(grant, "original_points") == 0,
                "POINTS_CANCEL_GRANTED_CONFLICT");
        var moves = new ArrayList<PointsMove>();
        var changes = new ArrayList<PositionMove>();
        var reserves = new ArrayList<ReserveMove>();
        String no = PointsKeys.position("R", fact.typeCode(), fact.subOrderNo());
        var source = positions.lockSource(no);
        long minor = 0;
        if (source != null && !"RELEASED".equals(source.get("state"))) {
            require("HELD".equals(source.get("state")), "POINTS_CANCEL_AFTER_PAID");
            var snapshot =
                    json.readValue((String) source.get("payload_json"), RedeemSnapshot.class);
            require(fact.userNo().equals(snapshot.userNo()), "POINTS_FACT_SUBJECT_MISMATCH");
            long points = num(source, "original_points");
            minor = snapshot.share().minor();
            moves.add(
                    new PointsMove(
                            new AccountKey(
                                    fact.typeCode(), HolderType.USER, fact.userNo(), Bucket.HELD),
                            -points));
            moves.add(
                    new PointsMove(
                            new AccountKey(
                                    fact.typeCode(),
                                    HolderType.USER,
                                    fact.userNo(),
                                    Bucket.AVAILABLE),
                            points));
            changes.add(
                    new PositionMove(
                            PositionMove.Kind.REDEEM,
                            no,
                            num(source, "version"),
                            "HELD",
                            "RELEASED",
                            points,
                            points,
                            (String) source.get("payload_json")));
            var reserve = read.reserve(no);
            reserves.add(new ReserveMove(no, no, num(reserve, "version"), minor, -minor, 0, minor));
        }
        var type = read.type(fact.typeCode()).orElseThrow();
        posting.post(
                new Voucher(
                        "CANCEL:" + fact.eventNo(),
                        type.typeCode(),
                        type.currency(),
                        type.rate(),
                        moves,
                        List.of(),
                        changes,
                        -minor,
                        List.of(),
                        "{}",
                        reserves));
        state.put("cancelled", true);
        state.set("cancelFact", json.readTree(body));
        facts.saveLifecycle(
                fact, json.writeValueAsString(state), head == null ? null : num(head, "version"));
        facts.record(fact, body, "APPLIED");
    }

    private long num(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private void require(boolean condition, String reason) {
        if (!condition) {
            throw new IllegalArgumentException(reason);
        }
    }
}
