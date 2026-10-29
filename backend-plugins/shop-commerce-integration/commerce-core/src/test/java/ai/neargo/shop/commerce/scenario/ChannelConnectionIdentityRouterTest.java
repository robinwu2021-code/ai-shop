package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.commerce.port.ChannelConnectionIdentityRouter;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityProvider;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class ChannelConnectionIdentityRouterTest {
    final ChannelConnectionIdentityPort.Identity identity =
            new ChannelConnectionIdentityPort.Identity(
                    "E", "S", "ACCOUNT", "P1", "EA", "ES", "NAMESPACE", false, 0, 0);

    ChannelConnectionIdentityProvider provider(String profile, AtomicInteger calls) {
        return new ChannelConnectionIdentityProvider() {
            public String profileNo() {
                return profile;
            }

            public Proof verify(Identity value) {
                calls.incrementAndGet();
                return new Proof(Status.UNKNOWN, value, null, null, null);
            }
        };
    }

    @Test
    void routesOnlyTheFrozenProfileAndDoesNotTryAnotherAdapterOnUnknown() {
        var first = new AtomicInteger();
        var second = new AtomicInteger();
        var router =
                ChannelConnectionIdentityRouter.available(
                        List.of(provider("P1", first), provider("P2", second)));
        assertThat(router.verify(identity).status())
                .isEqualTo(ChannelConnectionIdentityPort.Status.UNKNOWN);
        assertThat(first.get()).isOne();
        assertThat(second.get()).isZero();
    }

    @Test
    void absentDuplicateAndMismatchedProfilesDoNotPickTheFirstAdapter() {
        assertThat(ChannelConnectionIdentityRouter.available(List.of())).isNull();
        var p = provider("P1", new AtomicInteger());
        assertThatThrownBy(
                        () ->
                                ChannelConnectionIdentityRouter.available(List.of(p, p))
                                        .verify(identity))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        ChannelConnectionIdentityRouter.available(
                                        List.of(provider("P2", new AtomicInteger())))
                                .verify(identity)
                                .status())
                .isEqualTo(ChannelConnectionIdentityPort.Status.UNSUPPORTED);
    }
}
