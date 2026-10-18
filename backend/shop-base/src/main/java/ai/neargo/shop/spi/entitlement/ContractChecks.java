package ai.neargo.shop.spi.entitlement;

import java.util.Currency;
import java.util.Objects;

/** Structural validation shared by internal immutable contracts; authentication remains at the transport boundary. */
public final class ContractChecks {
    private ContractChecks() {
    }

    /** IDs are opaque and unpadded so storage equality agrees with command identity. */
    public static String id(String value) {
        require(value != null && !value.isBlank() && value.length() <= 64 && value.equals(value.strip()));
        return value;
    }

    /** Optional means absent, never an empty or padded alternate identity. */
    public static void optionalId(String value) {
        if (value != null) {
            id(value);
        }
    }

    /** Require a canonical digest representation; owners still recompute the payload digest. */
    public static void hash(String value) {
        require(value != null && value.matches("[0-9a-f]{64}"));
    }

    /** Currency must be an explicit supported ISO code, never inferred from a store. */
    public static void currency(String value) {
        require(value != null && value.matches("[A-Z]{3}"));
        Currency.getInstance(value);
    }

    /** Required references fail during deserialization before a handler can mutate state. */
    public static <T> T present(T value) {
        if (Objects.isNull(value)) {
            throw new IllegalArgumentException("Required internal contract value is missing");
        }
        return value;
    }

    /** Reject invalid combinations uniformly, without leaking supplied secrets in exception messages. */
    public static void require(boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Invalid internal entitlement contract");
        }
    }
}
