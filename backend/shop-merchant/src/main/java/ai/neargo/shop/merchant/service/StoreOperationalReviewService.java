package ai.neargo.shop.merchant.service;

/** Store operating admission, independent of legal qualification and payment onboarding. */
public interface StoreOperationalReviewService {
    record ReviewVO(String storeNo, String status, String review, String source, String orderNo, Long decidedAt, Long submittedAt) {}
    ReviewVO read(String storeNo);
    ReviewVO submit(String storeNo);
    ReviewVO decide(String storeNo, boolean approved, String reason);
    /** Trusted paid-fulfillment boundary; joins the caller transaction. */
    void activatePaid(String tenantNo, String entityNo, String storeNo, String model, String orderNo, long purchasedAt, long at);
}
