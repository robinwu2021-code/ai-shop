package ai.neargo.shop.pay.points.posting;

/** Replays return the originally committed result, not a newly calculated answer. */
public record PostingResult(String voucherNo, boolean replayed, String resultJson) { }
