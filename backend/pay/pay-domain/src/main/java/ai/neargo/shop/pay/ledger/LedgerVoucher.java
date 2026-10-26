package ai.neargo.shop.pay.ledger;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 一张凭证与其分录（代码清单 §2.3）。<b>构造即校验</b>：借贷平衡、金额为正、单币种、
 * 平台外备查账不与平台账户同票（AC18）。造得出来的凭证一定是能入账的凭证。
 *
 * @param idemKey 幂等键：{@code voucher_type|ref_type|ref_id[|序号]}，同一资金事件只记一次
 */
public record LedgerVoucher(String voucherType, String refType, Long refId, String idemKey, String market,
                            String currency, LocalDate bizDate, String fundsPath, String sourceType, Long sourceId,
                            String memo, List<Line> lines) {

    public enum Direction { DR, CR }

    public record Line(LedgerAccount account, Direction direction, long amountMinor, Long storeId,
                       Long payMerchantId, String payChannel, Long payOrderId) {

        public Line {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(direction, "direction");
            if (amountMinor <= 0) {
                throw new IllegalArgumentException("LEDGER_AMOUNT_NOT_POSITIVE: " + account + " " + amountMinor);
            }
            Objects.requireNonNull(storeId, "storeId");
        }
    }

    public LedgerVoucher {
        Objects.requireNonNull(voucherType, "voucherType");
        Objects.requireNonNull(refType, "refType");
        Objects.requireNonNull(idemKey, "idemKey");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(bizDate, "bizDate");
        lines = List.copyOf(lines);
        if (lines.size() < 2) {
            throw new IllegalArgumentException("LEDGER_VOUCHER_NEEDS_TWO_LINES");
        }
        long dr = lines.stream().filter(l -> l.direction() == Direction.DR).mapToLong(Line::amountMinor).sum();
        long cr = lines.stream().filter(l -> l.direction() == Direction.CR).mapToLong(Line::amountMinor).sum();
        if (dr != cr) {
            throw new IllegalArgumentException("LEDGER_UNBALANCED: DR " + dr + " ≠ CR " + cr);
        }
        boolean off = lines.stream().anyMatch(l -> l.account().offPlatform());
        boolean platform = lines.stream().anyMatch(l -> l.account().platform());
        if (off && platform) {
            throw new IllegalArgumentException("LEDGER_OFF_PLATFORM_MIXED: 平台外备查账与平台账户同票");
        }
    }

    public long total() {
        return lines.stream().filter(l -> l.direction() == Direction.DR).mapToLong(Line::amountMinor).sum();
    }
}
