package ai.neargo.commerce.api.mall;

import ai.neargo.commerce.api.common.*;

/** List/gross and discounted deal are separate; deal equals credit plus cash in one currency. */
public record QuoteComponent(
        String componentNo,
        ComponentKind kind,
        Money listAmount,
        Money dealAmount,
        Money creditAmount,
        Money cashAmount,
        int cycleDays,
        long quantity) {
    /** Compatibility constructor retains list/gross semantics and derives the explicit deal. */
    public QuoteComponent(
            String componentNo,
            ComponentKind kind,
            Money listAmount,
            Money creditAmount,
            Money cashAmount,
            int cycleDays,
            long quantity) {
        this(
                componentNo,
                kind,
                listAmount,
                settlement(creditAmount, cashAmount),
                creditAmount,
                cashAmount,
                cycleDays,
                quantity);
    }

    private static Money settlement(Money credit, Money cash) {
        java.util.Objects.requireNonNull(credit);
        java.util.Objects.requireNonNull(cash);
        if (!java.util.Objects.equals(credit.currency(), cash.currency()))
            throw new IllegalArgumentException("settlement currency mismatch");
        return new Money(credit.currency(), Math.addExact(credit.minor(), cash.minor()));
    }
}
