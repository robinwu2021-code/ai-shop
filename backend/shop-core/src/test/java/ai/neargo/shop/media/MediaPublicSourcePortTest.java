package ai.neargo.shop.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.media.port.MediaPublicSourcePortImpl;
import ai.neargo.shop.spi.media.MediaPublicSourceFacts;
import ai.neargo.shop.spi.media.MediaPublicSourcePort;
import ai.neargo.shop.spi.media.MediaPublicSourcePort.Request;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import javax.sql.DataSource;

class MediaPublicSourcePortTest {
    static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final String KEY = "E/S/goods/a.jpg";
    static final Request REQUEST = new Request("E", "S", List.of(KEY));

    record Fixture(AssertableApplicationContext context, JdbcTemplate sql) {}

    void run(Consumer<Fixture> action) {
        new ApplicationContextRunner()
                .withUserConfiguration(MediaAssetQueryPortTest.Fixture.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:media_public_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=")
                .run(
                        c -> {
                            var sql = new JdbcTemplate(c.getBean(DataSource.class));
                            sql.execute(
                                    "CREATE TABLE sys_media_asset(id BIGINT PRIMARY KEY,asset_key"
                                            + " VARCHAR(512),entity_no VARCHAR(32),store_no"
                                            + " VARCHAR(32),biz_type VARCHAR(32),status"
                                            + " VARCHAR(32),purge_batch_no VARCHAR(32),bytes"
                                            + " BIGINT,width INT,height INT,content_type"
                                            + " VARCHAR(128),created_at DATETIME(3),updated_at"
                                            + " DATETIME(3))");
                            sql.update(
                                    "INSERT INTO sys_media_asset"
                                        + " VALUES(1,?,'E','S','GOODS','ACTIVE',NULL,1234,"
                                        + "640,480,'image/jpeg','2026-10-01"
                                        + " 00:00:00','2026-10-01 00:00:00')",
                                    KEY);
                            action.accept(new Fixture(c, sql));
                        });
    }

    MediaStore store() {
        var store = mock(MediaStore.class);
        when(store.publicUrl(anyString()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return "https://img.example/" + call.getArgument(0);
                        });
        return store;
    }

    MediaPublicSourcePortImpl port(Fixture f, MediaStore store) {
        return new MediaPublicSourcePortImpl(
                f.context().getBean(SysMediaAssetMapper.class),
                () -> store,
                f.context().getBean("transactionManager", PlatformTransactionManager.class),
                CLOCK);
    }

    @Test
    void capturesOriginalPublicMediaInsteadOfInventingAnAddress() {
        run(
                f -> {
                    var store = store();
                    var facts = port(f, store).capture(REQUEST);
                    MediaPublicSourceFacts.validate(REQUEST, facts, NOW);
                    assertThat(facts.purpose()).isEqualTo("CATALOG_PUBLIC_MEDIA");
                    var image = facts.assets().getFirst();
                    assertThat(image.assetKey()).isEqualTo(KEY);
                    assertThat(image.publicUrl()).isEqualTo("https://img.example/" + KEY);
                    assertThat(image.bytes()).isEqualTo(1234L);
                    assertThat(image.width()).isEqualTo(640);
                    assertThat(image.height()).isEqualTo(480);
                    assertThat(image.contentType()).isEqualTo("image/jpeg");
                    assertThat(facts.validUntil()).isEqualTo(NOW.plusSeconds(30));
                    verify(store).publicUrl(KEY);
                    verifyNoMoreInteractions(store);
                    var json =
                            JsonMapper.builder()
                                    .findAndAddModules()
                                    .build()
                                    .writeValueAsString(facts);
                    assertThat(json)
                            .doesNotContain(
                                    "uploadedBy",
                                    "purgeBatchNo",
                                    "secret",
                                    "createdAt",
                                    "updatedAt",
                                    "\"id\"");
                    assertThat(
                                    f.sql()
                                            .queryForObject(
                                                    "SELECT count(*) FROM sys_media_asset",
                                                    Integer.class))
                            .isOne();
                });
    }

    @Test
    void everyRequestedAssetMustBeOwnedActiveGoodsAndNotInPurge() {
        for (var mutation :
                List.of(
                        "UPDATE sys_media_asset SET entity_no='OTHER'",
                        "UPDATE sys_media_asset SET store_no='OTHER'",
                        "UPDATE sys_media_asset SET biz_type='QUAL'",
                        "UPDATE sys_media_asset SET status='PENDING'",
                        "UPDATE sys_media_asset SET status='RECLAIMABLE'",
                        "UPDATE sys_media_asset SET status='PURGED'",
                        "UPDATE sys_media_asset SET purge_batch_no='PURGE'")) {
            run(
                    f -> {
                        f.sql().update(mutation);
                        var store = store();
                        assertThatThrownBy(() -> port(f, store).capture(REQUEST))
                                .isInstanceOf(BizException.class);
                        verifyNoInteractions(store);
                    });
        }
        run(
                f -> {
                    var store = store();
                    assertThatThrownBy(
                                    () ->
                                            port(f, store)
                                                    .capture(
                                                            new Request(
                                                                    "E",
                                                                    "S",
                                                                    List.of(
                                                                            KEY,
                                                                            "E/S/goods/missing.jpg"))))
                            .isInstanceOf(BizException.class);
                    verifyNoInteractions(store);
                });
    }

    @Test
    void providerUrlMustBeStablePublicHttpsForExactlyTheRequestedKey() {
        var urls =
                List.of(
                        "/uploads/" + KEY,
                        "http://img.example/" + KEY,
                        "https://u:p@img.example/" + KEY,
                        "https://img.example/" + KEY + "?signature=secret",
                        "https://img.example/" + KEY + "#fragment",
                        "https://img.example/../" + KEY,
                        "https://img.example/wrong.jpg",
                        "https://img.example/%2E/" + KEY);
        run(
                f -> {
                    for (var url : urls) {
                        var store = store();
                        when(store.publicUrl(KEY)).thenReturn(url);
                        assertThatThrownBy(() -> port(f, store).capture(REQUEST))
                                .as(url)
                                .isInstanceOf(BizException.class);
                    }
                    f.sql().update("UPDATE sys_media_asset SET asset_key='E/S/qual/private.jpg'");
                    var store = store();
                    assertThatThrownBy(
                                    () ->
                                            port(f, store)
                                                    .capture(
                                                            new Request(
                                                                    "E",
                                                                    "S",
                                                                    List.of(
                                                                            "E/S/qual/private.jpg"))))
                            .isInstanceOf(BizException.class);
                    verifyNoInteractions(store);
                });
    }

    @Test
    void changesWhileResolvingOriginalStorageAreRejected() {
        for (var mutation :
                List.of(
                        "UPDATE sys_media_asset SET bytes=4321",
                        "UPDATE sys_media_asset SET width=800",
                        "UPDATE sys_media_asset SET entity_no='OTHER'",
                        "UPDATE sys_media_asset SET status='RECLAIMABLE'",
                        "UPDATE sys_media_asset SET purge_batch_no='PENDING_PURGE'"))
            run(
                    f -> {
                        var store = store();
                        when(store.publicUrl(KEY))
                                .thenAnswer(
                                        call -> {
                                            f.sql().update(mutation);
                                            return "https://img.example/" + KEY;
                                        });
                        assertThatThrownBy(() -> port(f, store).capture(REQUEST))
                                .as(mutation)
                                .isInstanceOf(BizException.class);
                    });
    }

    @Test
    void metadataRevisionIsStableAcrossAuditRefreshAndUnknownValuesStayUnknown() {
        run(
                f -> {
                    var first = port(f, store()).capture(REQUEST);
                    f.sql().update("UPDATE sys_media_asset SET updated_at='2026-10-02 00:00:00'");
                    var refreshed = port(f, store()).capture(REQUEST);
                    assertThat(refreshed.assets().getFirst().sourceRevision())
                            .isEqualTo(first.assets().getFirst().sourceRevision());
                    f.sql()
                            .update(
                                    "UPDATE sys_media_asset SET"
                                            + " width=NULL,height=NULL,content_type=NULL");
                    var unknown = port(f, store()).capture(REQUEST).assets().getFirst();
                    assertThat(unknown.width()).isNull();
                    assertThat(unknown.height()).isNull();
                    assertThat(unknown.contentType()).isNull();
                    assertThat(unknown.sourceRevision())
                            .isNotEqualTo(first.assets().getFirst().sourceRevision());
                    f.sql()
                            .update(
                                    "INSERT INTO sys_media_asset SELECT"
                                        + " 2,'E/S/goods/b.jpg',entity_no,store_no,"
                                        + "biz_type,status,purge_batch_no,bytes,width,"
                                        + "height,content_type,created_at,updated_at"
                                        + " FROM sys_media_asset WHERE id=1");
                    var ordered = new Request("E", "S", List.of("E/S/goods/b.jpg", KEY));
                    assertThat(port(f, store()).capture(ordered).assets())
                            .extracting(a -> a.assetKey())
                            .containsExactlyElementsOf(ordered.assetKeys());
                });
    }

    @Test
    void malformedRequestMetadataOrExpiredProofNeverBecomesAnEmptySuccess() {
        run(
                f -> {
                    assertThatThrownBy(() -> port(f, null).capture(REQUEST))
                            .isInstanceOf(BizException.class);
                    var tx =
                            new TransactionTemplate(
                                    f.context()
                                            .getBean(
                                                    "transactionManager",
                                                    PlatformTransactionManager.class));
                    tx.executeWithoutResult(
                            t ->
                                    assertThatThrownBy(() -> port(f, store()).capture(REQUEST))
                                            .isInstanceOf(BizException.class));
                    for (var request :
                            List.of(
                                    new Request("E", "S", List.of(KEY, KEY)),
                                    new Request("E", "S", List.of()),
                                    new Request(" E", "S", List.of(KEY)),
                                    new Request("E", "S", List.of("../private.jpg"))))
                        assertThatThrownBy(() -> port(f, store()).capture(request))
                                .isInstanceOf(BizException.class);
                    var facts = port(f, store()).capture(REQUEST);
                    assertThatThrownBy(
                                    () ->
                                            MediaPublicSourceFacts.validate(
                                                    REQUEST, facts, NOW.plusSeconds(30)))
                            .isInstanceOf(IllegalStateException.class);
                    f.sql().update("UPDATE sys_media_asset SET bytes=0");
                    assertThatThrownBy(() -> port(f, store()).capture(REQUEST))
                            .isInstanceOf(BizException.class);
                });
    }

    @Test
    void boundedCallsAndSlowCaptureNeverExtendTheEvidenceBudget() {
        run(
                f -> {
                    var keys =
                            IntStream.range(0, 21)
                                    .mapToObj(n -> "E/S/goods/" + n + ".jpg")
                                    .toList();
                    assertThatThrownBy(() -> port(f, store()).capture(new Request("E", "S", keys)))
                            .isInstanceOf(BizException.class);
                    var clock = mock(Clock.class);
                    when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(31));
                    var source =
                            new MediaPublicSourcePortImpl(
                                    f.context().getBean(SysMediaAssetMapper.class),
                                    this::store,
                                    f.context()
                                            .getBean(
                                                    "transactionManager",
                                                    PlatformTransactionManager.class),
                                    clock);
                    assertThatThrownBy(() -> source.capture(REQUEST))
                            .isInstanceOf(BizException.class);
                });
    }

    @Test
    void originalComponentWiresItsOptionalStorageOwnerWithoutInstallingChannelCode() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        MediaAssetQueryPortTest.Fixture.class, MediaPublicSourcePortImpl.class)
                .withBean(MediaStore.class, this::store)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:media_component_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=")
                .run(c -> assertThat(c).hasNotFailed().hasSingleBean(MediaPublicSourcePort.class));
    }
}
