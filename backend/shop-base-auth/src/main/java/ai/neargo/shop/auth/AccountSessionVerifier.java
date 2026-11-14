package ai.neargo.shop.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

public interface AccountSessionVerifier {
    /** Runs local member/governance reads against the same fresh snapshot as the account. */
    <T> Optional<T> withFresh(
            String bearer, Instant now, Function<AccountSessionVerification, T> read);

    default Optional<AccountSessionVerification> verifyFresh(String bearer, Instant now) {
        return withFresh(bearer, now, Function.identity());
    }
}
