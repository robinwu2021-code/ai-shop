package ai.neargo.shop.pay.points.refund.impl;

import ai.neargo.shop.pay.points.model.EvidenceRef;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.refund.PointsRefundCollectionService;
import ai.neargo.shop.pay.points.refund.impl.PointsRefundPreparationServiceImpl.RefundSnapshot;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsCashRefundReadStore;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundPreparation;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** Successful collection evidence stays bound to this preparation until the final financial voucher consumes it. */
@Service
@Transactional("payTxManager")
public class PointsRefundCollectionServiceImpl implements PointsRefundCollectionService {
    private final PointsReadStore read;
    private final PointsConfigurationStore configuration;
    private final PointsPostingService posting;
    private final PointsCashRefundReadStore cash;
    private final ObjectMapper json=new ObjectMapper();
    public PointsRefundCollectionServiceImpl(PointsReadStore read,PointsConfigurationStore configuration,
                                            PointsPostingService posting,PointsCashRefundReadStore cash) {
        this.read=read; this.configuration=configuration; this.posting=posting; this.cash=cash;
    }
    @Override public RefundPreparation reviseFailed(String no) {
        var head=locked(no);
        var snapshot=json.readValue((String)head.get("payload_json"),RefundSnapshot.class);
        require(snapshot.collectionResult()!=null && snapshot.collectionResult().status()==FundsStatus.FAILED,
                "POINTS_REFUND_COLLECTION_NOT_FAILED");
        var original=snapshot.preparation();
        if (original.revision()>1 && original.collectionTicket()==null) { return original; }
        require("COLLECTION_FAILED".equals(head.get("state")),"POINTS_REFUND_COLLECTION_STATE_CONFLICT");
        require(!cash.hasRefundForAfterSale(snapshot.command().afterSaleNo()),"POINTS_REFUND_CASH_ALREADY_PREPARED");
        var revised=new RefundPreparation(original.preparationNo(),original.typeCode(),
                Math.addExact(original.revision(),1),original.inputHash(),original.returnedPoints(),
                original.reclaimedPending(),original.reclaimedAvailable(),original.expectedClawbackPoints(),0,
                snapshot.command().businessCashMinor(),original.expectedClawbackPoints(),null,"PREPARED");
        var saved=new RefundSnapshot(snapshot.command(),revised,snapshot.grantState(),snapshot.redeemState(),
                snapshot.reclaimedByLine(),snapshot.collectionCommand(),null,null,snapshot.collectionResult());
        transition(no,head,saved,"PREPARED","COLLECTION_REVISE:"+revised.revision()+":");
        return revised;
    }
    @Override public Dispatch claim(String no) {
        var head=locked(no);
        var snapshot=json.readValue((String)head.get("payload_json"),RefundSnapshot.class);
        require(snapshot.collectionCommand()!=null,"POINTS_REFUND_NO_COLLECTION");
        if (snapshot.collectionResult()!=null && snapshot.collectionResult().status()!=FundsStatus.UNKNOWN) {
            return new Dispatch(snapshot.collectionCommand(),false,snapshot.collectionResult());
        }
        String state=(String)head.get("state");
        require(List.of("COLLECTION_REQUIRED","COLLECTING","COLLECTION_UNKNOWN").contains(state),
                "POINTS_REFUND_COLLECTION_STATE_CONFLICT");
        boolean submit="COLLECTION_REQUIRED".equals(state);
        if (submit) { transition(no,head,snapshot,"COLLECTING","COLLECTION_DISPATCH:"); }
        return new Dispatch(snapshot.collectionCommand(),submit,null);
    }
    @Override public FundsResult accept(String no,FundsResult result) {
        var head=locked(no);
        var snapshot=json.readValue((String)head.get("payload_json"),RefundSnapshot.class);
        var command=snapshot.collectionCommand();
        require(command!=null && result!=null && result.status()!=null && command.ticketNo().equals(result.ticketNo())
                && command.amountMinor()==result.amountMinor(),"POINTS_REFUND_COLLECTION_RECEIPT_MISMATCH");
        if (snapshot.collectionResult()!=null && snapshot.collectionResult().status()!=FundsStatus.UNKNOWN) {
            return snapshot.collectionResult();
        }
        String state=(String)head.get("state");
        require("COLLECTING".equals(state) || "COLLECTION_UNKNOWN".equals(state),
                "POINTS_REFUND_COLLECTION_STATE_CONFLICT");
        String next=switch (result.status()) {
            case SUCCEEDED -> "COLLECTED";
            case FAILED -> "COLLECTION_FAILED";
            case UNKNOWN -> "COLLECTION_UNKNOWN";
        };
        if (result.status()==FundsStatus.SUCCEEDED) {
            require(result.evidenceNo()!=null && !result.evidenceNo().isBlank(),
                    "POINTS_REFUND_COLLECTION_RECEIPT_MISMATCH");
            posting.recordEvidence(new EvidenceRef(result.evidenceNo(),command.currency(),command.amountMinor(),
                    "REFUND_COLLECTION",no));
        }
        var saved=new RefundSnapshot(snapshot.command(),snapshot.preparation(),snapshot.grantState(),
                snapshot.redeemState(),snapshot.reclaimedByLine(),command,null,null,result);
        transition(no,head,saved,next,"COLLECTION_RESULT:"+number(head,"version")+":");
        return result;
    }
    private Map<String,Object> locked(String no) {
        var head=read.position(PositionMove.Kind.REFUND.table(),no);
        require(head!=null,"POINTS_REFUND_PREPARATION_UNKNOWN");
        configuration.lockTypeAndPool((String)head.get("type_code"));
        return configuration.lockRefundPreparation(no);
    }
    private void transition(String no,Map<String,Object> head,RefundSnapshot snapshot,String next,String prefix) {
        var type=read.type((String)head.get("type_code")).orElseThrow();
        posting.post(new Voucher(prefix+no,type.typeCode(),type.currency(),type.rate(),List.of(),List.of(),
                List.of(new PositionMove(PositionMove.Kind.REFUND,no,number(head,"version"),
                        (String)head.get("state"),next,number(head,"original_points"),0,
                        json.writeValueAsString(snapshot))),0,List.of(),"{}"));
    }
    private long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private void require(boolean condition,String reason) {
        if (!condition) { throw new IllegalArgumentException(reason); }
    }
}
