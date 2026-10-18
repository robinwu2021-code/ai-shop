package ai.neargo.shop.pay.points.model;

/** A committed outgoing intent reserves existing U/B money without pretending that cash has left. */
public record BudgetLockMove(FundBucket bucket,long deltaMinor) { }
