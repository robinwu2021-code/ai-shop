package ai.neargo.shop.pay.points.refund;

import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsCommand;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundPreparation;

/** Commits collection dispatch and records its trusted evidence without prematurely crediting issuance U. */
public interface PointsRefundCollectionService {
    RefundPreparation reviseFailed(String preparationNo);
    Dispatch claim(String preparationNo);
    FundsResult accept(String preparationNo,FundsResult result);
    record Dispatch(FundsCommand command,boolean submit,FundsResult completed) { }
}
