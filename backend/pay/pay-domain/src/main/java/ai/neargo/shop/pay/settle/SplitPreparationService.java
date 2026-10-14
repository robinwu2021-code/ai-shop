package ai.neargo.shop.pay.settle;

import ai.neargo.shop.pay.entity.StlBill;

/** Preparation and dispatch ownership are short pay transactions, completed before any external call. */
public interface SplitPreparationService {
    Plan prepare(String settleNo);
    Claim claim(String requestNo);
    /** Retains the bill lock in the caller's pay transaction; null means an outgoing result is still uncertain. */
    StlBill lockForReverse(String settleNo);
    record Plan(SplitCommand command,String pointsTicket,boolean completed) { }
    record Claim(SplitCommand command,boolean submit,boolean completed) { }
}
