package ai.neargo.shop.pay.points;

import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.EvidenceRef;
import ai.neargo.shop.pay.points.model.FundBucket;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.model.Voucher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The fixtures are hand-balanced; receivables cannot silently replace actual backing. */
class VoucherInvariantTest {
    @Test
    void receivable_is_not_backing() {
        Voucher voucher = voucher(List.of(move(HolderType.MINT, Bucket.ISSUED, -100),
                move(HolderType.USER, Bucket.AVAILABLE, 100)), List.of(), List.of());
        assertThatThrownBy(voucher::validateV1ToV4).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void funded_grant_and_internal_budget_transfer_preserve_both_ledgers() {
        assertThatCode(voucher(List.of(move(HolderType.ISSUE, Bucket.ISSUABLE, -100),
                move(HolderType.USER, Bucket.PENDING, 100)),
                List.of(new FundMove(FundBucket.U, -100), new FundMove(FundBucket.C, 100)),
                List.of())::validateV1ToV4).doesNotThrowAnyException();
        assertThatCode(voucher(List.of(move(HolderType.ISSUE, Bucket.ISSUABLE, -100),
                move(HolderType.RECEIVE, Bucket.RECIRCULATABLE, 100)),
                List.of(new FundMove(FundBucket.U, -100), new FundMove(FundBucket.B, 100)),
                List.of())::validateV1ToV4).doesNotThrowAnyException();
    }

    @Test
    void unbalanced_points_wrong_currency_and_illegal_holder_bucket_are_rejected() {
        assertThatThrownBy(voucher(List.of(move(HolderType.USER, Bucket.AVAILABLE, 100)),
                List.of(new FundMove(FundBucket.C, 100)),
                List.of(new EvidenceRef("bank-1", "CNY", 100)))::validateV1ToV4)
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(voucher(List.of(), List.of(new FundMove(FundBucket.B, 100)),
                List.of(new EvidenceRef("bank-2", "USD", 100)))::validateV1ToV4)
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(voucher(List.of(move(HolderType.RECEIVE, Bucket.AVAILABLE, 100),
                move(HolderType.MINT, Bucket.ISSUED, -100)), List.of(), List.of())::validateV1ToV4)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void points_and_money_must_match_the_fixed_redemption_unit() {
        Voucher wrong = new Voucher("GRANT:S", "T", "CNY", new RedeemRate(3, 2),
                List.of(move(HolderType.ISSUE, Bucket.ISSUABLE, -4),
                        move(HolderType.USER, Bucket.PENDING, 4)),
                List.of(new FundMove(FundBucket.U, -2), new FundMove(FundBucket.C, 2)),
                List.of(), 0, List.of(), "{}");
        assertThatThrownBy(wrong::validateV1ToV4).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reserve_change_without_a_position_record_is_rejected() {
        Voucher reserveOnly = new Voucher("HOLD:S", "T", "CNY", new RedeemRate(1, 1),
                List.of(), List.of(), List.of(), 100, List.of(), "{}");
        assertThatThrownBy(reserveOnly::validateV1ToV4).isInstanceOf(IllegalArgumentException.class);
    }

    private Voucher voucher(List<PointsMove> points, List<FundMove> funds, List<EvidenceRef> evidence) {
        return new Voucher("V:1", "T", "CNY", new RedeemRate(1, 1), points, funds,
                List.of(), 0, evidence, "{}");
    }

    private PointsMove move(HolderType holder, Bucket bucket, long delta) {
        return new PointsMove(new AccountKey("T", holder, holder == HolderType.USER ? "U1" : "PLATFORM", bucket),
                delta);
    }
}
