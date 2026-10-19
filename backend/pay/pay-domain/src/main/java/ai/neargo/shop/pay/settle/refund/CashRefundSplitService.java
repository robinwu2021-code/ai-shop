package ai.neargo.shop.pay.settle.refund;

import ai.neargo.shop.spi.settle.SettlePort.SplitReversal;

/** Cash-only source continuation. Unknown outcomes query the committed original request, never resubmit it. */
public interface CashRefundSplitService {
    boolean reverse(SplitReversal command);
}
