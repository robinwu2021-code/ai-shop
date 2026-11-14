package ai.neargo.shop.commerce.port;

import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityProvider;

import java.util.List;

/**
 * Select once by frozen profile. An unavailable/mismatched adapter never falls through to a
 * different protocol.
 */
public final class ChannelConnectionIdentityRouter implements ChannelConnectionIdentityPort {
    private final List<ChannelConnectionIdentityPort> ports;

    private ChannelConnectionIdentityRouter(List<ChannelConnectionIdentityPort> ports) {
        this.ports = List.copyOf(ports);
    }

    /**
     * Null preserves the existing absence gate; a sole legacy global verifier retains source
     * compatibility.
     */
    public static ChannelConnectionIdentityPort available(
            List<ChannelConnectionIdentityPort> ports) {
        if (ports.isEmpty()) return null;
        return new ChannelConnectionIdentityRouter(ports);
    }

    @Override
    public Proof verify(Identity identity) {
        if (identity == null || identity.profileNo() == null)
            throw new IllegalArgumentException("Missing identity profile");
        var matching =
                ports.stream()
                        .filter(
                                port ->
                                        port instanceof ChannelConnectionIdentityProvider provider
                                                && identity.profileNo()
                                                        .equals(provider.profileNo()))
                        .toList();
        if (matching.size() > 1) throw new IllegalStateException("Duplicate identity profile");
        if (matching.size() == 1) return matching.getFirst().verify(identity);
        if (ports.size() == 1 && !(ports.getFirst() instanceof ChannelConnectionIdentityProvider))
            return ports.getFirst().verify(identity);
        return new Proof(Status.UNSUPPORTED, identity, null, null, null);
    }
}
