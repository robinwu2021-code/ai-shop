package ai.neargo.shop.channel.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.channel.media.port.MediaReferenceQueryPortImpl;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.media.MediaStore;
import ai.neargo.shop.spi.media.MediaAssetQueryPort;
import ai.neargo.shop.spi.media.MediaReferenceFacts;
import ai.neargo.shop.spi.media.MediaReferenceQueryPort.Query;
import ai.neargo.shop.spi.media.MediaReferenceQueryPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class MediaReferenceQueryPortTest {
    private static final String KEY = "E/S/goods/202610/dish.jpg";
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final MediaStore store = mock(MediaStore.class);
    private final MediaAssetQueryPort.AssetFact owner =
            new MediaAssetQueryPort.AssetFact(KEY, "E", "S", "GOODS", "ACTIVE");
    private final MediaAssetQueryPort ledger = (e, s, keys) -> List.of(owner);

    private MediaReferenceQueryPortImpl resolver(MediaAssetQueryPort source) {
        when(store.publicUrl(anyString())).thenAnswer(inv -> "https://img.example.com/" + inv.getArgument(0));
        return new MediaReferenceQueryPortImpl(() -> store, () -> source,
                "https://img.example.com", "https://cdn.example.com", CLOCK);
    }

    @Test void normalSpringWiringRetainsOptionalOwnersAndUsesOnlyConfiguredAliases() {
        resolver(ledger);
        new ApplicationContextRunner().withUserConfiguration(MediaReferenceQueryPortImpl.class)
                .withBean(MediaStore.class, () -> store).withBean(MediaAssetQueryPort.class, () -> ledger)
                .withPropertyValues("shop.cos.domain=https://img.example.com",
                        "shop.media.direct-base-url=https://cdn.example.com")
                .run(c -> {
                    assertThat(c).hasNotFailed();
                    assertThat(c.getBean(MediaReferenceQueryPort.class)
                            .resolve(new Query("E", "S", "https://cdn.example.com/" + KEY)).assetKey()).isEqualTo(KEY);
                });
        new ApplicationContextRunner().withUserConfiguration(MediaReferenceQueryPortImpl.class).run(c -> {
            assertThat(c).hasNotFailed();
            assertThatThrownBy(() -> c.getBean(MediaReferenceQueryPort.class).resolve(new Query("E", "S", KEY)))
                    .isInstanceOf(BizException.class);
        });
    }

    @Test void internalImageFactsRemainOutsideThePublicMediaRewriteRegistration() {
        var filter = new MediaDeliveryConfig().mediaHostRewriteFilter("direct", "https://cdn.example.com",
                "https://img.example.com");
        assertThat(filter.getUrlPatterns()).containsExactly("/mp/*", "/biz/*", "/ops/*");
    }

    @Test void resolvesOnlyOriginalExportsAndExplicitAliasesToSameIdentity() {
        var service = resolver(ledger);
        String hash = null;
        for (String ref : List.of(KEY, "https://img.example.com/" + KEY, "https://cdn.example.com/" + KEY)) {
            var query = new Query("E", "S", ref);
            var facts = service.resolve(query);
            MediaReferenceFacts.validate(query, facts, NOW);
            assertThat(facts.assetKey()).isEqualTo(KEY);
            assertThat(facts.validUntil()).isEqualTo(NOW.plusSeconds(30));
            if (hash != null) assertThat(facts.assetIdentityHash()).isEqualTo(hash);
            hash = facts.assetIdentityHash();
        }
    }

    @Test void arbitraryHostsSignedEncodedPrivateAndAmbiguousReferencesAreRejected() {
        var service = resolver(ledger);
        for (String ref : List.of("https://evil.example/" + KEY, "https://img.example.com.evil/" + KEY,
                "https://img.example.com@evil.example/" + KEY, "http://img.example.com/" + KEY,
                "https://img.example.com:444/" + KEY, "https://img.example.com/" + KEY + "?sign=secret",
                "https://img.example.com/" + KEY + "#x", "https://img.example.com/%45/S/goods/a.jpg",
                "https://img.example.com/x/../" + KEY, "//img.example.com/" + KEY,
                "E/S/license/a.jpg", "[\"" + KEY + "\"]", "/uploads/" + KEY)) {
            assertThatThrownBy(() -> service.resolve(new Query("E", "S", ref)))
                    .as(ref).isInstanceOf(BizException.class);
        }
        var ambiguous = new MediaReferenceQueryPortImpl(() -> store, () -> ledger,
                "https://img.example.com", "https://img.example.com/E/S/goods", CLOCK);
        assertThatThrownBy(() -> ambiguous.resolve(new Query("E", "S", "https://img.example.com/" + KEY)))
                .isInstanceOf(BizException.class);
    }

    @Test void actualLocalExportResolvesWithoutInventingAnExternalUrl() {
        var local = new LocalDiskMediaStore("./target/test-uploads", "/uploads", "/media", "test-secret");
        var service = new MediaReferenceQueryPortImpl(() -> local, () -> ledger, "", "", CLOCK);
        var facts = service.resolve(new Query("E", "S", local.publicUrl(KEY)));
        assertThat(facts.assetKey()).isEqualTo(KEY);
        assertThatThrownBy(() -> service.resolve(new Query("E", "S", "https://unknown.test" + local.publicUrl(KEY))))
                .isInstanceOf(BizException.class);
    }

    @Test void ledgerScopeClassificationAndStatusCannotBeClaimedByAUrl() {
        for (var wrong : List.of(new MediaAssetQueryPort.AssetFact(KEY, "X", "S", "GOODS", "ACTIVE"),
                new MediaAssetQueryPort.AssetFact(KEY, "E", "X", "GOODS", "ACTIVE"),
                new MediaAssetQueryPort.AssetFact(KEY, "E", "S", "QUAL", "ACTIVE"),
                new MediaAssetQueryPort.AssetFact(KEY, "E", "S", "GOODS", "DELETED"))) {
            var service = resolver((e, s, keys) -> List.of(wrong));
            assertThatThrownBy(() -> service.resolve(new Query("E", "S", KEY))).isInstanceOf(BizException.class);
        }
        assertThatThrownBy(() -> resolver((e, s, keys) -> List.of()).resolve(new Query("E", "S", KEY)))
                .isInstanceOf(BizException.class);
    }

    @Test void originalLedgerIsRecheckedOutsideTransactions() {
        var count = new AtomicInteger();
        var service = resolver((e, s, keys) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return count.incrementAndGet() == 1 ? List.of(owner) : List.of();
        });
        assertThatThrownBy(() -> service.resolve(new Query("E", "S", KEY))).isInstanceOf(BizException.class);
        assertThat(count.get()).isEqualTo(2);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(() -> resolver(ledger).resolve(new Query("E", "S", KEY)))
                .isInstanceOf(BizException.class); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }

    @Test void expiredMissingOrMisconfiguredOriginalOwnersFailClosed() {
        var none = new MediaReferenceQueryPortImpl(() -> null, () -> ledger, "", "", CLOCK);
        assertThatThrownBy(() -> none.resolve(new Query("E", "S", KEY))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new MediaReferenceQueryPortImpl(() -> store, () -> ledger,
                "https://img.example.com/?bad", "", CLOCK)).isInstanceOf(BizException.class);
        var facts = resolver(ledger).resolve(new Query("E", "S", KEY));
        assertThatThrownBy(() -> MediaReferenceFacts.validate(facts.query(), facts, NOW.plusSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
    }
}
