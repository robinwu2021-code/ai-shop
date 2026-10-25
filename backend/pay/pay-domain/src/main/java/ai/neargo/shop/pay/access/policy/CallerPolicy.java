package ai.neargo.shop.pay.access.policy;

import ai.neargo.shop.pay.access.PayAction;
import ai.neargo.shop.pay.access.PayCaller;

/** 入口的第一行：矩阵外、门店范围外一律 {@code PAY_FORBIDDEN}（设计 v4 AC24） */
public interface CallerPolicy {

    void check(PayCaller caller, PayAction action, Long storeId);
}
