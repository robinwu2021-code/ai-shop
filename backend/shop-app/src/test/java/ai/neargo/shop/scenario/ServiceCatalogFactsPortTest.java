package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.product.service.StoreSalesCatalogService;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import java.time.OffsetDateTime;
import java.util.List;
import ai.neargo.shop.spi.product.ServiceCatalogFactsPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Real owner beans and SQL, without directory/product/price test doubles; H2 is not native deployment proof. */
@SpringBootTest(properties = "shop.catalog.publication.archive-only=true")
@ActiveProfiles("test")
class ServiceCatalogFactsPortTest {
    @Autowired
    private ServiceCatalogFactsPort catalog;
    @Autowired
    private JdbcTemplate sql;
    @Autowired
    private StoreSalesCatalogService sales;
    @BeforeEach
    void seed() {
        sql.update("INSERT INTO prd_goods(goods_no,entity_no,title,cover,type,duration_min,on_sale,audit_status,"
                + "store_no,created_at,updated_at) VALUES('BM01-G','M0001','护理项目','cover','SERVICE',60,1,"
                + "'APPROVED','ST-M0001',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        sql.update("INSERT INTO prd_sku(sku_no,goods_no,entity_no,market,price,created_at,updated_at) "
                + "VALUES('BM01-SKU','BM01-G','M0001','CN',9900,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        sql.update("INSERT INTO prd_store_goods(store_no,goods_no,entity_no,on_sale) "
                + "VALUES('ST-M0001','BM01-G','M0001',1)");
    }
    @AfterEach
    void clean() {
        sql.update("DELETE FROM prd_store_catalog_version WHERE store_no='ST-M0001'");
        sql.update("DELETE FROM prd_store_catalog WHERE store_no='ST-M0001'");
        sql.update("DELETE FROM sys_idempotent WHERE idem_key LIKE 'BM01-CATALOG-%'");
        sql.update("DELETE FROM prd_store_goods WHERE goods_no='BM01-G'");
        sql.update("DELETE FROM prd_sku WHERE sku_no='BM01-SKU'");
        sql.update("DELETE FROM prd_goods WHERE goods_no='BM01-G'");
    }
    @Test
    void realDirectoryProductAndQuoteOwnersSupplyPublicFacts() {
        var store = catalog.store("ST-M0001");
        var service = catalog.service("ST-M0001", "BM01-SKU");
        assertThat(store.entityNo()).isEqualTo("M0001");
        assertThat(service.store()).isEqualTo(store);
        assertThat(service.priceMinor()).isEqualTo(9900);
        assertThat(service.title()).isEqualTo("护理项目");
        assertThat(service.durationMin()).isEqualTo(60);
        assertThat(service.goodsRevision()).isZero();
        assertThat(service.skuRevision()).isZero();
        sql.update("UPDATE prd_goods SET version=7 WHERE goods_no='BM01-G'");
        sql.update("UPDATE prd_sku SET version=9 WHERE sku_no='BM01-SKU'");
        var versioned = catalog.service("ST-M0001", "BM01-SKU");
        assertThat(versioned.goodsRevision()).isEqualTo(7);
        assertThat(versioned.skuRevision()).isEqualTo(9);
        var legacy = new ServiceCatalogFactsPort.Service(store, "SKU", "G", "title", null, 1, 1);
        assertThat(legacy.goodsRevision()).isNull();
        assertThat(legacy.skuRevision()).isNull();
        sql.update("UPDATE prd_sku SET price=12900 WHERE sku_no='BM01-SKU'");
        assertThat(catalog.service("ST-M0001", "BM01-SKU").priceMinor()).isEqualTo(12900);
    }
    @Test
    void configuredHiddenGroupCannotLeakThroughDirectSkuLookup() {
        var definition = new StoreSalesCatalogPort.PublishReq(0L, OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                "Asia/Shanghai", List.of(new StoreSalesCatalogPort.Group("HIDDEN", "hidden", 0, false)),
                List.of(new StoreSalesCatalogPort.Entry("BM01-SKU", "HIDDEN", 0, true,
                        StoreSalesCatalogPort.SalesMode.UNRESTRICTED, List.of())));
        // Archive opt-in is explicit; this owner test otherwise uses the normal platform configuration.
        sales.publish("M0001", "ST-M0001", "BM01", definition, "BM01-CATALOG-HIDDEN");
        assertThatThrownBy(() -> catalog.service("ST-M0001", "BM01-SKU")).isInstanceOf(BizException.class);
    }
    @Test
    void foreignStoreNonServiceOffShelfAndOtherMarketAreRejected() {
        assertThatThrownBy(() -> catalog.service("ST-UNKNOWN", "BM01-SKU")).isInstanceOf(BizException.class);
        sql.update("UPDATE prd_goods SET type='FRESH' WHERE goods_no='BM01-G'");
        assertThatThrownBy(() -> catalog.service("ST-M0001", "BM01-SKU")).isInstanceOf(BizException.class);
        sql.update("UPDATE prd_goods SET type='SERVICE' WHERE goods_no='BM01-G'");
        sql.update("UPDATE prd_store_goods SET on_sale=0 WHERE goods_no='BM01-G'");
        assertThatThrownBy(() -> catalog.service("ST-M0001", "BM01-SKU")).isInstanceOf(BizException.class);
        sql.update("UPDATE prd_store_goods SET on_sale=1 WHERE goods_no='BM01-G'");
        sql.update("UPDATE prd_sku SET market='OTHER' WHERE sku_no='BM01-SKU'");
        assertThatThrownBy(() -> catalog.service("ST-M0001", "BM01-SKU")).isInstanceOf(BizException.class);
    }
}
