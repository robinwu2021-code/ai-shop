package ai.neargo.shop.commerce.channel;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.secret.api.SecretRef;
import ai.neargo.secret.api.SecretVault;
import ai.neargo.shop.spi.commerce.ChannelCertificateHandleStore;
import ai.neargo.shop.spi.commerce.ChannelCertificateVaultPort;

import java.util.Arrays;
import java.util.function.Function;

/**
 * Original SecretVault is the sole decryptor; raw codes live only in a bounded in-process callback
 * and are erased afterward.
 */
public final class SecretVaultChannelCertificateAdapter implements ChannelCertificateVaultPort {
    private final SecretVault vault;
    private final ChannelCertificateHandleStore handles;

    public SecretVaultChannelCertificateAdapter(
            SecretVault vault, ChannelCertificateHandleStore handles) {
        this.vault = vault;
        this.handles = handles;
    }

    @Override
    public String capture(
            ChannelCertificateHandleStore.Scope scope, String actor, byte[] certificateCode) {
        if (certificateCode == null || certificateCode.length < 1 || certificateCode.length > 4096)
            throw new IllegalArgumentException("Invalid certificate material length");
        try {
            var handle = handles.prepare(scope, actor);
            var ref =
                    vault.seal(
                            new KeyOwner(
                                    SecretNamespace.PLATFORM,
                                    "CHANNEL_CERTIFICATE",
                                    handle.ownerId()),
                            certificateCode);
            return handles.attach(scope, handle.handleNo(), ref.blobId()).handleNo();
        } finally {
            Arrays.fill(certificateCode, (byte) 0);
        }
    }

    @Override
    public <T> T withSecret(
            ChannelCertificateHandleStore.Scope scope,
            String handleNo,
            Function<byte[], T> action) {
        var handle = handles.read(scope, handleNo);
        if (handle.encryptedBlobId() == null)
            throw new IllegalStateException(
                    "Controlled certificate has no original encrypted material");
        byte[] code = vault.open(SecretNamespace.PLATFORM, new SecretRef(handle.encryptedBlobId()));
        try {
            return action.apply(code);
        } finally {
            Arrays.fill(code, (byte) 0);
        }
    }
}
