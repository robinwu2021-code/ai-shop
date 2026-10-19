package ai.neargo.shop.pay.points.statement.impl;

import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.FundBucket;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.redeem.impl.RedeemServiceImpl.RedeemSnapshot;
import ai.neargo.shop.pay.points.statement.StoreStatementService;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.pay.points.store.StatementStore;
import ai.neargo.shop.spi.points.PointsFactPort.LifecycleFact;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Statement metadata and financial confirmation share the pay transaction. */
@Service
@Transactional("payTxManager")
public class StoreStatementServiceImpl implements StoreStatementService {
    @Override
    public void reopenForRefund(String positionNo) {
        var initial = read.position(PositionMove.Kind.REDEEM.table(), positionNo);
        require(initial != null, "POINTS_STATEMENT_SOURCE_NOT_READY");
        config.lockTypeAndPool((String) initial.get("type_code"));
        var source = store.lockSource(positionNo);
        String no = (String) source.get("statement_no");
        if (no == null) {
            return;
        }
        var head = store.lock(no);
        var saved = snapshot(head);
        require(saved.sourceBuildNo()!=null || saved.positionIds().contains(positionNo), "POINTS_STATEMENT_BINDING_CONFLICT");
        if (Set.of("BUILDING", "DRAFT", "DISPUTED", "PAID").contains(saved.state())) {
            return;
        }
        require("CONFIRMED".equals(saved.state()), "POINTS_STATEMENT_PAYMENT_UNRESOLVED");
        if (saved.sourceBuildNo()!=null) {
            var history=new ArrayList<>(saved.revisions());
            var actual=normalizedRevision(saved,Math.addExact(saved.current().number(),1),"REFUND:"+positionNo);
            require(actual.points()==saved.current().points(),"POINTS_STATEMENT_AMOUNT_CONFLICT");
            history.add(actual);
            var next=new Snapshot(no,saved.period(),List.of(),history,"DRAFT",null,saved.sourceBuildNo());
            String type=saved.period().typeCode();
            long points=saved.current().points(),minor=read.type(type).orElseThrow().rate().toMinor(points);
            post(head,next,List.of(new PointsMove(new AccountKey(type,HolderType.STORE,saved.period().storeNo(),Bucket.AVAILABLE),points),
                    new PointsMove(new AccountKey(type,HolderType.RECEIVE,"PLATFORM",Bucket.REFUND_RESERVED),-points)),
                    List.of(new FundMove(FundBucket.D,-minor),new FundMove(FundBucket.C,minor)),List.of(),"REFUND:"+positionNo);
            return;
        }
        List<PositionMove> positions = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        long points = 0;
        for (String id : saved.positionIds()) {
            var row = store.lockSource(id);
            require("PAYABLE".equals(row.get("state")), "POINTS_STATEMENT_PAYMENT_UNRESOLVED");
            long remaining =
                    Math.subtractExact(num(row, "original_points"), num(row, "processed_points"));
            points = Math.addExact(points, remaining);
            lines.add(new Line(id, Math.addExact(num(row, "version"), 1), remaining));
            positions.add(
                    new PositionMove(
                            PositionMove.Kind.REDEEM,
                            id,
                            num(row, "version"),
                            "PAYABLE",
                            "SETTLEABLE",
                            num(row, "original_points"),
                            0,
                            (String) row.get("payload_json")));
        }
        require(points == saved.current().points(), "POINTS_STATEMENT_AMOUNT_CONFLICT");
        String actor = "REFUND:" + positionNo;
        var history = new ArrayList<>(saved.revisions());
        history.add(
                new Revision(
                        Math.addExact(saved.current().number(), 1),
                        lines,
                        points,
                        actor,
                        "REFUND_REOPEN"));
        var next =
                new Snapshot(
                        no,
                        saved.period(),
                        saved.positionIds(),
                        history,
                        history.getLast().points() == 0 ? "CLEARED" : "DRAFT",
                        null,saved.sourceBuildNo());
        String type = saved.period().typeCode();
        long minor = read.type(type).orElseThrow().rate().toMinor(points);
        post(
                head,
                next,
                List.of(
                        new PointsMove(
                                new AccountKey(
                                        type,
                                        HolderType.STORE,
                                        saved.period().storeNo(),
                                        Bucket.AVAILABLE),
                                points),
                        new PointsMove(
                                new AccountKey(
                                        type,
                                        HolderType.RECEIVE,
                                        "PLATFORM",
                                        Bucket.REFUND_RESERVED),
                                -points)),
                List.of(new FundMove(FundBucket.D, -minor), new FundMove(FundBucket.C, minor)),
                positions,
                actor);
    }

    private final PointsReadStore read;
    private final PointsConfigurationStore config;
    private final StatementStore store;
    private final PointsPostingService posting;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<ai.neargo.shop.pay.points.statement.StatementConfirmationPublisher> confirmationPublisher;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<ai.neargo.shop.pay.points.store.StatementBuildStore> buildStores;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<ai.neargo.shop.pay.points.statement.StatementBuildService> builders;
    private final ObjectMapper json = new ObjectMapper();

    public StoreStatementServiceImpl(
            PointsReadStore read,
            PointsConfigurationStore config,
            StatementStore store,
            PointsPostingService posting) {
        this.read = read;
        this.config = config;
        this.store = store;
        this.posting = posting;
    }

    @Override
    public Snapshot draft(Period period, String actor) {
        var groups = draftGroups(period, actor);
        require(groups.size() == 1, "POINTS_STATEMENT_ROUTE_GROUPS_REQUIRED");
        return groups.getFirst();
    }

    @Override
    public List<Snapshot> draftGroups(Period period, String actor) {
        require(
                period != null
                        && nonblank(period.typeCode())
                        && nonblank(period.storeNo())
                        && period.fromMillis() >= 0
                        && period.fromMillis() < period.toMillis()
                        && nonblank(actor),
                "POINTS_STATEMENT_INPUT_INVALID");
        config.lockTypeAndPool(period.typeCode());
        var existing = store.forPeriod(period.typeCode(), period.storeNo(), period.fromMillis());
        require(existing.size() <= 200, "POINTS_STATEMENT_ROUTE_GROUP_LIMIT");
        if (existing.isEmpty() && buildStores!=null && buildStores.getIfAvailable()!=null) {
            String buildNo=PointsKeys.position("SB",period.typeCode(),period.storeNo()+":"+period.fromMillis());
            var priorBuild=buildStores.getObject().find(buildNo);
            if (priorBuild!=null) {
                require(priorBuild.toMillis()==period.toMillis(),"POINTS_STATEMENT_PERIOD_CONFLICT");
                if ("PUBLISHED".equals(priorBuild.state())) return List.of();
                var progress=builders.getObject().advance(period.typeCode(),buildNo);
                var results=store.forPeriod(period.typeCode(),period.storeNo(),period.fromMillis()).stream()
                        .map(this::snapshot).toList();
                if ("READY".equals(progress.state()) && results.isEmpty()) buildStores.getObject().published(buildNo);
                return results;
            }
        }
        if (!existing.isEmpty()) {
            var saved = existing.stream().map(this::snapshot).toList();
            require(
                    saved.stream().allMatch(s -> s.period().toMillis() == period.toMillis()),
                    "POINTS_STATEMENT_PERIOD_CONFLICT");
            var building=saved.stream().filter(x -> "BUILDING".equals(x.state())).findFirst();
            if (building.isPresent()) {
                var build=builders.getObject().advance(period.typeCode(),building.get().sourceBuildNo());
                if ("READY".equals(build.state())) {
                    for (var row:existing) {
                        var old=snapshot(row);
                        if ("BUILDING".equals(old.state())) {
                            var actual=normalizedRevision(old,1,actor);
                            var next=new Snapshot(old.statementNo(),old.period(),List.of(),List.of(actual),
                                    actual.points()==0?"CLEARED":"DRAFT",null,old.sourceBuildNo());
                            post(row,next,List.of(),List.of(),List.of(),actor);
                        }
                    }
                }
                var results=store.forPeriod(period.typeCode(),period.storeNo(),period.fromMillis()).stream()
                        .map(this::snapshot).toList();
                if ("READY".equals(build.state()) && results.stream().noneMatch(x -> "BUILDING".equals(x.state()))) {
                    buildStores.getObject().published(build.buildNo());
                }
                return results;
            }
            var selected =
                    saved.stream()
                            .filter(
                                    s ->
                                            period.routeKey() == null
                                                    || period.routeKey()
                                                            .equals(s.period().routeKey()))
                            .toList();
            require(!selected.isEmpty(), "POINTS_STATEMENT_ROUTE_UNKNOWN");
            return selected;
        }
        require(period.routeKey() == null, "POINTS_STATEMENT_ROUTE_UNKNOWN");
        var candidates = store.candidates(period.typeCode(), period.storeNo(), period.toMillis());
        if (candidates.size()>200) {
            var build=builders.getObject().start(period,actor);
            builders.getObject().advance(period.typeCode(),build.buildNo());
            return store.forPeriod(period.typeCode(),period.storeNo(),period.fromMillis()).stream()
                    .map(this::snapshot).toList();
        }
        var groups = new java.util.TreeMap<String, List<Map<String, Object>>>();
        for (var source : candidates) {
            if (belongsToPeriod(source, period)) {
                var snapshot =
                        json.readValue((String) source.get("payload_json"), RedeemSnapshot.class);
                var c = snapshot.fundsContext();
                var recipient =
                        new ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsContext(
                                c.path(),
                                c.channel(),
                                c.merchantNo(),
                                c.storeNo(),
                                c.accountVersion(),
                                c.subMerchantId(),
                                null,
                                null,
                                0);
                String key =
                        PointsKeys.position(
                                "ROUTE", period.typeCode(), json.writeValueAsString(recipient));
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(source);
            }
        }
        require(!groups.isEmpty(), "POINTS_STATEMENT_EMPTY");
        require(groups.size() <= 200, "POINTS_STATEMENT_ROUTE_GROUP_LIMIT");
        List<Snapshot> result = new ArrayList<>();
        for (var group : groups.entrySet()) {
            var scoped =
                    new Period(
                            period.typeCode(),
                            period.storeNo(),
                            period.fromMillis(),
                            period.toMillis(),
                            group.getKey());
            String no =
                    PointsKeys.position(
                            "S",
                            period.typeCode(),
                            period.storeNo() + ":" + period.fromMillis() + ":" + group.getKey());
            var ids =
                    group.getValue().stream().map(row -> (String) row.get("position_no")).toList();
            var revision = revision(scoped, ids, 1, actor);
            var saved = new Snapshot(no, scoped, ids, List.of(revision), "DRAFT", null);
            post(null, saved, List.of(), List.of(), List.of(), actor);
            result.add(saved);
        }
        return List.copyOf(result);
    }

    @Override
    public Snapshot redraft(String no, long revision, String actor) {
        var head = locked(no, actor);
        var saved = checked(head, revision);
        require(Set.of("DRAFT", "DISPUTED").contains(saved.state()), "POINTS_STATEMENT_NOT_DRAFT");
        var history = new ArrayList<>(saved.revisions());
        history.add(
                saved.sourceBuildNo()==null ? revision(saved.period(), saved.positionIds(), Math.addExact(revision, 1), actor)
                        : normalizedRevision(saved,Math.addExact(revision,1),actor));
        var next =
                new Snapshot(
                        no,
                        saved.period(),
                        saved.positionIds(),
                        history,
                        history.getLast().points() == 0 ? "CLEARED" : "DRAFT",
                        null,saved.sourceBuildNo());
        post(head, next, List.of(), List.of(), List.of(), actor);
        return next;
    }

    @Override
    public Snapshot confirm(String no, long revision, String actor) {
        var head = locked(no, actor);
        var saved = checked(head, revision);
        if (Set.of("CONFIRMED", "PAYING", "PAID", "CLEARED").contains(saved.state())) {
            return saved;
        }
        require("DRAFT".equals(saved.state()), "POINTS_STATEMENT_NOT_DRAFT");
        require(saved.current().points() > 0, "POINTS_STATEMENT_EMPTY");
        var actual = saved.sourceBuildNo()==null ? revision(saved.period(), saved.positionIds(), revision, actor)
                : normalizedRevision(saved,revision,actor);
        require(
                actual.lines().equals(saved.current().lines()) && actual.points()==saved.current().points()
                        && actual.sourceVersionSum().equals(saved.current().sourceVersionSum()),
                "POINTS_STATEMENT_REDRAFT_REQUIRED");
        List<PositionMove> positions = new ArrayList<>();
        for (var line : actual.lines()) {
            var source = store.lockSource(line.positionNo());
            positions.add(
                    new PositionMove(
                            PositionMove.Kind.REDEEM,
                            line.positionNo(),
                            num(source, "version"),
                            "SETTLEABLE",
                            "PAYABLE",
                            num(source, "original_points"),
                            0,
                            (String) source.get("payload_json")));
        }
        String type = saved.period().typeCode();
        long points = actual.points();
        long minor = read.type(type).orElseThrow().rate().toMinor(points);
        var next =
                new Snapshot(
                        no,
                        saved.period(),
                        saved.positionIds(),
                        saved.revisions(),
                        "CONFIRMED",
                        null,saved.sourceBuildNo());
        post(
                head,
                next,
                List.of(
                        new PointsMove(
                                new AccountKey(
                                        type,
                                        HolderType.STORE,
                                        saved.period().storeNo(),
                                        Bucket.AVAILABLE),
                                -points),
                        new PointsMove(
                                new AccountKey(
                                        type,
                                        HolderType.RECEIVE,
                                        "PLATFORM",
                                        Bucket.REFUND_RESERVED),
                                points)),
                List.of(new FundMove(FundBucket.C, -minor), new FundMove(FundBucket.D, minor)),
                positions,
                actor);
        return next;
    }

    @Override
    public Snapshot dispute(String no, long revision, String actor, String reason) {
        require(nonblank(reason), "POINTS_STATEMENT_DISPUTE_REASON_REQUIRED");
        var head = locked(no, actor);
        var saved = checked(head, revision);
        if ("DISPUTED".equals(saved.state())) {
            require(reason.equals(saved.disputeReason()), "POINTS_STATEMENT_DISPUTE_CONFLICT");
            return saved;
        }
        require("DRAFT".equals(saved.state()), "POINTS_STATEMENT_NOT_DRAFT");
        var next =
                new Snapshot(
                        no,
                        saved.period(),
                        saved.positionIds(),
                        saved.revisions(),
                        "DISPUTED",
                        reason,saved.sourceBuildNo());
        post(head, next, List.of(), List.of(), List.of(), actor);
        return next;
    }

    private Revision normalizedRevision(Snapshot saved,long number,String actor) {
        var totals=buildStores.getObject().totals(saved.statementNo());
        require(num(totals,"busy")==0,"POINTS_STATEMENT_SOURCE_NOT_READY");
        require(num(totals,"source_count")>0,"POINTS_STATEMENT_EMPTY");
        return new Revision(number,List.of(),num(totals,"points"),actor,null,num(totals,"version_sum"));
    }

    private Revision revision(Period period, List<String> ids, long number, String actor) {
        List<Line> lines = new ArrayList<>();
        long points = 0;
        boolean carryForward = false;
        for (String id : ids) {
            var source = store.lockSource(id);
            require(
                    source != null
                            && period.typeCode().equals(source.get("type_code"))
                            && period.storeNo().equals(source.get("store_no"))
                            && "SETTLEABLE".equals(source.get("state")),
                    "POINTS_STATEMENT_SOURCE_NOT_READY");
            var redemption =
                    json.readValue((String) source.get("payload_json"), RedeemSnapshot.class);
            require(
                    "PERIODIC".equals(redemption.agreement().settleMode())
                            && redemption.finalSnapshotReceived(),
                    "POINTS_STATEMENT_SOURCE_NOT_READY");
            long remaining =
                    Math.subtractExact(
                            num(source, "original_points"), num(source, "processed_points"));
            lines.add(new Line(id, num(source, "version"), remaining));
            carryForward |= finalAt(source, period) < period.fromMillis();
            points = Math.addExact(points, remaining);
        }
        return new Revision(number, lines, points, actor, carryForward ? "CARRY_FORWARD" : null);
    }

    private boolean belongsToPeriod(Map<String, Object> source, Period period) {
        // A previously unbound late fact must not be orphaned by an already frozen older statement.
        return finalAt(source, period) < period.toMillis();
    }

    private long finalAt(Map<String, Object> source, Period period) {
        var lifecycle = read.lifecycle(period.typeCode(), (String) source.get("sub_order_no"));
        require(lifecycle != null, "POINTS_STATEMENT_FINAL_FACT_REQUIRED");
        var node = json.readTree((String) lifecycle.get("payload_json")).get("finalFact");
        require(node != null && node.isObject(), "POINTS_STATEMENT_FINAL_FACT_REQUIRED");
        var fact = json.treeToValue(node, LifecycleFact.class);
        require(
                fact.occurredAt() != null
                        && period.typeCode().equals(fact.typeCode())
                        && source.get("sub_order_no").equals(fact.subOrderNo()),
                "POINTS_STATEMENT_FINAL_FACT_REQUIRED");
        return fact.occurredAt().toEpochMilli();
    }

    private Map<String, Object> locked(String no, String actor) {
        require(nonblank(no) && nonblank(actor), "POINTS_STATEMENT_INPUT_INVALID");
        var initial = read.position(PositionMove.Kind.STATEMENT.table(), no);
        require(initial != null, "POINTS_STATEMENT_UNKNOWN");
        config.lockTypeAndPool((String) initial.get("type_code"));
        return store.lock(no);
    }

    private Snapshot checked(Map<String, Object> head, long revision) {
        var saved = snapshot(head);
        require(saved.current().number() == revision, "POINTS_STATEMENT_REVISION_CONFLICT");
        return saved;
    }

    private Snapshot snapshot(Map<String, Object> head) {
        return json.readValue((String) head.get("payload_json"), Snapshot.class);
    }

    private void post(
            Map<String, Object> head,
            Snapshot saved,
            List<PointsMove> points,
            List<FundMove> funds,
            List<PositionMove> sources,
            String actor) {
        var type = read.type(saved.period().typeCode()).orElseThrow();
        long version = head == null ? -1 : num(head, "version");
        var positions = new ArrayList<>(sources);
        positions.add(
                new PositionMove(
                        PositionMove.Kind.STATEMENT,
                        saved.statementNo(),
                        version,
                        head == null ? "NEW" : (String) head.get("state"),
                        saved.state(),
                        head == null ? saved.current().points() : num(head, "original_points"),
                        0,
                        json.writeValueAsString(saved)));
        posting.post(
                new Voucher(
                        "STATEMENT:" + saved.statementNo() + ":" + version,
                        type.typeCode(),
                        type.currency(),
                        type.rate(),
                        points,
                        funds,
                        positions,
                        0,
                        List.of(),
                        json.writeValueAsString(Map.of("actor", actor, "statement", saved))));
        if (confirmationPublisher != null) {
            var publisher = confirmationPublisher.getIfAvailable();
            if (publisher != null) publisher.publish(saved);
        }
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
