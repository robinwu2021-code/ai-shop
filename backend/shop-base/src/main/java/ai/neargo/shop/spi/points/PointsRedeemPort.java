package ai.neargo.shop.spi.points;

import java.util.List;

/**
 * Order prices are authoritative inputs; every new hold rechecks account, agreement, and funding
 * capacity.
 */
public interface PointsRedeemPort {
    RedeemQuote quote(RedeemQuery query);

    HoldOutcome hold(HoldCommand command);

    HoldOutcome release(ReleaseCommand command);

    record Item(String itemId, long goodsMinor) {}

    record Target(
            String subOrderNo,
            String merchantNo,
            String storeNo,
            long goodsMinor,
            String path,
            String channel,
            String accountVersion,
            String subMerchantId,
            String tradeNo,
            List<Item> items) {
        public Target {
            items = List.copyOf(items);
        }
    }

    record RedeemQuery(
            String market,
            String userNo,
            long wantPoints,
            String payMode,
            String clientType,
            List<Target> targets) {
        public RedeemQuery {
            targets = List.copyOf(targets);
        }
    }

    record AcceptedPrice(
            String typeCode,
            long pointsUnit,
            long minorUnit,
            long points,
            long minor,
            List<Share> shares) {
        public AcceptedPrice {
            shares = List.copyOf(shares);
        }
    }

    record HoldCommand(
            String orderNo,
            String market,
            String userNo,
            long wantPoints,
            String payMode,
            String clientType,
            List<Target> targets,
            AcceptedPrice acceptedPrice) {
        public HoldCommand {
            targets = List.copyOf(targets);
        }

        public HoldCommand(
                String orderNo,
                String market,
                String userNo,
                long wantPoints,
                String payMode,
                String clientType,
                List<Target> targets) {
            this(orderNo, market, userNo, wantPoints, payMode, clientType, targets, null);
        }
    }

    record ReleaseCommand(String orderNo, String eventNo, long sourceVersion) {}

    record Share(String subOrderNo, String merchantNo, String storeNo, long points, long minor) {}

    record RedeemQuote(
            String typeCode,
            long pointsUnit,
            long minorUnit,
            long points,
            long minor,
            String reason,
            List<Share> shares) {
        public RedeemQuote {
            shares = List.copyOf(shares);
        }
    }

    record HoldOutcome(
            String holdNo,
            String typeCode,
            long points,
            long minor,
            String state,
            String reason,
            List<Share> shares) {
        public HoldOutcome {
            shares = List.copyOf(shares);
        }
    }
}
