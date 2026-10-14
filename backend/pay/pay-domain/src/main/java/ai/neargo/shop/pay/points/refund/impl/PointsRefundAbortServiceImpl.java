package ai.neargo.shop.pay.points.refund.impl;

import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.refund.PointsRefundAbortService;
import ai.neargo.shop.pay.points.refund.impl.PointsRefundPreparationServiceImpl.RefundSnapshot;
import ai.neargo.shop.pay.points.store.PointsCashRefundReadStore;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundAbort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Unknown cash or unfinished collection retains the financial freeze, including across retries. */
@Service
@Transactional("payTxManager")
public class PointsRefundAbortServiceImpl implements PointsRefundAbortService {
    private final PointsReadStore read;
    private final PointsConfigurationStore configuration;
    private final PointsCashRefundReadStore cash;
    private final PointsPostingService posting;
    private final ObjectMapper json=new ObjectMapper();
    public PointsRefundAbortServiceImpl(PointsReadStore read,PointsConfigurationStore configuration,
                                       PointsCashRefundReadStore cash,PointsPostingService posting) {
        this.read=read; this.configuration=configuration; this.cash=cash; this.posting=posting;
    }
    @Override public void abort(RefundAbort abort) {
        require(abort!=null && abort.preparationNo()!=null,"POINTS_REFUND_ABORT_INVALID");
        var initial=read.position(PositionMove.Kind.REFUND.table(),abort.preparationNo());
        require(initial!=null,"POINTS_REFUND_PREPARATION_UNKNOWN");
        configuration.lockTypeAndPool((String)initial.get("type_code"));
        var head=read.position(PositionMove.Kind.REFUND.table(),abort.preparationNo());
        var snapshot=json.readValue((String)head.get("payload_json"),RefundSnapshot.class);
        var preparation=snapshot.preparation();
        require(preparation.inputHash().equals(abort.inputHash()),"POINTS_REFUND_INPUT_CONFLICT");
        if ("ABORTED".equals(head.get("state"))) {
            require(abort.equals(snapshot.abort()),"POINTS_REFUND_ABORT_CONFLICT");
            return;
        }
        require("PREPARED".equals(head.get("state")) && preparation.collectionTicket()==null,
                "POINTS_REFUND_ABORT_NOT_SAFE");
        var payment=cash.refund(abort.cashRefundPaymentNo());
        require(payment!=null && Set.of("FAILED","CLOSED").contains(payment.status()),
                "POINTS_REFUND_CASH_OUTCOME_UNKNOWN");
        var command=snapshot.command();
        require("REFUND".equals(payment.direction()) && command.afterSaleNo().equals(payment.afterSaleNo())
                && command.subOrderNo().equals(payment.subOrderNo()) && command.userNo().equals(payment.userNo())
                && command.currency().equals(payment.currency())
                && command.payment().channel().equals(payment.channel())
                && preparation.netCashMinor()==payment.amountMinor(),"POINTS_CASH_REFUND_MISMATCH");
        List<PointsMove> points=new ArrayList<>();
        restore(points,command.typeCode(),command.userNo(),Bucket.PENDING,preparation.reclaimedPending());
        restore(points,command.typeCode(),command.userNo(),Bucket.AVAILABLE,preparation.reclaimedAvailable());
        List<PositionMove> positions=new ArrayList<>();
        restorePosition(positions,PositionMove.Kind.GRANT,"G",snapshot.grantState(),snapshot);
        restorePosition(positions,PositionMove.Kind.REDEEM,"R",snapshot.redeemState(),snapshot);
        var completed=new RefundSnapshot(command,preparation,snapshot.grantState(),snapshot.redeemState(),
                snapshot.reclaimedByLine(),snapshot.collectionCommand(),null,abort,snapshot.collectionResult());
        positions.add(new PositionMove(PositionMove.Kind.REFUND,preparation.preparationNo(),number(head,"version"),
                "PREPARED","ABORTED",number(head,"original_points"),0,json.writeValueAsString(completed)));
        var type=read.type(command.typeCode()).orElseThrow();
        posting.post(new Voucher("REFUND_ABORT:"+preparation.preparationNo(),type.typeCode(),type.currency(),
                type.rate(),points,List.of(),positions,0,List.of(),"{}"));
    }
    private void restorePosition(List<PositionMove> positions,PositionMove.Kind kind,String key,String original,
                                 RefundSnapshot snapshot) {
        if (original==null) { return; }
        String no=PointsKeys.position(key,snapshot.command().typeCode(),snapshot.command().subOrderNo());
        var head=read.position(kind.table(),no);
        if (!"REFUND_PREPARED".equals(head.get("state"))) {
            require(kind==PositionMove.Kind.GRANT && number(head,"original_points")==0,
                    "POINTS_REFUND_SOURCE_STATE_CONFLICT");
            return;
        }
        positions.add(new PositionMove(kind,no,number(head,"version"),"REFUND_PREPARED",original,
                number(head,"original_points"),0,(String)head.get("payload_json")));
    }
    private void restore(List<PointsMove> moves,String type,String user,Bucket bucket,long amount) {
        if (amount==0) { return; }
        moves.add(new PointsMove(new AccountKey(type,HolderType.USER,user,Bucket.HELD),-amount));
        moves.add(new PointsMove(new AccountKey(type,HolderType.USER,user,bucket),amount));
    }
    private long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private void require(boolean condition,String reason) {
        if (!condition) { throw new IllegalArgumentException(reason); }
    }
}
