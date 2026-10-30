package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.commerce.dto.SaveProductProfileReq;
import ai.neargo.shop.commerce.service.ProviderService;
import ai.neargo.shop.commerce.service.impl.ProviderServiceImpl;
import ai.neargo.shop.spi.commerce.ChannelProductProfilePort;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

class ProductProfileManagementTest {
    ChannelProductProfilePort protocol(String number) {
        return new ChannelProductProfilePort() {
            public String profileNo() {
                return number;
            }

            public Proof verifyDefinition(Definition definition) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                var now = Instant.now();
                return new Proof(
                        Status.VERIFIED,
                        definition,
                        now,
                        now.plusSeconds(20),
                        "ORIGINAL_INSTALLED_CODE");
            }
        };
    }

    private void originalJsonTextStorage(CatalogSynchronizationTest.State state) {
        // H2 native JSON encodes a bound Java String as a JSON string scalar. Original
        // MySQL/MariaDB
        // JSON columns return the stored object text to the String mapper; emulate that driver
        // boundary
        // for both the fixture seed and later mapper inserts, without accepting malformed
        // production JSON.
        state.sql()
                .execute(
                        "ALTER TABLE cim_product_profile ALTER COLUMN capability_definition"
                            + " VARCHAR(8192) NOT NULL");
        state.sql()
                .update(
                        "UPDATE cim_product_profile SET capability_definition='{}' WHERE"
                            + " profile_no='PP'");
    }

    @Test
    void plannedProfileCannotBeVerifiedByAnOperatorWithoutAnInstalledProtocol() {
        new CatalogSynchronizationTest()
                .run(
                        s -> {
                            originalJsonTextStorage(s);
                            var service = s.context().getBean(ProviderService.class);
                            var before = service.listProfiles("P", 1, 20).records().getFirst();
                            var stopped =
                                    service.saveProfile(
                                            "PP",
                                            "OPS",
                                            new SaveProductProfileReq(
                                                    before.version(), "SUSPENDED", "maintenance"),
                                            "pause");
                            assertThat(stopped.status()).isEqualTo("SUSPENDED");
                            assertThatThrownBy(
                                            () ->
                                                    service.saveProfile(
                                                            "PP",
                                                            "OPS",
                                                            new SaveProductProfileReq(
                                                                    stopped.version(),
                                                                    "VERIFIED",
                                                                    "resume"),
                                                            "resume"))
                                    .isInstanceOf(RuntimeException.class);
                            assertThat(
                                            s.sql()
                                                    .queryForObject(
                                                            "SELECT COUNT(*) FROM"
                                                                    + " cim_request_journal WHERE"
                                                                    + " scope_kind='PLATFORM' AND"
                                                                    + " entity_no IS NULL",
                                                            Integer.class))
                                    .isOne();
                        });
    }

    @Test
    void versionAndActorBoundPlatformReplayDoNotRewriteImmutableProtocolMetadata() {
        new CatalogSynchronizationTest()
                .runWith(
                        r -> r.withBean(ChannelProductProfilePort.class, () -> protocol("PP")),
                        s -> {
                            originalJsonTextStorage(s);
                            var service = s.context().getBean(ProviderService.class);
                            var req = new SaveProductProfileReq(0L, "SUSPENDED", "maintenance");
                            var first = service.saveProfile("PP", "OPS", req, "pause");
                            assertThat(service.saveProfile("PP", "OPS", req, "pause"))
                                    .isEqualTo(first);
                            assertThatThrownBy(
                                            () -> service.saveProfile("PP", "OTHER", req, "pause"))
                                    .isInstanceOf(RuntimeException.class);
                            var resumed =
                                    service.saveProfile(
                                            "PP",
                                            "OPS",
                                            new SaveProductProfileReq(
                                                    first.version(), "VERIFIED", "ready"),
                                            "resume");
                            assertThat(resumed.status()).isEqualTo("VERIFIED");
                            assertThat(service.saveProfile("PP", "OPS", req, "pause"))
                                    .isEqualTo(first);
                            assertThat(
                                            s.sql()
                                                    .queryForObject(
                                                            "SELECT protocol_code FROM"
                                                                    + " cim_product_profile WHERE"
                                                                    + " profile_no='PP'",
                                                            String.class))
                                    .isEqualTo("TEST");
                            assertThatThrownBy(
                                            () ->
                                                    service.saveProfile(
                                                            "PP",
                                                            "OPS",
                                                            new SaveProductProfileReq(
                                                                    0L, "PLANNED", "stale"),
                                                            "stale"))
                                    .isInstanceOf(RuntimeException.class);
                        });
    }

    @Test
    void explicitMockRegistrationIsIdempotentAndBootstrapCannotUndoAManualPause() {
        new CatalogSynchronizationTest()
                .runWith(
                        r ->
                                r.withBean(
                                        ChannelProductProfilePort.class,
                                        () -> protocol("MOCK_TEST")),
                        s -> {
                            originalJsonTextStorage(s);
                            var service = s.context().getBean(ProviderServiceImpl.class);
                            var definition =
                                    new ChannelProductProfilePort.Definition(
                                            "MOCK_TEST",
                                            "MOCK",
                                            "DELIVERY",
                                            "TW",
                                            "MOCK_V1",
                                            "{\"mock\":true}",
                                            1,
                                            "MOCK:TW",
                                            false,
                                            true);
                            var registration =
                                    new ChannelProductProfilePort.Registration(
                                            definition, "Mock platform");
                            var created = service.registerMock(registration, "bootstrap");
                            assertThat(service.registerMock(registration, "bootstrap"))
                                    .isEqualTo(created);
                            service.saveProfile(
                                    "MOCK_TEST",
                                    "OPS",
                                    new SaveProductProfileReq(
                                            created.version(), "SUSPENDED", "pause"),
                                    "pause");
                            service.registerMock(registration, "bootstrap");
                            service.registerMock(registration, "bootstrap-again");
                            assertThat(
                                            s.sql()
                                                    .queryForObject(
                                                            "SELECT status FROM cim_product_profile"
                                                                + " WHERE profile_no='MOCK_TEST'",
                                                            String.class))
                                    .isEqualTo("SUSPENDED");
                            assertThat(
                                            s.sql()
                                                    .queryForObject(
                                                            "SELECT COUNT(*) FROM"
                                                                    + " cim_product_profile WHERE"
                                                                    + " profile_no='MOCK_TEST'",
                                                            Integer.class))
                                    .isOne();
                        });
    }
}
