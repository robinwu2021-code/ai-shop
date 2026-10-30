package ai.neargo.shop.paybridge.service;

import ai.neargo.shop.spi.settle.PaymentOperationPort.OfflinePaymentReceiptReq;
import ai.neargo.shop.spi.settle.PaymentOperationPort.PaymentOperationReq;
import ai.neargo.shop.spi.settle.PaymentOperationPort.PaymentOperationView;
import ai.neargo.shop.spi.settle.SettlePort;

/** Lock-free cross-owner orchestration around short durable pay transactions. */
public interface PaymentDispatchService {
    PaymentOperationView prepare(PaymentOperationReq request);

    PaymentOperationView initiate(String operationNo);

    PaymentOperationView query(String operationNo);

    PaymentOperationView confirmOffline(OfflinePaymentReceiptReq request);

    /** The old trade caller shares exactly the same canonical send arbitration. */
    SettlePort.PayInitResult initiateLegacy(SettlePort.PaymentOpen payment);
}
