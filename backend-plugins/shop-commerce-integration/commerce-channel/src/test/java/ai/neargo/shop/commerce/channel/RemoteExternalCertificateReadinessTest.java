package ai.neargo.shop.commerce.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.spi.entitlement.ExternalCertificateConsumptionInternalApi;
import ai.neargo.shop.spi.entitlement.ExternalCertificateConsumptionPort;

import org.junit.jupiter.api.Test;

class RemoteExternalCertificateReadinessTest {
    final ExternalCertificateConsumptionInternalApi api =
            mock(ExternalCertificateConsumptionInternalApi.class);
    final RemoteExternalCertificateConsumptionPort remote =
            new RemoteExternalCertificateConsumptionPort(() -> api);
    final ExternalCertificateConsumptionPort.Scope scope =
            new ExternalCertificateConsumptionPort.Scope("MAIN", "E", "S");

    @Test
    void genuineUnsupportedSourceStaysTypedWithoutAnInventedAuthorization() {
        when(api.reversalReadiness(any()))
                .thenReturn(
                        new ExternalCertificateConsumptionPort.ReversalReadiness(
                                "UNSUPPORTED", "ORIGINAL_REVERSAL_SOURCE_UNAVAILABLE", null));
        var result = remote.reversalReadiness(scope, "ORIGINAL_USE", "ACTUAL_RETURN");
        assertThat(result.status()).isEqualTo("UNSUPPORTED");
        assertThat(result.authorization()).isNull();
    }

    @Test
    void missingReadyProofAndTransportFailureCannotBecomeSourceAuthorizationOrUnsupportedFacts() {
        when(api.reversalReadiness(any()))
                .thenReturn(
                        new ExternalCertificateConsumptionPort.ReversalReadiness(
                                "READY", null, null));
        assertThatThrownBy(() -> remote.reversalReadiness(scope, "ORIGINAL_USE", "ACTUAL_RETURN"))
                .isInstanceOf(RuntimeException.class);
        when(api.reversalReadiness(any()))
                .thenThrow(new IllegalStateException("Original transport unavailable"));
        assertThatThrownBy(() -> remote.reversalReadiness(scope, "ORIGINAL_USE", "ACTUAL_RETURN"))
                .isInstanceOf(RuntimeException.class);
    }
}
