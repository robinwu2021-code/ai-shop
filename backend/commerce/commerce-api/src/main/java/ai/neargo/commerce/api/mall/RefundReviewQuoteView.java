package ai.neargo.commerce.api.mall;

public record RefundReviewQuoteView(
        String reviewQuoteNo, String requestDigest, RefundPreviewView preview) {}
