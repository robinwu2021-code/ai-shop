package ai.neargo.shop.pay.points.port;

/** Explicit money operations; accepting a command never means money has arrived. */
public interface PointsFundsGateway {
    /** Advertises separately verified capabilities before accepting a financial promise. */
    FundsCapabilities capabilities(FundsContext context);

    /** Executes an already committed intent, outside a database transaction. */
    FundsResult submit(FundsCommand command);

    /** Resolves an unknown outcome using the original immutable command. */
    FundsResult query(FundsCommand command);

    enum FundsPath { DIRECT, AGGREGATED }
    enum FundsPurpose {
        SUBSIDY, INTERNAL_REPLENISHMENT, TRANSFER, COLLECTION, EXPIRY_INCOME;
        /** Direct orders need merchant subsidy; aggregated orders replenish the platform goods-funds allocation. */
        public static FundsPurpose forPerOrder(FundsPath path) {
            if (path==null) { throw new IllegalArgumentException("POINTS_FUNDS_PATH_REQUIRED"); }
            return path==FundsPath.DIRECT?SUBSIDY:INTERNAL_REPLENISHMENT;
        }
    }
    enum FundsStatus { SUCCEEDED, FAILED, UNKNOWN }

    record FundsContext(FundsPath path, String channel, String merchantNo, String storeNo, String accountVersion,
                        String subMerchantId, String tradeNo, String outTradeNo, long orderTotalMinor) { }
    record FundsCapabilities(boolean subsidy, boolean transfer, boolean internalReplenishment,
                             boolean collection) { }
    record FundsCommand(String ticketNo, String providerIdem, FundsPurpose purpose, FundsContext context,
                        String currency, long amountMinor) { }
    record FundsResult(String ticketNo, FundsStatus status, String evidenceNo, long amountMinor, String reason) { }
}
