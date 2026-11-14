package ai.neargo.shop.pay.points.posting;
/** Replays return the originally committed result and its human document alias. */
public record PostingResult(String voucherNo, boolean replayed, String resultJson, String documentNo) {
    public PostingResult(String voucherNo, boolean replayed, String resultJson) {
        this(voucherNo, replayed, resultJson, null);
    }
}
