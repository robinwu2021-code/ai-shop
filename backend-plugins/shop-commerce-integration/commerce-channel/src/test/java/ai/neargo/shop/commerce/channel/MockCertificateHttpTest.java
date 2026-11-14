package ai.neargo.shop.commerce.channel;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.commerce.channel.mock.MockCommerceGateway;
import ai.neargo.shop.commerce.channel.mock.MockCommercePlatform;
import ai.neargo.shop.spi.commerce.ChannelAuthorizationGateway;
import ai.neargo.shop.spi.commerce.ChannelCertificateHandleStore;
import ai.neargo.shop.spi.commerce.ChannelCertificateVaultPort;
import ai.neargo.shop.spi.commerce.ChannelRedemptionGateway;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

class MockCertificateHttpTest {
    @TempDir Path directory;
    final MockCommercePlatformTest fixture = new MockCommercePlatformTest();
    static final String PRIVATE_CODE = "PRIVATE-CERTIFICATE-NOT-PERSISTED";

    MockCommercePlatform server() throws Exception {
        fixture.directory = directory;
        return fixture.server();
    }

    record Setup(
            MockCommerceGateway gateway,
            ChannelRedemptionGateway.Scope scope,
            ChannelRedemptionGateway.Certificate certificate) {}

    Setup setup(MockCommercePlatform server, MockCommercePlatform.Scenario scenario)
            throws Exception {
        fixture.post(
                server,
                "admin/fixtures",
                new MockCommercePlatform.Fixture(
                        "E",
                        "S",
                        "MOCK_PROFILE",
                        "EA",
                        "ES",
                        "MOCK:TW",
                        Set.of("CATALOG", "REDEMPTION"),
                        MockCommercePlatform.Scenario.SUCCESS));
        var gateway = fixture.gateway(server);
        var credential = fixture.authorize(gateway);
        var catalog = fixture.publication("CERTIFICATE_CATALOG", credential);
        var published = gateway.publish(catalog);
        var sku =
                published.items().stream()
                        .filter(item -> "SKU".equals(item.objectType()))
                        .findFirst()
                        .orElseThrow();
        var offer =
                published.items().stream()
                        .filter(item -> "OFFER".equals(item.objectType()))
                        .findFirst()
                        .orElseThrow();
        var identity =
                new ChannelResourceIdentityGateway.Identity(
                        sku.externalRef(),
                        List.of(
                                new ChannelResourceIdentityGateway.Parent(
                                        ChannelResourceIdentityGateway.ObjectType.OFFER,
                                        offer.externalRef())));
        var scope =
                new ChannelRedemptionGateway.Scope(
                        "E",
                        "S",
                        catalog.connectionNo(),
                        "MOCK_PROFILE",
                        "EA",
                        "ES",
                        "CR",
                        "AUTH",
                        credential);
        gateway.bindCertificateVault(
                () ->
                        new ChannelCertificateVaultPort() {
                            public String capture(
                                    ChannelCertificateHandleStore.Scope s,
                                    String actor,
                                    byte[] code) {
                                throw new UnsupportedOperationException(
                                        "Fixture handles are already controlled");
                            }

                            public <T> T withSecret(
                                    ChannelCertificateHandleStore.Scope s,
                                    String handle,
                                    Function<byte[], T> work) {
                                if (!s.equals(
                                                new ChannelCertificateHandleStore.Scope(
                                                        "E", "S", catalog.connectionNo()))
                                        || !"HANDLE".equals(handle))
                                    throw new IllegalArgumentException("Fixture handle scope");
                                byte[] bytes = PRIVATE_CODE.getBytes(StandardCharsets.UTF_8);
                                try {
                                    return work.apply(bytes);
                                } finally {
                                    Arrays.fill(bytes, (byte) 0);
                                }
                            }
                        });
        var now = Instant.now();
        var certificate =
                new ChannelRedemptionGateway.Certificate(
                        MockCommerceGateway.certificateIdentity(
                                MockCommercePlatformTest.TOKEN,
                                "MOCK_PROFILE",
                                "EA",
                                PRIVATE_CODE.getBytes(StandardCharsets.UTF_8)),
                        identity,
                        null,
                        new BigDecimal("5"),
                        BigDecimal.ZERO,
                        new BigDecimal("5"),
                        "KG",
                        now.plusSeconds(3600),
                        now.plusSeconds(29),
                        "ORIGINAL_CERTIFICATE");
        fixture.post(
                server,
                "admin/certificates",
                new MockCommercePlatform.CertificateSeed(
                        "E", "MOCK_PROFILE", "EA", Set.of("ES"), certificate, scenario));
        return new Setup(gateway, scope, certificate);
    }

    ChannelRedemptionGateway.Action action(
            String number,
            Setup setup,
            ChannelRedemptionGateway.Verified verified,
            String quantity,
            String source,
            String original) {
        return new ChannelRedemptionGateway.Action(
                number,
                setup.scope(),
                "PREVIEW",
                verified.operationNo(),
                verified.certificate().certificateIdentityHash(),
                source,
                "b".repeat(64),
                new BigDecimal(quantity),
                "KG",
                verified.certificate().originalReceiptRef(),
                original);
    }

    @Test
    void
            uncertainOriginalIssuerConsumptionSurvivesRestartAndRevocationWithoutAnotherWriteOrCodeRead()
                    throws Exception {
        ChannelRedemptionGateway.Action original;
        try (var server = server()) {
            var setup = setup(server, MockCommercePlatform.Scenario.PERSIST_THEN_TIMEOUT);
            var verified =
                    setup.gateway()
                            .verifyCertificate(
                                    new ChannelRedemptionGateway.Verify(
                                            "VERIFY", setup.scope(), "HANDLE"));
            assertThat(verified.status()).isEqualTo(ChannelRedemptionGateway.Status.CONFIRMED);
            original = action("REDEEM", setup, verified, "1.25", "ORIGINAL_SOURCE", null);
            assertThat(setup.gateway().redeemCertificate(original).status())
                    .isEqualTo(ChannelRedemptionGateway.Status.UNKNOWN);
            setup.gateway()
                    .revoke(
                            new ChannelAuthorizationGateway.RevocationContext(
                                    "REVOKE",
                                    "E",
                                    "A",
                                    "MOCK_PROFILE",
                                    "EA",
                                    "AUTH",
                                    setup.scope().credentialRef()));
        }
        try (var server = server()) {
            var gateway = fixture.gateway(server);
            var result = gateway.queryRedemption(original);
            assertThat(result.status()).isEqualTo(ChannelRedemptionGateway.Status.CONFIRMED);
            assertThat(result.appliedQuantity()).isEqualByComparingTo("1.25");
            assertThat(result.cumulativeQuantity()).isEqualByComparingTo("1.25");
            assertThat(gateway.queryRedemption(original)).isEqualTo(result);
            try (var files = Files.list(directory)) {
                for (var path : files.filter(Files::isRegularFile).toList())
                    assertThat(Files.readString(path)).doesNotContain(PRIVATE_CODE);
            }
        }
    }

    @Test
    void originalIssuerBalancesRemainExactAcrossDistinctUsesAndOneOriginalReversal()
            throws Exception {
        try (var server = server()) {
            var setup = setup(server, MockCommercePlatform.Scenario.SUCCESS);
            var gateway = setup.gateway();
            var verified =
                    gateway.verifyCertificate(
                            new ChannelRedemptionGateway.Verify(
                                    "VERIFY1", setup.scope(), "HANDLE"));
            var first = action("USE1", setup, verified, "1.25", "SOURCE1", null);
            assertThat(gateway.redeemCertificate(first).cumulativeQuantity())
                    .isEqualByComparingTo("1.25");
            var secondProof =
                    gateway.verifyCertificate(
                            new ChannelRedemptionGateway.Verify(
                                    "VERIFY2", setup.scope(), "HANDLE"));
            assertThat(secondProof.certificate().remainingQuantity()).isEqualByComparingTo("3.75");
            assertThat(
                            gateway.redeemCertificate(
                                            action(
                                                    "USE2",
                                                    setup,
                                                    secondProof,
                                                    "2.5",
                                                    "SOURCE2",
                                                    null))
                                    .cumulativeQuantity())
                    .isEqualByComparingTo("3.75");
            var reverse =
                    action(
                            "REVERSE",
                            setup,
                            verified,
                            "1.25",
                            "ACTUAL_RETURN_AUTH",
                            first.operationNo());
            assertThat(gateway.reverseCertificate(reverse).status())
                    .isEqualTo(ChannelRedemptionGateway.Status.CONFIRMED);
            assertThat(gateway.queryReversal(reverse).appliedQuantity())
                    .isEqualByComparingTo("1.25");
            var finalProof =
                    gateway.verifyCertificate(
                            new ChannelRedemptionGateway.Verify(
                                    "VERIFY3", setup.scope(), "HANDLE"));
            assertThat(finalProof.certificate().consumedQuantity()).isEqualByComparingTo("2.5");
            assertThat(finalProof.certificate().remainingQuantity()).isEqualByComparingTo("2.5");
        }
    }
}
