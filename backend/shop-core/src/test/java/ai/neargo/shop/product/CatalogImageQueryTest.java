package ai.neargo.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.port.GoodsCatalogQueryPortImpl;
import ai.neargo.shop.spi.media.MediaAssetQueryPort;
import ai.neargo.shop.spi.media.MediaReferenceFacts;
import ai.neargo.shop.spi.media.MediaReferenceQueryPort;
import ai.neargo.shop.spi.product.CatalogImageContracts.Expectation;
import ai.neargo.shop.spi.product.CatalogImageContracts.Query;
import ai.neargo.shop.spi.product.CatalogImageFacts;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CatalogImageQueryTest {
    static final String KEY = "E/S/goods/202610/dish.jpg";
    static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    static final Query QUERY = query("REFERENCE", "https://old.example/" + KEY);

    static Query query(String kind, String ref) {
        return new Query("E", "S", List.of(new Expectation("G", kind, ref)));
    }

    static MediaReferenceQueryPort.Facts proof(MediaReferenceQueryPort.Query q) {
        String key = q.reference().endsWith("different.jpg") ? "E/S/goods/different.jpg" : KEY;
        return MediaReferenceFacts.seal(new MediaReferenceQueryPort.Facts(1, "MEDIA_REFERENCE_IDENTITY", q, key,
                PricingSnapshotHash.of(new MediaAssetQueryPort.AssetFact(key, "E", "S", "GOODS", "ACTIVE")),
                NOW, NOW.plusSeconds(20), null));
    }

    private void run(Consumer<JdbcTemplate> duringMedia, BiConsumer<GoodsCatalogQueryPort, JdbcTemplate> action) {
        new ApplicationContextRunner().withUserConfiguration(GoodsCatalogQueryPortTest.Fixture.class)
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:image_" + UUID.randomUUID() + ";MODE=MySQL",
                        "spring.datasource.username=sa", "spring.datasource.password=")
                .run(c -> {
                    assertThat(c).hasNotFailed();
                    var sql = new JdbcTemplate(c.getBean(DataSource.class));
                    sql.execute("CREATE TABLE prd_goods(id BIGINT PRIMARY KEY,goods_no VARCHAR(32),"
                            + "entity_no VARCHAR(32),"
                            + "store_no VARCHAR(32),title VARCHAR(100),cover VARCHAR(2048),on_sale BOOLEAN,"
                            + "audit_status VARCHAR(24),version BIGINT,deleted INT DEFAULT 0)");
                    sql.update("INSERT INTO prd_goods VALUES(1,'G','E','S','dish',?,FALSE,'PENDING',3,0)", KEY);
                    MediaReferenceQueryPort media = new MediaReferenceQueryPort() {
                        @Override public Facts resolve(MediaReferenceQueryPort.Query q) {
                            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                            duringMedia.accept(sql);
                            return proof(q);
                        }
                    };
                    var port = new GoodsCatalogQueryPortImpl(c.getBean(GoodsMapper.class), c.getBean(SkuMapper.class),
                            () -> media, c.getBean(PlatformTransactionManager.class),
                            Clock.fixed(NOW, ZoneOffset.UTC));
                    action.accept(port, sql);
                });
    }

    @Test void provesOriginalCoverAndAllowsOwnerVerifiedAliasWithShortestLifetime() {
        run(sql -> { }, (port, sql) -> {
            var f = port.resolveCatalogImages(QUERY);
            CatalogImageFacts.validate(QUERY, f, NOW);
            assertThat(f.items().getFirst().assetKey()).isEqualTo(KEY);
            assertThat(f.items().getFirst().goodsVersion()).isEqualTo(3);
            assertThat(f.items().getFirst().originalRefDigest()).isEqualTo(PricingSnapshotHash.of(KEY));
            assertThat(f.validUntil()).isEqualTo(NOW.plusSeconds(20));
        });
    }

    @Test void differentFrozenAssetCannotBeReplacedByTheCurrentCover() {
        run(sql -> { }, (port, sql) -> assertThatThrownBy(() -> port.resolveCatalogImages(
                query("REFERENCE", "E/S/goods/different.jpg"))).isInstanceOf(BizException.class));
    }

    @Test void onlyActuallyAbsentOriginalCoverCanBecomeNone() {
        run(sql -> { throw new AssertionError("NONE must not call media"); }, (port, sql) -> {
            sql.update("UPDATE prd_goods SET cover=NULL");
            assertThat(port.resolveCatalogImages(query("NONE", null)).items().getFirst().assetKey()).isNull();
            assertThatThrownBy(() -> port.resolveCatalogImages(QUERY)).isInstanceOf(BizException.class);
            sql.update("UPDATE prd_goods SET cover=''");
            assertThat(port.resolveCatalogImages(query("NONE", null)).items().getFirst().coverKind()).isEqualTo("NONE");
        });
    }

    @Test void originalGoodsChangesDuringMediaVerificationAreRejected() {
        run(sql -> sql.update("UPDATE prd_goods SET version=version+1"), (port, sql) ->
                assertThatThrownBy(() -> port.resolveCatalogImages(QUERY)).isInstanceOf(BizException.class));
    }

    @Test void originalCoverChangeEvenWithoutVersionIncrementIsRejected() {
        run(sql -> sql.update("UPDATE prd_goods SET cover='E/S/goods/changed.jpg'"), (port, sql) ->
                assertThatThrownBy(() -> port.resolveCatalogImages(QUERY)).isInstanceOf(BizException.class));
    }

    @Test void unknownForeignDeletedAndPartialBatchesNeverReturnPartialFacts() {
        run(sql -> { }, (port, sql) -> {
            for (var q : List.of(new Query("X", "S", QUERY.expectations()),
                    new Query("E", "X", QUERY.expectations()), new Query("E", "S", List.of(
                            new Expectation("G", "REFERENCE", KEY), new Expectation("MISSING", "NONE", null))))) {
                assertThatThrownBy(() -> port.resolveCatalogImages(q)).isInstanceOf(BizException.class);
            }
            sql.update("UPDATE prd_goods SET deleted=1");
            assertThatThrownBy(() -> port.resolveCatalogImages(QUERY)).isInstanceOf(BizException.class);
        });
    }

    @Test void wrongExpectedKindAndOuterTransactionCannotBeUsedAsCoverProof() {
        run(sql -> { }, (port, sql) -> {
            assertThatThrownBy(() -> port.resolveCatalogImages(query("NONE", null))).isInstanceOf(BizException.class);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try { assertThatThrownBy(() -> port.resolveCatalogImages(QUERY)).isInstanceOf(BizException.class); }
            finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        });
    }
}
