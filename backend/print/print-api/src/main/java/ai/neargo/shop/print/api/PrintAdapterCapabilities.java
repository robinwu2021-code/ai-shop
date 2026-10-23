package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintAdapterCapabilities(
        String adapterCode,
        boolean submitIdempotency,
        boolean lookupByDispatchKey,
        boolean queryJob,
        boolean cancelJob,
        Set<Integer> paperWidthsMm,
        Set<PrintEvidenceLevel> evidenceLevels) {
    public PrintAdapterCapabilities {
        paperWidthsMm = Set.copyOf(Objects.requireNonNull(paperWidthsMm, "paperWidthsMm"));
        evidenceLevels = Set.copyOf(Objects.requireNonNull(evidenceLevels, "evidenceLevels"));
    }
}
