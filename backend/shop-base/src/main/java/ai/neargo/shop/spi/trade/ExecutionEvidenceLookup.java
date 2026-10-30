package ai.neargo.shop.spi.trade;

import java.util.Objects;

/** Machine reads accept only a validated complete immutable reference. */
public record ExecutionEvidenceLookup(ExecutionReference reference) {
    public ExecutionEvidenceLookup {
        Objects.requireNonNull(reference, "reference").validate();
    }
}
