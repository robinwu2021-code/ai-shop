package ai.neargo.shop.trade.order.points;

import ai.neargo.shop.spi.points.PointsRedeemPort.Item;
import ai.neargo.shop.spi.points.PointsRefundPort.PaymentSnapshot;

import java.util.List;

public interface OrderPointsBindingService {
    Binding admit(Admission command);

    Binding find(String subOrderNo);

    Binding transition(Transition command);

    enum Phase {
        PENDING,
        BOUND,
        CLOSING,
        CLOSED
    }

    record Snapshot(
            long goodsMinor,
            long quotedPoints,
            long quotedMinor,
            long ratePoints,
            long rateMinor,
            String ruleSnapshot,
            String agreementSnapshot,
            PaymentSnapshot payment,
            List<Item> items) {
        public Snapshot {
            items = List.copyOf(items);
        }
    }

    record Admission(
            String requestId,
            String orderNo,
            String subOrderNo,
            String userNo,
            String merchantNo,
            String storeNo,
            String typeCode,
            String currency,
            String scene,
            long policyVersion,
            boolean earning,
            boolean redemption,
            Snapshot snapshot) {}

    record Binding(
            String bindingNo, Admission admission, Phase phase, long version, String holdNo) {
        public Snapshot snapshot() {
            return admission.snapshot();
        }
    }

    record Transition(String bindingNo, long expectedVersion, Phase target, String holdNo) {}

    interface CompletionGuard {
        boolean complete(Binding binding);
    }
}
