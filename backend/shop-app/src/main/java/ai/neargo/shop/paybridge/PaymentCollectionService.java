package ai.neargo.shop.paybridge;

public interface PaymentCollectionService {
    record Accepted(String eventId, String payloadSha256, String status, String sourceReceiptNo) {}

    Accepted receive(PaymentCollectionFact fact, String raw);
}
