package ai.neargo.shop.paybridge;

public interface PaymentCollectionService {
    record Accepted(String eventId, String payloadSha256, String status, String sourceReceiptNo) {}

    /** Receives one verified result of the given payment environment. */
    Accepted receive(String environment, PaymentCollectionFact fact, String raw);

    /** The original SANDBOX-only entry. */
    default Accepted receive(PaymentCollectionFact fact, String raw) {
        return receive("SANDBOX", fact, raw);
    }
}
