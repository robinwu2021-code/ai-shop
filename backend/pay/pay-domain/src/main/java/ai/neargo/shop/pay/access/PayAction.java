package ai.neargo.shop.pay.access;

/** 能力矩阵的动作（设计 v4 §2.8.2；I3 用到收款、查关、退款与复核几格，其余随各自迭代） */
public enum PayAction {
    QUERY_METHODS, CREATE_ONLINE, CREATE_IN_STORE, START_PAYMENT, QUERY, CLOSE, VOID,
    REQUEST_REFUND, REVIEW_REFUND, READ_LEDGER, TERMINAL_BATCH
}
