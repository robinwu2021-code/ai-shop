package ai.neargo.shop.pay.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.pay.ledger.LedgerVoucher.Direction;
import ai.neargo.shop.pay.ledger.LedgerVoucher.Line;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 凭证构造即校验（TDD-支付服务-I3支付单退款流水 AC5）：造得出来的凭证一定能入账 */
class LedgerVoucherTest {

    private static Line line(LedgerAccount a, Direction d, long amount) {
        return new Line(a, d, amount, 1L, null, "TEST", 9L);
    }

    private static LedgerVoucher voucher(List<Line> lines) {
        return new LedgerVoucher("PAY_SUCCEEDED", "PAY_ORDER", 9L, "k", "CN", "CNY", LocalDate.of(2026, 10, 25),
                "AGGREGATED", "INTERNAL", 1L, null, lines);
    }

    @Test
    @DisplayName("★★★ 借贷不平 → 造不出来")
    void unbalancedRejected() {
        assertThatThrownBy(() -> voucher(List.of(line(LedgerAccount.PLATFORM_CHANNEL, Direction.DR, 100),
                line(LedgerAccount.MERCHANT_PAYABLE, Direction.CR, 99))))
                .hasMessageContaining("LEDGER_UNBALANCED");
    }

    @Test
    @DisplayName("★★★ 平台外备查账与平台账户同票 → 造不出来（AC18：一笔 DIRECT 的钱被当成了平台的钱）")
    void offPlatformMixedWithPlatformRejected() {
        assertThatThrownBy(() -> voucher(List.of(line(LedgerAccount.MERCHANT_CHANNEL, Direction.DR, 100),
                line(LedgerAccount.PLATFORM_CHANNEL, Direction.CR, 100))))
                .hasMessageContaining("LEDGER_OFF_PLATFORM_MIXED");
    }

    @Test
    @DisplayName("★★ 金额必须为正（方向看 DR / CR，不看正负号）；少于两行不成凭证")
    void positiveAmountsAndTwoLines() {
        assertThatThrownBy(() -> line(LedgerAccount.PLATFORM_CHANNEL, Direction.DR, 0))
                .hasMessageContaining("LEDGER_AMOUNT_NOT_POSITIVE");
        assertThatThrownBy(() -> voucher(List.of(line(LedgerAccount.PLATFORM_CHANNEL, Direction.DR, 100))))
                .hasMessageContaining("LEDGER_VOUCHER_NEEDS_TWO_LINES");
    }

    @Test
    @DisplayName("★ 平衡的凭证造得出来，合计 = 借方")
    void balancedVoucher() {
        assertThat(voucher(List.of(line(LedgerAccount.PLATFORM_CHANNEL, Direction.DR, 100),
                line(LedgerAccount.MERCHANT_PAYABLE, Direction.CR, 100))).total()).isEqualTo(100);
    }
}
