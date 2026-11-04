package ai.neargo.shop.pay.service;

import java.util.List;

/** Durable ledger handoff; acknowledgement is separate from recording the cash fact. */
public interface PlatformMoneyHandoffService {
    record Handoff(String paymentNo, String direction, String tenantNo, String orderNo) {}

    List<Handoff> pending(int limit);

    default List<Handoff> pending(String afterPaymentNo, int limit) {
        return pending(limit);
    }

    void acknowledge(String paymentNo);
}
