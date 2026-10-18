package ai.neargo.shop.trade.order.points;

import ai.neargo.shop.spi.points.PointsOrderEvent;
import ai.neargo.shop.trade.order.points.OrderPointsBindingService.*;

public interface OrderPointsCoordinator {
    Decision decide(Context context);

    StoreObligations inspectStore(String storeNo);

    record StoreObligations(boolean known,long activePolicies,long openBindings,boolean sourceSettled,
            ai.neargo.shop.spi.points.PointsObligationPort.Obligations pay) {}

    Binding admit(Admission command);

    Binding resume(String requestId);

    void onSourceEvent(PointsOrderEvent event);

    enum Action {
        BYPASS,
        RECONFIRM,
        READY
    }

    record Context(
            String storeNo,
            String scene,
            boolean wantsRedeem,
            boolean wantsEarn,
            boolean confirmedPointsPrice) {}

    record Decision(
            Action action,
            long policyVersion,
            boolean earning,
            boolean redemption,
            String reason) {}
}
