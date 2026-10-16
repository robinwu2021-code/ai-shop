package ai.neargo.shop.pay.points.payout.impl;

import ai.neargo.shop.pay.points.app.PointsSettlementHook.PayoutTicket;
import ai.neargo.shop.pay.points.app.PointsSettlementHook.PayoutCommand;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.ReceivableMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.payout.PayoutSnapshot;
import ai.neargo.shop.pay.points.payout.PayoutSnapshot.Offset;
import ai.neargo.shop.pay.points.port.PointsFundsGateway;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsContext;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsCommand;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsPurpose;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.redeem.impl.RedeemServiceImpl.RedeemSnapshot;
import ai.neargo.shop.pay.points.statement.StoreStatementService.Snapshot;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.StatementStore;
import ai.neargo.shop.pay.points.payout.StatementPayoutService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Periodic preparation shares pay's ledger transaction with its source and receivable reservations. */
@Service
@Transactional("payTxManager")
public class StatementPayoutServiceImpl implements StatementPayoutService {
    private final PointsReadStore read;
    private final PointsConfigurationStore config;
    private final StatementStore store;
    private final PointsPostingService posting;
    private final ObjectProvider<PointsFundsGateway> gateways;
    private final ObjectMapper json=new ObjectMapper();

    public StatementPayoutServiceImpl(PointsReadStore read,PointsConfigurationStore config,StatementStore store,
            PointsPostingService posting,ObjectProvider<PointsFundsGateway> gateways) {
        this.read=read; this.config=config; this.store=store; this.posting=posting; this.gateways=gateways;
    }

    @Override public PayoutTicket prepare(String biz,String no,long revision,String actor) {
        require(biz!=null && !biz.isBlank() && actor!=null && !actor.isBlank(),"POINTS_STATEMENT_INPUT_INVALID");
        var initial=read.position(PositionMove.Kind.STATEMENT.table(),no);
        require(initial!=null,"POINTS_STATEMENT_UNKNOWN");
        String typeCode=(String)initial.get("type_code");
        config.lockTypeAndPool(typeCode);
        var head=store.lock(no);
        var statement=json.readValue((String)head.get("payload_json"),Snapshot.class);
        String ticketNo=PointsKeys.position("Y",typeCode,biz);
        var existing=config.lockPayoutTicket(ticketNo);
        if (existing!=null) {
            var prior=json.readValue((String)existing.get("payload_json"),PayoutSnapshot.class);
            require(no.equals(prior.statementNo()) && revision==prior.statementRevision(),
                    "POINTS_IDEMPOTENCY_CONFLICT");
            return prior.ticket();
        }
        require(statement.current().number()==revision,"POINTS_STATEMENT_REVISION_CONFLICT");
        require("CONFIRMED".equals(statement.state()),"POINTS_STATEMENT_NOT_CONFIRMED");
        var type=read.type(typeCode).orElseThrow();
        List<PositionMove> positions=new ArrayList<>();
        FundsContext recipient=null;
        long points=0;
        List<String> ids=new ArrayList<>();
        for (String id:statement.positionIds()) {
            var source=store.lockSource(id);
            require("PAYABLE".equals(source.get("state")) && no.equals(source.get("statement_no")),
                    "POINTS_POSITION_NOT_PAYABLE");
            var snapshot=json.readValue((String)source.get("payload_json"),RedeemSnapshot.class);
            var c=snapshot.fundsContext();
            var route=new FundsContext(c.path(),c.channel(),c.merchantNo(),c.storeNo(),c.accountVersion(),
                    c.subMerchantId(),null,null,0);
            require(recipient==null || recipient.equals(route),"POINTS_PAYOUT_RECIPIENT_MISMATCH");
            recipient=route;
            long remaining=Math.subtractExact(num(source,"original_points"),num(source,"processed_points"));
            if (remaining==0) { continue; }
            points=Math.addExact(points,remaining); ids.add(id);
            positions.add(new PositionMove(PositionMove.Kind.REDEEM,id,num(source,"version"),"PAYABLE","PAYING",
                    num(source,"original_points"),0,(String)source.get("payload_json")));
        }
        require(points>0 && points==statement.current().points(),"POINTS_STATEMENT_REDRAFT_REQUIRED");
        long gross=type.rate().toMinor(points);
        List<Offset> offsets=new ArrayList<>();
        List<ReceivableMove> debts=new ArrayList<>();
        long offset=0;
        for (var debt:store.debts(typeCode,statement.period().storeNo())) {
            long available=Math.subtractExact(Math.subtractExact(num(debt,"original_minor"),
                    num(debt,"recovered_minor")),num(debt,"locked_minor"));
            long amount=Math.min(gross-offset,available);
            if (amount==0) { break; }
            String debtNo=(String)debt.get("receivable_no");
            offsets.add(new Offset(debtNo,amount)); offset=Math.addExact(offset,amount);
            debts.add(new ReceivableMove(debtNo,statement.period().storeNo(),num(debt,"version"),"OPEN","OPEN",
                    num(debt,"original_minor"),0,amount,(String)debt.get("payload_json")));
        }
        long cash=Math.subtractExact(gross,offset);
        var gateway=gateways.getIfAvailable();
        require(cash==0 || (gateway!=null && gateway.capabilities(recipient).transfer()),
                "POINTS_PAYOUT_CAPABILITY_UNSUPPORTED");
        var ticket=new PayoutTicket(ticketNo,gross,offset,cash,"PTS-"+ticketNo,FundsStatus.UNKNOWN);
        var command=new PayoutCommand(biz,typeCode,no,ids,FundsPurpose.TRANSFER,recipient,type.currency());
        var funds=new FundsCommand(ticketNo,ticket.providerIdem(),FundsPurpose.TRANSFER,recipient,type.currency(),cash);
        var saved=new PayoutSnapshot(command,ticket,funds,null,no,revision,offsets);
        positions.add(new PositionMove(PositionMove.Kind.PAYOUT,ticketNo,-1,"NEW","READY",points,0,
                json.writeValueAsString(saved)));
        var paying=new Snapshot(no,statement.period(),statement.positionIds(),statement.revisions(),"PAYING",null);
        positions.add(new PositionMove(PositionMove.Kind.STATEMENT,no,num(head,"version"),"CONFIRMED","PAYING",
                num(head,"original_points"),0,json.writeValueAsString(paying)));
        posting.post(new Voucher("STATEMENT_PAYOUT:"+biz,typeCode,type.currency(),type.rate(),List.of(),List.of(),
                positions,0,List.of(),json.writeValueAsString(Map.of("actor",actor,"ticket",ticket)),
                List.of(),List.of(),debts));
        return ticket;
    }
    private long num(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private void require(boolean condition,String reason) {
        if (!condition) { throw new IllegalArgumentException(reason); }
    }
}
