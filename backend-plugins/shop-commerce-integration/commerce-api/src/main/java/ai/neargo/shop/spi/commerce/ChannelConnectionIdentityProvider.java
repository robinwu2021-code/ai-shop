package ai.neargo.shop.spi.commerce;

/**
 * A protocol adapter declares its immutable product profile; a callback or merchant body cannot
 * choose another adapter.
 */
public interface ChannelConnectionIdentityProvider extends ChannelConnectionIdentityPort {
    String profileNo();
}
