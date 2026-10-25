package ai.neargo.shop.payclient;
import org.springframework.beans.factory.annotation.Autowired;


import ai.neargo.shop.pay.points.admin.PointsAdministrationService;
import ai.neargo.shop.pay.points.admin.PointsInspectionService;
import ai.neargo.shop.pay.points.policy.EarnPolicy;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.trade.order.points.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Call only after the order service has authorized the requested order. Reads never deliver events. */
@Service
public class OrderPointsEarningViewService {
    private final OrderPointsBindingService bindings;
    private final PointsInspectionService inspection;
    private final PointsAdministrationService types;
    private final PointsOperationsPort operations;
    private final EarnPolicy arithmetic = new ai.neargo.shop.pay.points.policy.DefaultEarnPolicy();
    private final ObjectMapper json = new ObjectMapper();
    public OrderPointsEarningViewService(OrderPointsBindingService bindings, PointsInspectionService inspection,
            PointsAdministrationService types, PointsOperationsPort operations) {
        this.bindings=bindings;this.inspection=inspection;this.types=types;this.operations=operations;
    }
    public record View(String orderNo, String typeCode, String name, String currency, String state,
            long expectedPoints, long grantedPoints, long pendingPoints, long availablePoints,
            long reclaimedPoints, String reason, String redemptionState, long usedPoints, String refundState, long returnedPoints) {
        public View(String orderNo,String typeCode,String name,String currency,String state,long expectedPoints,long grantedPoints,
                long pendingPoints,long availablePoints,long reclaimedPoints,String reason) {
            this(orderNo,typeCode,name,currency,state,expectedPoints,grantedPoints,pendingPoints,availablePoints,reclaimedPoints,reason,"NONE",0,"NONE",0);
        }
    }
    private OrderPointsRefundCoordinator refunds;
    private ai.neargo.shop.trade.order.mapper.OrderPointsRefundBindingMapper refundRows;
    @Autowired
    public void setRefundProgress(OrderPointsRefundCoordinator refunds,ai.neargo.shop.trade.order.mapper.OrderPointsRefundBindingMapper rows) {this.refunds=refunds;this.refundRows=rows;}
    private View decorate(View v,OrderPointsBindingService.Admission a) {
        String redemption="NONE",refund="NONE";long returned=0;
        if(refunds!=null) {
            returned=refunds.confirmedTotals(a.subOrderNo()).returnedPoints();
            var rows=ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(()->refundRows.forSubOrder(a.subOrderNo()));
            boolean complete=false,processing=false,manual=false;
            for(var row:rows) {
                String phase=(String)row.get("phase");
                complete|="COMMITTED".equals(phase)||"POINTS_CONFIRMED".equals(phase);
                manual|="COMPENSATED_PENDING_OPERATOR".equals(phase)||"CASH_FAILED_PENDING_OPERATOR".equals(phase);
                processing|=!java.util.Set.of("COMMITTED","ABORTED").contains(phase);
            }
            refund=manual?"MANUAL":processing?"PROCESSING":complete?"COMPLETED":"NONE";
        }
        if(a.redemption()) {
            redemption="PROCESSING";
            try {
                var position=inspection.position(a.typeCode(),ai.neargo.shop.pay.points.model.PositionMove.Kind.REDEEM,
                        ai.neargo.shop.pay.points.model.PointsKeys.position("R",a.typeCode(),a.subOrderNo()));
                if(position!=null)redemption=switch(position.state()) {
                    case "HELD" -> "HELD";case "RELEASED" -> "RELEASED";case "REFUND_PREPARED" -> "REFUND_PROCESSING";
                    case "PAYABLE","PAID","IN_STORE","SETTLEABLE","SETTLED","CONSUMED" -> "CONSUMED";
                    default -> "PROCESSING";
                };
            } catch(IllegalArgumentException ex){if(!"POINTS_POSITION_UNKNOWN".equals(ex.getMessage()))throw ex;}
            if(returned>0)redemption=returned>=a.snapshot().quotedPoints()?"RETURNED":"PARTIALLY_RETURNED";
        }
        return new View(v.orderNo(),v.typeCode(),v.name(),v.currency(),v.state(),v.expectedPoints(),v.grantedPoints(),v.pendingPoints(),
                v.availablePoints(),v.reclaimedPoints(),v.reason(),redemption,a.snapshot().quotedPoints(),refund,returned);
    }
    public List<View> read(OrderVO authorized) {
        if (authorized.subOrders()!=null && !authorized.subOrders().isEmpty())
            return authorized.subOrders().stream().flatMap(order -> read(order).stream()).toList();
        var binding=bindings.find(authorized.orderNo());
        if(binding==null)return List.of();
        var a=binding.admission();
        if(!Objects.equals(a.orderNo(),authorized.payOrderNo()) || !Objects.equals(a.merchantNo(),authorized.merchantNo()))
            throw new IllegalArgumentException("POINTS_ORDER_BINDING_MISMATCH");
        var snapshot=json.readValue(a.snapshot().ruleSnapshot(),PointsOrderPlacementCoordinator.EarningSnapshot.class);
        var type=types.types().stream().filter(t->t.typeCode().equals(a.typeCode())).findFirst()
                .orElseThrow(()->new IllegalArgumentException("POINTS_TYPE_UNKNOWN"));
        if(!a.earning())return List.of(decorate(new View(a.subOrderNo(),a.typeCode(),type.name(),a.currency(),"NONE",0,0,0,0,0,null),a));
        long expected=arithmetic.calculate(snapshot.lines().stream().map(line -> new EarnPolicy.Line(
                line.itemId(),line.quantity(),line.goodsCashMinor(),line.refundedBeforeGrantMinor(),
                EarnPolicy.Mode.valueOf(line.rule().mode().name()),line.rule().value())).toList(),
                new RedeemRate(a.snapshot().ratePoints(),a.snapshot().rateMinor())).expectedPoints();
        String state=authorized.paidAt()==null?"EXPECTED":"PROCESSING",reason=null;
        if (authorized.paidAt()==null && "CANCELLED".equals(authorized.status())) {
            return List.of(decorate(new View(a.subOrderNo(),a.typeCode(),type.name(),a.currency(),"SKIPPED",0,0,0,0,0,"POINTS_ORDER_CANCELLED"),a));
        }
        var grant=inspection.earning(a.typeCode(),a.subOrderNo());
        if(grant!=null){
            state="SKIPPED".equals(grant.state())?"SKIPPED":grant.grantedPoints()>0&&grant.reclaimedPoints()>=grant.grantedPoints()?"RECLAIMED":grant.pendingPoints()>0?"PENDING":"AVAILABLE";
            return List.of(decorate(new View(a.subOrderNo(),a.typeCode(),type.name(),a.currency(),state,expected,
                    grant.grantedPoints(),grant.pendingPoints(),grant.availablePoints(),grant.reclaimedPoints(),grant.reason()),a));
        }
        if(authorized.paidAt()!=null && snapshot.schemaVersion()==3){
            try {
                var receipt=operations.status(new PointsOperationsPort.Operation(PointsOperationsPort.Source.TRADE,
                        a.subOrderNo(),snapshot.operationNo(),a.typeCode()),PointsOperationsPort.Action.EARN);
                reason=receipt.reason();
                state=switch(receipt.status()) {case RETRYABLE -> "RETRYABLE";case MANUAL -> "MANUAL";case SKIPPED -> "SKIPPED";default -> "PROCESSING";};
            } catch(IllegalArgumentException ex){ if(!"POINTS_OPERATION_UNKNOWN".equals(ex.getMessage()))throw ex; }
        }
        return List.of(decorate(new View(a.subOrderNo(),a.typeCode(),type.name(),a.currency(),state,expected,0,0,0,0,reason),a));
    }
}
