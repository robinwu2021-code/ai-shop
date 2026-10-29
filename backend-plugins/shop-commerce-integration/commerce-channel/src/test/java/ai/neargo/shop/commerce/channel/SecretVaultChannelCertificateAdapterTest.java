package ai.neargo.shop.commerce.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.secret.api.SecretRef;
import ai.neargo.secret.api.SecretVault;
import ai.neargo.shop.spi.commerce.ChannelCertificateHandleStore;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

class SecretVaultChannelCertificateAdapterTest {
    final SecretVault vault = mock(SecretVault.class);
    final ChannelCertificateHandleStore handles = mock(ChannelCertificateHandleStore.class);
    final ChannelCertificateHandleStore.Scope scope =
            new ChannelCertificateHandleStore.Scope("E", "S", "CON");
    final SecretVaultChannelCertificateAdapter adapter =
            new SecretVaultChannelCertificateAdapter(vault, handles);

    @Test
    void captureSealsWithTheActualScopedHandleOwnerAndErasesRawInputWithoutAJournal() {
        byte[] code = "PRIVATE-CERTIFICATE".getBytes(StandardCharsets.UTF_8);
        var pending =
                new ChannelCertificateHandleStore.Handle(
                        "HANDLE", 77, scope, "ACCOUNT", "MOCK_PROFILE", null, "AWAITING_SEAL");
        when(handles.prepare(scope, "OWNER")).thenReturn(pending);
        when(vault.seal(new KeyOwner(SecretNamespace.PLATFORM, "CHANNEL_CERTIFICATE", 77), code))
                .thenReturn(new SecretRef(9));
        when(handles.attach(scope, "HANDLE", 9))
                .thenReturn(
                        new ChannelCertificateHandleStore.Handle(
                                "HANDLE", 77, scope, "ACCOUNT", "MOCK_PROFILE", 9L, "SEALED"));
        assertThat(adapter.capture(scope, "OWNER", code)).isEqualTo("HANDLE");
        assertThat(code).containsOnly((byte) 0);
        verify(handles).attach(scope, "HANDLE", 9);
    }

    @Test
    void openUsesOriginalScopeAndErasesTransientMaterialAndCrossStoreFailsBeforeDecrypt() {
        var handle =
                new ChannelCertificateHandleStore.Handle(
                        "HANDLE", 77, scope, "ACCOUNT", "MOCK_PROFILE", 9L, "SEALED");
        when(handles.read(scope, "HANDLE")).thenReturn(handle);
        byte[] clear = {1, 2, 3};
        when(vault.open(SecretNamespace.PLATFORM, new SecretRef(9))).thenReturn(clear);
        Integer length = adapter.withSecret(scope, "HANDLE", bytes -> bytes.length);
        assertThat(length).isEqualTo(3);
        assertThat(clear).containsOnly((byte) 0);
        var foreign = new ChannelCertificateHandleStore.Scope("E", "OTHER", "CON");
        when(handles.read(foreign, "HANDLE"))
                .thenThrow(new IllegalArgumentException("Scope mismatch"));
        assertThatThrownBy(() -> adapter.withSecret(foreign, "HANDLE", bytes -> bytes.length))
                .isInstanceOf(RuntimeException.class);
    }
}
