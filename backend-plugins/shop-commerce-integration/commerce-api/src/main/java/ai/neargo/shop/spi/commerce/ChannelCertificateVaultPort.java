package ai.neargo.shop.spi.commerce;

import java.util.function.Function;

/**
 * In-process secret boundary: no public endpoint, idempotency journal or returned fact contains a
 * certificate code.
 */
public interface ChannelCertificateVaultPort {
    String capture(ChannelCertificateHandleStore.Scope scope, String actor, byte[] certificateCode);

    <T> T withSecret(
            ChannelCertificateHandleStore.Scope scope, String handleNo, Function<byte[], T> action);
}
