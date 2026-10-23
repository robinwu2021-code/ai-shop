package ai.neargo.shop.common.pricing;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One authoritative priced goods/component portion, using a half-open quantity interval.
 *
 * <p>The source owner supplies price revisions, amounts, and invoice-line identity. A coupon or
 * AA split cannot invent another invoice line merely to change tax rounding. Construction
 * validates structure, not source authenticity or a provider's fractional-quantity capability.
 */
public record PricingUnit(
        String sourceLineNo,
        String componentRef,
        String goodsNo,
        String skuNo,
        String priceRevision,
        String taxInvoiceLineRef,
        BigDecimal quantityStart,
        BigDecimal quantityEnd,
        MoneyMinor unitPrice,
        MoneyMinor baseAmount) {

    public PricingUnit {
        requireIdentity(sourceLineNo, 64);
        if (componentRef != null) {
            requireIdentity(componentRef, 64);
        }
        requireIdentity(goodsNo, 32);
        requireIdentity(skuNo, 32);
        requireIdentity(priceRevision, 64);
        requireIdentity(taxInvoiceLineRef, 64);
        quantityStart = canonicalQuantity(quantityStart);
        quantityEnd = canonicalQuantity(quantityEnd);
        if (quantityStart.compareTo(quantityEnd) >= 0) {
            throw new IllegalArgumentException("A priced portion must have a positive quantity interval");
        }
        Objects.requireNonNull(unitPrice, "Source unit price is required").requireNonNegative().requireJsonSafe();
        Objects.requireNonNull(baseAmount, "Source amount is required").requireNonNegative().requireJsonSafe();
        if (!unitPrice.currency().equals(baseAmount.currency())) {
            throw new IllegalArgumentException("Source price currencies must match");
        }
    }

    /** Returns the exact covered quantity; source-rounded baseAmount is never recomputed here. */
    public BigDecimal quantity() {
        return quantityEnd.subtract(quantityStart);
    }

    private static BigDecimal canonicalQuantity(BigDecimal value) {
        Objects.requireNonNull(value, "Quantity is required");
        BigDecimal normalized = value.stripTrailingZeros();
        long integerDigits = (long) normalized.precision() - normalized.scale();
        if (normalized.signum() < 0 || normalized.scale() > 6 || integerDigits > 14) {
            throw new IllegalArgumentException("Quantity must fit DECIMAL(20,6) without rounding");
        }
        return normalized.scale() < 0 ? normalized.setScale(0) : normalized;
    }

    private static void requireIdentity(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.codePoints().anyMatch(c -> Character.isWhitespace(c)
                        || Character.isSpaceChar(c) || Character.isISOControl(c))) {
            throw new IllegalArgumentException("A canonical source identity is required");
        }
    }
}
