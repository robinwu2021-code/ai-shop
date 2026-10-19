package ai.neargo.shop.pay.points.lifecycle.impl;

import ai.neargo.shop.pay.points.lifecycle.ExpiryService;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.EvidenceRef;
import ai.neargo.shop.pay.points.model.FundBucket;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.policy.ExpiryPolicy;
import ai.neargo.shop.pay.points.port.PointsFundsGateway;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsCommand;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsPurpose;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.store.LifecycleStore;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Unknown income movement retains the original expiry reservation. */
@Service
@Transactional("payTxManager")
public class ExpiryServiceImpl implements ExpiryService {
    private final PointsReadStore read;
    private final PointsConfigurationStore config;
    private final LifecycleStore store;
    private final PointsPostingService posting;
    private final ObjectProvider<PointsFundsGateway> gateways;
    private final ObjectMapper json = new ObjectMapper();

    public ExpiryServiceImpl(
            PointsReadStore read,
            PointsConfigurationStore config,
            LifecycleStore store,
            PointsPostingService posting,
            ObjectProvider<PointsFundsGateway> gateways) {
        this.read = read;
        this.config = config;
        this.store = store;
        this.posting = posting;
        this.gateways = gateways;
    }

    @Override
    public FundsCommand prepare(Request request) {
        require(
                request != null
                        && nonblank(request.typeCode())
                        && nonblank(request.userNo())
                        && request.expiryRevision() >= 0
                        && request.asOf() >= 0
                        && request.incomeAccount() != null,
                "POINTS_EXPIRY_INPUT_INVALID");
        var route = request.incomeAccount();
        require(
                "PLATFORM".equals(route.storeNo())
                        && "PLATFORM".equals(route.merchantNo())
                        && nonblank(route.channel())
                        && nonblank(route.accountVersion())
                        && route.path() != null,
                "POINTS_EXPIRY_INCOME_ACCOUNT_REQUIRED");
        config.lockTypeAndPool(request.typeCode());
        String no =
                PointsKeys.position(
                        "E", request.typeCode(), request.userNo() + ":" + request.expiryRevision());
        var existing = store.lockTransfer(no);
        if (existing != null) {
            var prior = snapshot(existing);
            require(
                    prior.userNo().equals(request.userNo())
                            && prior.fundsCommand().context().equals(route),
                    "POINTS_EXPIRY_INPUT_CONFLICT");
            return prior.fundsCommand();
        }
        var account = store.lockUser(request.typeCode(), request.userNo());
        require(account != null, "POINTS_EXPIRY_ACCOUNT_UNKNOWN");
        require(
                num(account, "expiry_revision") == request.expiryRevision(),
                "POINTS_EXPIRY_REVISION_CONFLICT");
        require(read.enabled(request.typeCode(), request.userNo(), null), "POINTS_DISABLED");
        require(num(account, "frozen") == 0, "POINTS_FROZEN");
        require(account.get("expire_at") != null, "POINTS_EXPIRY_NOT_DUE");
        long active = account.get("last_active_at") == null ? 0 : num(account, "last_active_at");
        long due =
                ExpiryPolicy.extend(
                        num(account, "expire_at"),
                        active,
                        request.asOf(),
                        store.pauses(request.typeCode(), request.userNo(), active, request.asOf()));
        require(due <= request.asOf(), "POINTS_EXPIRY_NOT_DUE");
        long points = num(account, "available_points");
        require(points > 0, "POINTS_EXPIRY_EMPTY");
        var type = read.type(request.typeCode()).orElseThrow();
        var gateway = gateways.getIfAvailable();
        require(
                gateway != null && gateway.capabilities(route).expiryIncome(),
                "POINTS_EXPIRY_CAPABILITY_UNSUPPORTED");
        var command =
                new FundsCommand(
                        no,
                        "PTS-" + no,
                        FundsPurpose.EXPIRY_INCOME,
                        route,
                        type.currency(),
                        type.rate().toMinor(points));
        var saved =
                new Snapshot(
                        type.typeCode(),
                        request.userNo(),
                        request.expiryRevision(),
                        due,
                        points,
                        command,
                        null);
        post(
                null,
                saved,
                "READY",
                List.of(user(saved, Bucket.AVAILABLE, -points), user(saved, Bucket.HELD, points)),
                List.of(),
                List.of());
        return command;
    }

    @Override
    public Dispatch claim(String no) {
        var head = locked(no);
        var saved = snapshot(head);
        if ("SUCCEEDED".equals(head.get("state"))) {
            return new Dispatch(saved.fundsCommand(), false, saved.result());
        }
        require(
                Set.of("READY", "DISPATCHING", "UNKNOWN", "FAILED").contains(head.get("state")),
                "POINTS_EXPIRY_STATE_CONFLICT");
        boolean submit = "READY".equals(head.get("state"));
        if (submit) {
            post(head, saved, "DISPATCHING", List.of(), List.of(), List.of());
        }
        return new Dispatch(saved.fundsCommand(), submit, null);
    }

    @Override
    public FundsResult accept(String no, FundsResult result) {
        var head = locked(no);
        var saved = snapshot(head);
        require(
                result != null
                        && result.status() != null
                        && no.equals(result.ticketNo())
                        && saved.fundsCommand().amountMinor() == result.amountMinor(),
                "POINTS_EXPIRY_RECEIPT_MISMATCH");
        if ("SUCCEEDED".equals(head.get("state"))) {
            require(
                    result.status() == FundsStatus.UNKNOWN
                            || (result.status() == FundsStatus.SUCCEEDED
                                    && saved.result().evidenceNo().equals(result.evidenceNo())),
                    "POINTS_EXPIRY_RECEIPT_CONFLICT");
            return saved.result();
        }
        require(
                Set.of("DISPATCHING", "UNKNOWN", "FAILED").contains(head.get("state")),
                "POINTS_EXPIRY_NOT_DISPATCHED");
        List<PointsMove> points = List.of();
        List<FundMove> funds = List.of();
        List<EvidenceRef> evidence = List.of();
        if (result.status() == FundsStatus.SUCCEEDED) {
            require(nonblank(result.evidenceNo()), "POINTS_EXPIRY_RECEIPT_MISMATCH");
            var receipt =
                    new EvidenceRef(
                            result.evidenceNo(),
                            saved.fundsCommand().currency(),
                            -result.amountMinor(),
                            "EXPIRY_INCOME",
                            no);
            posting.recordEvidence(receipt);
            evidence = List.of(receipt);
            points =
                    List.of(
                            user(saved, Bucket.HELD, -saved.points()),
                            new PointsMove(
                                    new AccountKey(
                                            saved.typeCode(),
                                            HolderType.RECEIVE,
                                            "PLATFORM",
                                            Bucket.RECIRCULATABLE),
                                    saved.points()));
            funds = List.of(new FundMove(FundBucket.C, -result.amountMinor()));
        }
        var next =
                new Snapshot(
                        saved.typeCode(),
                        saved.userNo(),
                        saved.expiryRevision(),
                        saved.expireAt(),
                        saved.points(),
                        saved.fundsCommand(),
                        result);
        post(head, next, result.status().name(), points, funds, evidence);
        return result;
    }

    private Map<String, Object> locked(String no) {
        var initial = read.position(PositionMove.Kind.TRANSFER.table(), no);
        require(initial != null, "POINTS_EXPIRY_UNKNOWN");
        config.lockTypeAndPool((String) initial.get("type_code"));
        var head = store.lockTransfer(no);
        require(
                json.readTree((String) head.get("payload_json")).hasNonNull("expiryRevision"),
                "POINTS_EXPIRY_UNKNOWN");
        return head;
    }

    private Snapshot snapshot(Map<String, Object> head) {
        return json.readValue((String) head.get("payload_json"), Snapshot.class);
    }

    private PointsMove user(Snapshot saved, Bucket bucket, long points) {
        return new PointsMove(
                new AccountKey(saved.typeCode(), HolderType.USER, saved.userNo(), bucket), points);
    }

    private void post(
            Map<String, Object> head,
            Snapshot saved,
            String state,
            List<PointsMove> points,
            List<FundMove> funds,
            List<EvidenceRef> evidence) {
        var type = read.type(saved.typeCode()).orElseThrow();
        String no = saved.fundsCommand().ticketNo();
        long version = head == null ? -1 : num(head, "version");
        posting.post(
                new Voucher(
                        "EXPIRE:" + no + ":" + version,
                        type.typeCode(),
                        type.currency(),
                        type.rate(),
                        points,
                        funds,
                        List.of(
                                new PositionMove(
                                        PositionMove.Kind.TRANSFER,
                                        no,
                                        version,
                                        head == null ? "NEW" : (String) head.get("state"),
                                        state,
                                        saved.points(),
                                        0,
                                        json.writeValueAsString(saved))),
                        0,
                        evidence,
                        json.writeValueAsString(saved)));
    }

    private long num(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private boolean nonblank(String value) {
        return value != null && !value.isBlank();
    }

    private void require(boolean condition, String reason) {
        if (!condition) {
            throw new IllegalArgumentException(reason);
        }
    }
}
