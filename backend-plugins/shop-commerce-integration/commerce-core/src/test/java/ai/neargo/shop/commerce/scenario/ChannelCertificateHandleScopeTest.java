package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.commerce.mapper.CertificateHandleMapper;
import ai.neargo.shop.commerce.service.impl.CatalogPersistence;
import ai.neargo.shop.commerce.service.impl.ChannelCertificateHandleStoreImpl;
import ai.neargo.shop.spi.commerce.ChannelCertificateHandleStore;
import ai.neargo.shop.spi.user.MerchantQueryPort;

import org.junit.jupiter.api.Test;

import java.util.Optional;

class ChannelCertificateHandleScopeTest {
    @Test
    void
            realMerchantOwnerAndOriginalConnectionScopeBindAHandleBeforeAnyVaultMaterialCanBeAttached() {
        new CatalogSynchronizationTest()
                .runWithoutPublishing(
                        s -> {
                            var merchants = mock(MerchantQueryPort.class);
                            when(merchants.ownerUserNoOf("E")).thenReturn(Optional.of("OWNER"));
                            var store =
                                    new ChannelCertificateHandleStoreImpl(
                                            s.context().getBean(CatalogPersistence.class),
                                            s.context().getBean(CertificateHandleMapper.class),
                                            () -> merchants);
                            var scope = new ChannelCertificateHandleStore.Scope("E", "S", "CON");
                            assertThatThrownBy(() -> store.prepare(scope, "INTRUDER"))
                                    .isInstanceOf(RuntimeException.class);
                            assertThat(
                                            s.sql()
                                                    .queryForObject(
                                                            "SELECT COUNT(*) FROM"
                                                                    + " cim_certificate_handle",
                                                            Integer.class))
                                    .isZero();
                            var handle = store.prepare(scope, "OWNER");
                            assertThat(handle.ownerId()).isPositive();
                            assertThat(handle.encryptedBlobId()).isNull();
                            assertThat(store.attach(scope, handle.handleNo(), 9).status())
                                    .isEqualTo("SEALED");
                            assertThat(store.read(scope, handle.handleNo()).encryptedBlobId())
                                    .isEqualTo(9L);
                            assertThatThrownBy(
                                            () ->
                                                    store.read(
                                                            new ChannelCertificateHandleStore.Scope(
                                                                    "E", "OTHER", "CON"),
                                                            handle.handleNo()))
                                    .isInstanceOf(RuntimeException.class);
                            assertThatThrownBy(() -> store.attach(scope, handle.handleNo(), 10))
                                    .isInstanceOf(RuntimeException.class);
                            assertThat(
                                            s.sql()
                                                    .queryForObject(
                                                            "SELECT encrypted_blob_id FROM"
                                                                    + " cim_certificate_handle",
                                                            Long.class))
                                    .isEqualTo(9L);
                        });
    }
}
