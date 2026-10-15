package ai.neargo.shop.pay.points.refund.impl;

import ai.neargo.shop.pay.points.earn.impl.EarnServiceImpl.GrantSnapshot;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.FundBucket;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.ReceivableMove;
import ai.neargo.shop.pay.points.model.ReserveMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.refund.PointsRefundConfirmationService;
import ai.neargo.shop.pay.points.refund.impl.PointsRefundPreparationServiceImpl.RefundSnapshot;
import ai.neargo.shop.pay.points.redeem.impl.RedeemServiceImpl.RedeemSnapshot;
import ai.neargo.shop.pay.points.store.PointsCashRefundReadStore;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundConfirmation;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundOutcome;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import ai.neargo.shop.pay.points.model.EvidenceRef;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;

/** Cash confirmation, grant reclaim, returned redemption and store receivable are one short pay transaction. */
@Service
@Transactional("payTxManager")
public class PointsRefundConfirmationServiceImpl implements PointsRefundConfirmationService {
    private final PointsReadStore read;
    private final PointsConfigurationStore configuration;
    private final PointsCashRefundReadStore cash;
    private final PointsPostingService posting;
    private final ObjectMapper json=new ObjectMapper();
    public PointsRefundConfirmationServiceImpl(PointsReadStore read,PointsConfigurationStore configuration,
                                               PointsCashRefundReadStore cash,PointsPostingService posting) {
        this.read=read; this.configuration=configuration; this.cash=cash; this.posting=posting;
    }
    @Override public RefundOutcome confirm(RefundConfirmation confirmation) {
        require(confirmation!=null && confirmation.preparationNo()!=null,"POINTS_REFUND_CONFIRMATION_INVALID");
        var initial=read.position(PositionMove.Kind.REFUND.table(),confirmation.preparationNo());
        require(initial!=null,"POINTS_REFUND_PREPARATION_UNKNOWN");
        configuration.lockTypeAndPool((String)initial.get("type_code"));
        var head=configuration.lockRefundPreparation(confirmation.preparationNo());
        var snapshot=json.readValue((String)head.get("payload_json"),RefundSnapshot.class);
        var preparation=snapshot.preparation();
        require(preparation.inputHash().equals(confirmation.inputHash()),"POINTS_REFUND_INPUT_CONFLICT");
        String ref="REFUND_CONFIRM:"+preparation.preparationNo();
        if ("CONFIRMED".equals(head.get("state"))) {
            require(confirmation.equals(snapshot.confirmation()),"POINTS_REFUND_CONFIRMATION_CONFLICT");
            return outcome(ref,true);
        }
        require("CASH_AUTHORIZED".equals(head.get("state")),
                "POINTS_REFUND_CASH_AUTHORIZATION_REQUIRED");
        require(Objects.equals(confirmation.collectionTicket(),preparation.collectionTicket())
                && confirmation.recoveryTicket()==null,
                "POINTS_REFUND_UNEXPECTED_FUNDS_TICKET");
        var command=snapshot.command();
        var payment=cash.refund(confirmation.cashRefundPaymentNo());
        require(payment!=null && "SUCCESS".equals(payment.status()) && payment.succeededAt()!=null
                && payment.providerNo()!=null && !payment.providerNo().isBlank() && payment.verifiedAt()!=null
                && payment.verifiedMinor()!=null,"POINTS_CASH_REFUND_NOT_CONFIRMED");
        require("REFUND".equals(payment.direction()) && command.afterSaleNo().equals(payment.afterSaleNo())
                && command.subOrderNo().equals(payment.subOrderNo()) && command.userNo().equals(payment.userNo())
                && command.currency().equals(payment.currency())
                && command.payment().channel().equals(payment.channel())
                && preparation.netCashMinor()==payment.amountMinor()
                && preparation.netCashMinor()==payment.verifiedMinor(),"POINTS_CASH_REFUND_MISMATCH");
        PointType type=read.type(command.typeCode()).orElseThrow();
        List<PointsMove> points=new ArrayList<>();
        List<FundMove> funds=new ArrayList<>();
        List<PositionMove> positions=new ArrayList<>();
        List<ReserveMove> reserves=new ArrayList<>();
        List<ReceivableMove> receivables=new ArrayList<>();
        long reclaimed=Math.addExact(preparation.reclaimedPending(),preparation.reclaimedAvailable());
        if (reclaimed>0) {
            points.add(move(type,HolderType.USER,command.userNo(),Bucket.HELD,-reclaimed));
            points.add(move(type,HolderType.ISSUE,"PLATFORM",Bucket.ISSUABLE,reclaimed));
            funds.add(new FundMove(FundBucket.C,-type.rate().toMinor(reclaimed)));
            funds.add(new FundMove(FundBucket.U,type.rate().toMinor(reclaimed)));
        }
        List<EvidenceRef> evidence=new ArrayList<>();
        long collected=preparation.cashOffsetMinor();
        if (collected>0) {
            var receipt=snapshot.collectionResult();
            require(receipt!=null && receipt.status()==FundsStatus.SUCCEEDED
                    && receipt.amountMinor()==collected && receipt.evidenceNo()!=null,
                    "POINTS_REFUND_COLLECTION_NOT_CONFIRMED");
            long issued=type.rate().pointsForMinor(collected);
            points.add(move(type,HolderType.MINT,"PLATFORM",Bucket.ISSUED,-issued));
            points.add(move(type,HolderType.ISSUE,"PLATFORM",Bucket.ISSUABLE,issued));
            funds.add(new FundMove(FundBucket.U,collected));
            evidence.add(new EvidenceRef(receipt.evidenceNo(),type.currency(),collected,"REFUND_COLLECTION",
                    preparation.preparationNo()));
        }
        updateGrant(snapshot,positions);
        long receivable=returnRedemption(snapshot,type,points,funds,positions,reserves,receivables);
        var result=new RefundOutcome(preparation.preparationNo(),"CONFIRMED",preparation.returnedPoints(),reclaimed,
                collected,preparation.waivedPoints(),receivable,null,false);
        var completed=new RefundSnapshot(command,preparation,snapshot.grantState(),snapshot.redeemState(),
                snapshot.reclaimedByLine(),snapshot.collectionCommand(),confirmation,null,snapshot.collectionResult());
        positions.add(new PositionMove(PositionMove.Kind.REFUND,preparation.preparationNo(),number(head,"version"),
                (String)head.get("state"),"CONFIRMED",number(head,"original_points"),number(head,"original_points"),
                json.writeValueAsString(completed)));
        long reserveDelta=reserves.stream().mapToLong(ReserveMove::remainingDelta).sum();
        posting.post(new Voucher(ref,type.typeCode(),type.currency(),type.rate(),points,funds,positions,reserveDelta,
                evidence,json.writeValueAsString(result),reserves,List.of(),receivables));
        return outcome(ref,false);
    }
    private void updateGrant(RefundSnapshot snapshot,List<PositionMove> positions) {
        if (snapshot.grantState()==null) { return; }
        var command=snapshot.command();
        String no=PointsKeys.position("G",command.typeCode(),command.subOrderNo());
        var head=read.position(PositionMove.Kind.GRANT.table(),no);
        var grant=json.readValue((String)head.get("payload_json"),GrantSnapshot.class);
        var preparation=snapshot.preparation();
        Map<String,Long> processed=new HashMap<>(grant.processedByLine());
        snapshot.reclaimedByLine().forEach((item,delta) -> processed.merge(item,delta,Math::addExact));
        Map<String,Long> refunded=new HashMap<>();
        command.lines().forEach(line -> refunded.put(line.itemId(),line.cumulativeGoodsMinor()));
        var next=new GrantSnapshot(grant.subOrderNo(),grant.userNo(),grant.command(),grant.decision(),grant.outcome(),
                grant.activatedPoints(),Math.addExact(grant.reclaimedPending(),preparation.reclaimedPending()),
                Math.addExact(grant.reclaimedAvailable(),preparation.reclaimedAvailable()),
                Math.addExact(grant.collectedMinor(),preparation.cashOffsetMinor()),
                Math.addExact(grant.waivedPoints(),preparation.waivedPoints()),processed,refunded);
        long processedDelta=snapshot.reclaimedByLine().values().stream().reduce(0L,Math::addExact);
        positions.add(new PositionMove(PositionMove.Kind.GRANT,no,number(head,"version"),
                number(head,"original_points")==0?snapshot.grantState():"REFUND_PREPARED",snapshot.grantState(),
                number(head,"original_points"),processedDelta,json.writeValueAsString(next)));
    }
    private long returnRedemption(RefundSnapshot snapshot,PointType type,List<PointsMove> points,List<FundMove> funds,
            List<PositionMove> positions,List<ReserveMove> reserves,List<ReceivableMove> receivables) {
        if (snapshot.redeemState()==null) { return 0; }
        var command=snapshot.command();
        long amount=snapshot.preparation().returnedPoints();
        long minor=type.rate().toMinor(amount);
        String no=PointsKeys.position("R",command.typeCode(),command.subOrderNo());
        var head=read.position(PositionMove.Kind.REDEEM.table(),no);
        var redemption=json.readValue((String)head.get("payload_json"),RedeemSnapshot.class);
        boolean store="IN_STORE".equals(snapshot.redeemState()) || "SETTLEABLE".equals(snapshot.redeemState());
        boolean paid="PAID".equals(snapshot.redeemState());
        if (amount>0) {
            points.add(move(type,store?HolderType.STORE:HolderType.RECEIVE,
                    store?redemption.share().storeNo():"PLATFORM",
                    store?("IN_STORE".equals(snapshot.redeemState())?Bucket.PENDING:Bucket.AVAILABLE):
                            Bucket.REFUND_RESERVED,-amount));
            points.add(move(type,HolderType.USER,command.userNo(),Bucket.AVAILABLE,amount));
            if (!store) {
                funds.add(new FundMove(paid?FundBucket.B:FundBucket.D,-minor));
                funds.add(new FundMove(FundBucket.C,minor));
            }
            String reserveNo=no;
            var reserve=read.reserve(reserveNo);
            require(reserve!=null,"POINTS_REFUND_RESERVE_UNKNOWN");
            reserves.add(new ReserveMove(reserveNo,no,number(reserve,"version"),number(reserve,"original_minor"),
                    -minor,paid?minor:0,paid?0:minor));
            if (paid) {
                receivables.add(new ReceivableMove(PointsKeys.position("L",type.typeCode(),command.afterSaleNo()),
                        redemption.share().storeNo(),-1,"NEW","OPEN",minor,0,0,
                        json.writeValueAsString(command)));
            }
        }
        positions.add(new PositionMove(PositionMove.Kind.REDEEM,no,number(head,"version"),"REFUND_PREPARED",
                snapshot.redeemState(),number(head,"original_points"),amount,(String)head.get("payload_json")));
        return paid?minor:0;
    }
    private RefundOutcome outcome(String ref,boolean replayed) {
        var voucher=read.voucher(ref);
        var result=json.readValue((String)voucher.get("result_json"),RefundOutcome.class);
        return new RefundOutcome(result.preparationNo(),result.state(),result.returnedPoints(),result.reclaimedPoints(),
                result.collectedMinor(),result.waivedPoints(),result.receivableMinor(),
                (String)voucher.get("voucher_no"),
                replayed);
    }
    private PointsMove move(PointType type,HolderType holder,String no,Bucket bucket,long amount) {
        return new PointsMove(new AccountKey(type.typeCode(),holder,no,bucket),amount);
    }
    private long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private void require(boolean condition,String reason) {
        if (!condition) { throw new IllegalArgumentException(reason); }
    }
}
