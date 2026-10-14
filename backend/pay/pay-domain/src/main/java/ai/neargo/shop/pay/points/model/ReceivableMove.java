package ai.neargo.shop.pay.points.model;

/** R is a store-owned claim; only actual recovery or committed offset reduces it, never cash backing. */
public record ReceivableMove(String receivableNo,String storeNo,long expectedVersion,String fromState,
                             String nextState,long originalMinor,long recoveredDelta,long lockedDelta,String payload) { }
