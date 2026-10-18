package ai.neargo.shop.spi.points;

import java.util.List;

/** Paid order snapshots define earning; the pay domain never rereads mutable product rules. */
public interface PointsEarnPort {
    GrantOutcome grant(GrantCommand command);

    enum RuleMode {
        FIXED,
        RATIO
    }

    enum GrantStatus {
        GRANTED,
        SKIPPED
    }

    record EarnRule(RuleMode mode, long value) {}

    record EarnLine(
            String itemId,
            String goodsNo,
            String categoryNo,
            long quantity,
            long goodsCashMinor,
            long refundedBeforeGrantMinor,
            EarnRule rule) {}

    record GrantCommand(
            String eventNo,
            String subOrderNo,
            String userNo,
            String typeCode,
            String storeNo,
            String payChannel,
            String payScene,
            long sourceVersion,
            List<EarnLine> lines,
            String merchantNo,
            String clientType,
            PointsRefundPort.PaymentSnapshot payment) {
        public GrantCommand {
            lines = List.copyOf(lines);
        }

        public GrantCommand(
                String eventNo,
                String subOrderNo,
                String userNo,
                String typeCode,
                String storeNo,
                String payChannel,
                String payScene,
                long sourceVersion,
                List<EarnLine> lines,
                String merchantNo,
                String clientType) {
            this(
                    eventNo,
                    subOrderNo,
                    userNo,
                    typeCode,
                    storeNo,
                    payChannel,
                    payScene,
                    sourceVersion,
                    lines,
                    merchantNo,
                    clientType,
                    null);
        }

        public GrantCommand(
                String eventNo,
                String subOrderNo,
                String userNo,
                String typeCode,
                String storeNo,
                String payChannel,
                String payScene,
                long sourceVersion,
                List<EarnLine> lines) {
            this(
                    eventNo,
                    subOrderNo,
                    userNo,
                    typeCode,
                    storeNo,
                    payChannel,
                    payScene,
                    sourceVersion,
                    lines,
                    null,
                    null);
        }
    }

    record GrantOutcome(
            GrantStatus status,
            long grantedPoints,
            long expectedPoints,
            String reason,
            String voucherNo) {}
}
