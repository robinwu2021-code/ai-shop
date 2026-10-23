package ai.neargo.shop.common.pricing;

import java.util.List;

/** No partially compiled config escapes when a publication prerequisite fails. */
public record CompilationResult(EffectivePricingConfig config, List<Issue> issues) {
    public CompilationResult {
        issues = List.copyOf(issues);
        if ((config == null) == issues.isEmpty()) {
            throw new IllegalArgumentException("A result must contain either a config or issues");
        }
    }

    public boolean valid() {
        return config != null;
    }

    /** Stable diagnostic identity; presentation layers translate codes instead of leaking input JSON. */
    public record Issue(String code, String path, String message) {
    }
}
