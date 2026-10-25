package ai.neargo.shop.spi.trade;

import ai.neargo.shop.event.DomainEvent;

/**
 * Manual declarations have their own event types so financial paid consumers cannot mistake them.
 */
public final class ManualServiceAdmissionEvents {
    private ManualServiceAdmissionEvents() {}

    public record CounterOrderCreated(
            String orderNo,
            String subOrderNo,
            String operationNo,
            String entityNo,
            String storeNo,
            ManualServiceAdmissionPort.CustomerParty party)
            implements DomainEvent {
        @Override
        public String aggregateType() {
            return "ORDER";
        }

        @Override
        public String aggregateId() {
            return orderNo;
        }

        @Override
        public String eventType() {
            return "COUNTER_ORDER_CREATED";
        }
    }

    public record ManualServiceReceiptRecorded(
            String orderNo, String declarationNo, long admissionRevision) implements DomainEvent {
        @Override
        public String aggregateType() {
            return "MANUAL_SERVICE_ADMISSION";
        }

        @Override
        public String aggregateId() {
            return orderNo;
        }

        @Override
        public String eventType() {
            return "MANUAL_SERVICE_RECEIPT_RECORDED";
        }
    }

    public record ManualServiceAdmissionChanged(
            String orderNo, String declarationNo, long admissionRevision, String admissionState)
            implements DomainEvent {
        @Override
        public String aggregateType() {
            return "MANUAL_SERVICE_ADMISSION";
        }

        @Override
        public String aggregateId() {
            return orderNo;
        }

        @Override
        public String eventType() {
            return "MANUAL_SERVICE_ADMISSION_CHANGED";
        }
    }
}
