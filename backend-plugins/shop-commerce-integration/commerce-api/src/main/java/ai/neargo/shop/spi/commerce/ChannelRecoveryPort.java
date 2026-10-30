package ai.neargo.shop.spi.commerce;

/**
 * Bounded durable workflows share the single existing commerce worker; no platform-specific
 * scheduler is needed.
 */
public interface ChannelRecoveryPort {
    String workflow();

    int recoverDue(int limit);
}
