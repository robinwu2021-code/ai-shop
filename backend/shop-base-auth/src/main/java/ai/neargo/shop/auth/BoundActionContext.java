package ai.neargo.shop.auth;

import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Function;
import java.util.function.Supplier;

/** Verified transport purpose is distinct from current business permission. */
public final class BoundActionContext {
    private static final ThreadLocal<Verified> CURRENT = new ThreadLocal<>();

    private BoundActionContext() {}

    /** Whether a token- and route-validated bound operation is active on this thread. */
    public static boolean present() {
        return CURRENT.get() != null;
    }

    /** Require the complete reconstructed request, including purpose and private payload. */
    public static void require(InternalCall<?> expected) {
        var current = CURRENT.get();
        if (current == null
                || expected == null
                || !current.binding().equals(expected.binding())
                || !MessageDigest.isEqual(
                        current.digest().getBytes(StandardCharsets.UTF_8),
                        current.digester().apply(expected).getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("verified bound action required");
        }
    }

    // Only the authenticated route lifecycle may install evidence. Payloads and tokens are not
    // retained.
    static <T> T call(
            InternalCall<?> original,
            Function<InternalCall<?>, String> digester,
            Supplier<T> operation) {
        var previous = CURRENT.get();
        CURRENT.set(new Verified(original.binding(), digester.apply(original), digester));
        try {
            return operation.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    private record Verified(
            InternalRequestBinding binding,
            String digest,
            Function<InternalCall<?>, String> digester) {}
}
