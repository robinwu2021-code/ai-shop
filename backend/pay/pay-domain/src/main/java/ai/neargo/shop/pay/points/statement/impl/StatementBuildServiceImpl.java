package ai.neargo.shop.pay.points.statement.impl;

import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsContext;
import ai.neargo.shop.pay.points.redeem.impl.RedeemServiceImpl.RedeemSnapshot;
import ai.neargo.shop.pay.points.statement.StatementBuildService;
import ai.neargo.shop.pay.points.statement.StoreStatementService.Period;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.StatementBuildStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Construction owns metadata only; it neither freezes refunds nor authorizes a payout. */
@Service
@Transactional("payTxManager")
public class StatementBuildServiceImpl implements StatementBuildService {
    private static final int PAGE_SIZE=200;
    private final StatementBuildStore store;
    private final PointsConfigurationStore configuration;
    private final ai.neargo.shop.pay.points.store.StatementStore statements;
    private final ai.neargo.shop.pay.points.store.PointsReadStore read;
    private final ai.neargo.shop.pay.points.posting.PointsPostingService posting;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<StatementBuildService> self;
    private final ObjectMapper json=new ObjectMapper();
    public StatementBuildServiceImpl(StatementBuildStore store,PointsConfigurationStore configuration,
            ai.neargo.shop.pay.points.store.StatementStore statements,
            ai.neargo.shop.pay.points.store.PointsReadStore read,
            ai.neargo.shop.pay.points.posting.PointsPostingService posting) {
        this.store=store; this.configuration=configuration; this.read=read; this.posting=posting; this.statements=statements;
    }
    @Override public Build start(Period p,String actor) {
        require(p!=null && p.typeCode()!=null && !p.typeCode().isBlank() && p.storeNo()!=null
                && !p.storeNo().isBlank() && p.fromMillis()>=0 && p.toMillis()>p.fromMillis()
                && p.routeKey()==null && actor!=null && !actor.isBlank(),"POINTS_STATEMENT_INPUT_INVALID");
        configuration.lockTypeAndPool(p.typeCode());
        String no=PointsKeys.position("SB",p.typeCode(),p.storeNo()+":"+p.fromMillis());
        Build old=store.lock(no);
        if (old!=null) {
            require(old.toMillis()==p.toMillis(),"POINTS_STATEMENT_PERIOD_CONFLICT");
            return old;
        }
        require(statements.forPeriod(p.typeCode(),p.storeNo(),p.fromMillis()).isEmpty(),
                "POINTS_STATEMENT_PERIOD_ALREADY_FROZEN");
        Build next=new Build(no,p.typeCode(),p.storeNo(),p.fromMillis(),p.toMillis(),
                store.highWater(p.typeCode()),0,0,"BUILDING",0);
        store.create(next,actor);
        return next;
    }
    @Override @Transactional(value="payTxManager",propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Build advance(String no) {
        require(no!=null && !no.isBlank(),"POINTS_STATEMENT_INPUT_INVALID");
        Build initial=store.find(no);
        require(initial!=null,"POINTS_STATEMENT_BUILD_UNKNOWN");
        return self.getObject().advance(initial.typeCode(),no);
    }
    @Override public Build advance(String type,String no) {
        require(type!=null && !type.isBlank() && no!=null && !no.isBlank(),"POINTS_STATEMENT_INPUT_INVALID");
        configuration.lockTypeAndPool(type);
        Build before=store.lock(no);
        require(before!=null && type.equals(before.typeCode()),"POINTS_STATEMENT_BUILD_UNKNOWN");
        if ("READY".equals(before.state()) || "PUBLISHED".equals(before.state())) return before;
        require("BUILDING".equals(before.state()),"POINTS_STATEMENT_BUILD_NOT_ACTIVE");
        var rows=store.page(before,PAGE_SIZE);
        var routes=new java.util.HashSet<>(store.routes(no));
        long cursor=before.cursor(),count=before.sourceCount();
        for (var row:rows) {
            cursor=((Number)row.get("ready_seq")).longValue();
            var source=json.readValue((String)row.get("payload_json"),RedeemSnapshot.class);
            require(source!=null && source.agreement()!=null,"POINTS_STATEMENT_SOURCE_NOT_READY");
            // A PER_ORDER refund can temporarily share REFUND_PREPARED. It is not a
            // periodic candidate; still advance over its sequence so it cannot poison a page.
            if (!"PERIODIC".equals(source.agreement().settleMode())) continue;
            require(source.finalSnapshotReceived() && source.fundsContext()!=null,
                    "POINTS_STATEMENT_SOURCE_NOT_READY");
            var c=source.fundsContext();
            var recipient=new FundsContext(c.path(),c.channel(),c.merchantNo(),c.storeNo(),
                    c.accountVersion(),c.subMerchantId(),null,null,0);
            String route=PointsKeys.position("ROUTE",before.typeCode(),json.writeValueAsString(recipient));
            long remaining=Math.subtractExact(((Number)row.get("original_points")).longValue(),
                    ((Number)row.get("processed_points")).longValue());
            require(remaining>=0,"POINTS_STATEMENT_AMOUNT_CONFLICT");
            routes.add(route);
            require(routes.size()<=200,"POINTS_STATEMENT_ROUTE_GROUP_LIMIT");
            String statement=PointsKeys.position("S",before.typeCode(),
                    before.storeNo()+":"+before.fromMillis()+":"+route);
            if (statements.lock(statement)==null) {
                var period=new Period(before.typeCode(),before.storeNo(),before.fromMillis(),before.toMillis(),route);
                var snapshot=new ai.neargo.shop.pay.points.statement.StoreStatementService.Snapshot(statement,period,
                        java.util.List.of(),java.util.List.of(new ai.neargo.shop.pay.points.statement.StoreStatementService.Revision(
                                1,java.util.List.of(),0,"BUILD:"+before.buildNo(),null)),"BUILDING",null,before.buildNo());
                var pointType=read.type(before.typeCode()).orElseThrow();
                posting.post(new ai.neargo.shop.pay.points.model.Voucher("STATEMENT_BUILD_HEAD:"+statement,
                        pointType.typeCode(),pointType.currency(),pointType.rate(),java.util.List.of(),java.util.List.of(),
                        java.util.List.of(new ai.neargo.shop.pay.points.model.PositionMove(
                                ai.neargo.shop.pay.points.model.PositionMove.Kind.STATEMENT,statement,-1,"NEW","BUILDING",0,0,
                                json.writeValueAsString(snapshot))),0,java.util.List.of(),"{}"));
            }
            store.append(before,row,route,remaining);
            store.bind((String)row.get("position_no"),before.typeCode(),before.storeNo(),statement);
            count=Math.addExact(count,1);
        }
        boolean done=rows.size()<PAGE_SIZE;
        Build after=new Build(before.buildNo(),before.typeCode(),before.storeNo(),before.fromMillis(),
                before.toMillis(),before.highWater(),done?before.highWater():cursor,count,
                done?"READY":"BUILDING",Math.addExact(before.version(),1));
        store.advance(before,after);
        return after;
    }
    @Override @Transactional(value="payTxManager",readOnly=true)
    public LinePage lines(String no,long revision,long after,int limit) {
        require(no!=null && !no.isBlank() && revision>0 && after>=0 && limit>0 && limit<=200,
                "POINTS_STATEMENT_INPUT_INVALID");
        var head=read.position(ai.neargo.shop.pay.points.model.PositionMove.Kind.STATEMENT.table(),no);
        require(head!=null,"POINTS_STATEMENT_UNKNOWN");
        var snapshot=json.readValue((String)head.get("payload_json"),
                ai.neargo.shop.pay.points.statement.StoreStatementService.Snapshot.class);
        require(snapshot.sourceBuildNo()!=null && !"BUILDING".equals(snapshot.state()),"POINTS_STATEMENT_NOT_DRAFT");
        var selected=snapshot.revisions().stream().filter(r -> r.number()==revision).findFirst().orElseThrow(
                () -> new IllegalArgumentException("POINTS_STATEMENT_REVISION_CONFLICT"));
        var rows=store.lines(no,snapshot.sourceBuildNo(),snapshot.period().routeKey(),selected.sourceVersionSum(),after,limit+1);
        var items=rows.stream().limit(limit).map(r -> new SourceLine(((Number)r.get("ready_seq")).longValue(),
                (String)r.get("position_no"),((Number)r.get("source_version")).longValue(),
                ((Number)r.get("points")).longValue())).toList();
        return new LinePage(items,items.isEmpty()?after:items.getLast().readySeq(),rows.size()>limit);
    }
    private void require(boolean value,String reason) { if (!value) throw new IllegalArgumentException(reason); }
}
