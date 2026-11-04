package ai.neargo.commerce.core.fulfillment;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.fulfillment.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.mall.MallSnapshot.Order;

import java.util.*;

/** Same local UoW. Account, balance, sources and lots precede mutable order/operation reads. */
public interface FulfillmentRepository {
    /** Immutable identity lookup only; never locks order before its subscription account. */
    record Location(StoreRef store, String currency) {}

    Optional<Location> locate(String tenant, String orderNo);

    record Operation(
            String operationNo,
            String orderNo,
            String componentNo,
            String kind,
            PaymentIntentCommand payment,
            InventoryCommand inventory,
            HardwareShipmentCommand shipment,
            PaymentOutcome paymentResult,
            InventoryOutcome inventoryResult) {
        public Operation paymentResult(PaymentOutcome result) {
            return new Operation(
                    operationNo,
                    orderNo,
                    componentNo,
                    kind,
                    payment,
                    inventory,
                    shipment,
                    result,
                    inventoryResult);
        }

        public Operation inventoryResult(InventoryOutcome result) {
            return new Operation(
                    operationNo,
                    orderNo,
                    componentNo,
                    kind,
                    payment,
                    inventory,
                    shipment,
                    paymentResult,
                    result);
        }
    }

    List<Operation> operations(String tenant, String orderNo);

    void save(String tenant, Operation operation);

    /**
     * Settles/releases exact existing reservation and FIFO lot holds, clears only owned occupancy.
     */
    void settleHolds(Order order, boolean consume);

    void prepareDigital(Order order);

    void digitalCompleted(Order order);

    void paid(Order order, VerifiedPaymentFact fact, String attemptNo);

    void shipment(Order order, Operation operation);
}
