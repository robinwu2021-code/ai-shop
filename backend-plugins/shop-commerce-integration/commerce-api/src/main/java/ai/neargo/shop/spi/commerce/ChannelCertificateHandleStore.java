package ai.neargo.shop.spi.commerce;

/**
 * Scoped original handle metadata only. Encrypted blobs are opened exclusively by the original
 * SecretVault adapter.
 */
public interface ChannelCertificateHandleStore {
    record Scope(String entityNo, String storeNo, String connectionNo) {}

    record Handle(
            String handleNo,
            long ownerId,
            Scope scope,
            String accountNo,
            String profileNo,
            Long encryptedBlobId,
            String status) {}

    Handle prepare(Scope scope, String actor);

    Handle attach(Scope scope, String handleNo, long encryptedBlobId);

    Handle read(Scope scope, String handleNo);
}
