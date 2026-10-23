package ai.neargo.shop.common.pricing;

import java.math.BigDecimal;
import java.util.Objects;

/** Source-owned half-open quantity scope; matching a scope never proves ownership or eligibility. */
public record PricingPortion(String sourceLineNo, String componentRef, BigDecimal start, BigDecimal end) {
    public PricingPortion {
        requireIdentity(sourceLineNo, 64);
        if (componentRef != null) {
            requireIdentity(componentRef, 64);
        }
        start = quantity(start);
        end = quantity(end);
        if (start.compareTo(end) >= 0) {
            throw new IllegalArgumentException("Positive quantity scope required");
        }
    }

    public static PricingPortion from(PricingUnit unit) {
        return new PricingPortion(unit.sourceLineNo(), unit.componentRef(), unit.quantityStart(), unit.quantityEnd());
    }

    public boolean sameComponent(PricingPortion other) {
        return sourceLineNo.equals(other.sourceLineNo) && Objects.equals(componentRef, other.componentRef);
    }

    public boolean overlaps(PricingPortion other) {
        return sameComponent(other) && start.compareTo(other.end) < 0 && other.start.compareTo(end) < 0;
    }

    public boolean contains(PricingPortion other) {
        return sameComponent(other) && start.compareTo(other.start) <= 0 && end.compareTo(other.end) >= 0;
    }

    static void requireIdentity(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.codePoints().anyMatch(c -> Character.isWhitespace(c)
                        || Character.isSpaceChar(c) || Character.isISOControl(c))) {
            throw new IllegalArgumentException("Canonical business identity required");
        }
    }

    private static BigDecimal quantity(BigDecimal value) {
        BigDecimal result = Objects.requireNonNull(value, "Quantity required").stripTrailingZeros();
        if (result.signum() < 0 || result.scale() > 6 || (long) result.precision() - result.scale() > 14) {
            throw new IllegalArgumentException("Quantity must fit DECIMAL(20,6) without rounding");
        }
        return result.scale() < 0 ? result.setScale(0) : result;
    }
}
