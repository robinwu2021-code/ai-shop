package ai.neargo.shop.scenario;

import ai.neargo.inventory.domain.config.InventoryDomainAutoConfiguration;
import ai.neargo.inventory.domain.service.InboundService;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.service.ReservationService;
import ai.neargo.inventory.svc.InventoryApplication;
import ai.neargo.shop.ShopApplication;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.api.biz.BizStockController;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.product.StockPort.SkuQty;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 端到端：商城用独立的进销存（ADR-046 M8）。
 *
 * <p>同一个 JVM 里起一个真实的库存服务（随机端口），与商城内嵌连<b>同一个库</b> —— 生产电商套就是这样：
 * 商城与服务都连 {@code ai_shop_inv}，过渡期按商家翻写入方。签一把 {@code SHOP} 系统凭证配给商城客户端，然后：
 * 商家在内嵌建账进货 → 装电商档案 → 经服务的翻转接口翻到服务（对账平才翻）→ 商城真实的下单锁货、付款确认、
 * 后台库存页都落在服务上，内嵌写被拒 → 翻回内嵌（回滚）后商城照常走内嵌。
 */
// classes 必须显式给：用例里内嵌的 @Configuration（库存服务的装配）否则会被当成商城上下文的配置
@SpringBootTest(classes = ShopApplication.class, properties = {
        "shop.inventory.stock-authority=INVENTORY",
        "shop.inventory.enabled=true",
        "shop.inventory.flyway-locations=classpath:db/inventory-h2",
        "shop.inventory.datasource.username=sa",
        "shop.inventory.datasource.password="})
@ActiveProfiles("test")
class InventoryStandaloneCutoverE2ETest {

    private static final String ADMIN_TOKEN = "e2e-admin-token";
    /**
     * 商城内嵌与库存服务连的同一个库。用文件库而不是内存库：商城测试会把每个上下文的 {@code jdbc:h2:mem:} 库
     * 改名隔离（{@code IsolatedH2ContextCustomizerFactory}），两边就成了两个库；文件库不改名，同一 JVM 里两个池共用。
     */
    private static final Path DB_DIR = Path.of("target", "inv-cutover-e2e-" + System.nanoTime());
    static final String INV_DB = "jdbc:h2:file:" + DB_DIR.toAbsolutePath().resolve("inv")
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
    private static ConfigurableApplicationContext service;
    private static String serviceUrl;

    @Autowired StockPort stockPort;
    @Autowired InventoryAclService acl;
    @Autowired InboundService inbound;
    @Autowired ReservationService embeddedReservations;
    @Autowired BizStockController bizStock;
    @Autowired @Qualifier("invDataSource") DataSource invDataSource;

    /**
     * 库存服务的最小装配：关掉自动装配（商城 classpath 上的安全、缓存、MyBatis 等不该进服务），
     * 只导入服务真正要的 —— 占位符解析、Web 容器、MVC、JSON、库存领域。
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({PropertyPlaceholderAutoConfiguration.class, TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            JacksonAutoConfiguration.class, InventoryDomainAutoConfiguration.class})
    static class ServiceInfra {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @DynamicPropertySource
    static void startInventoryService(DynamicPropertyRegistry registry) {
        service = new SpringApplicationBuilder(InventoryApplication.class, ServiceInfra.class)
                .properties(
                        "spring.config.name=inventory-e2e-none",
                        "spring.boot.enableautoconfiguration=false",
                        "spring.main.banner-mode=off",
                        "server.port=0",
                        "inventory.enabled=true",
                        "inventory.write-mode=SVC",
                        "inventory.datasource.url=" + INV_DB,
                        "inventory.datasource.username=sa",
                        "inventory.datasource.password=",
                        "inventory.flyway-locations=classpath:db/inventory-h2",
                        "inventory.instance.code=SHOP",
                        "inventory.instance.allowed-systems=SHOP",
                        "inventory.admin.token=" + ADMIN_TOKEN,
                        "inventory.flip.drain-millis=0")
                .run();
        serviceUrl = "http://127.0.0.1:" + service.getEnvironment().getProperty("local.server.port");
        Map<?, ?> issued = admin().post().uri("/api/inventory/admin/v1/systems/SHOP/credentials")
                .body(Map.of("name", "shop-app")).retrieve().body(Map.class);
        registry.add("shop.inventory.datasource.url", () -> INV_DB);
        registry.add("shop.inventory.service.base-url", () -> serviceUrl);
        registry.add("shop.inventory.service.app-key", () -> issued.get("appKey"));
        registry.add("shop.inventory.service.app-secret", () -> issued.get("appSecret"));
    }

    @AfterAll
    static void stopInventoryService() {
        if (service != null) {
            service.close();
        }
        org.springframework.util.FileSystemUtils.deleteRecursively(DB_DIR.toFile());
    }

    @Test
    void shopRunsOnTheStandaloneInventoryAfterTheMerchantIsFlippedAndBackAfterRollback() throws Exception {
        String merchant = "E2E" + (System.nanoTime() % 1_000_000_000L);
        String sku = "SKU-" + merchant;
        JdbcTemplate sql = new JdbcTemplate(invDataSource);

        // ① 内嵌时期：建账、进货 10
        String owner = acl.ownerIdOf(merchant);
        String item = acl.itemIdOf(merchant, sku);
        String location = acl.locationIdOf(merchant, null);
        String in = inbound.createDraft(new InboundService.Draft(owner, location, "PURCHASE", null, null, null,
                LocalDateTime.now(), null, List.of(new InboundService.Line(item, 10, null, 100L))));
        inbound.post(owner, in, "U1");

        // ② 没装档案不能翻（翻过去下单预留会全被拒）；装电商档案后翻转：对账平才翻
        assertThat(flip(owner, "SVC").getStatusCode().value()).isEqualTo(409);
        admin().put().uri("/api/inventory/admin/v1/owners/{o}/profile", owner)
                .body(Map.of("profile", new tools.jackson.databind.json.JsonMapper().readTree(
                        Files.readString(Path.of("../inventory/inventory-domain/src/test/resources/profiles/shop.json"))),
                        "reason", "cutover"))
                .retrieve().toBodilessEntity();
        assertThat(flip(owner, "SVC").getBody().get("writeAuthority")).isEqualTo("SVC");
        assertThat(acl.writeAuthorityOf(merchant)).isEqualTo("SVC");

        // ③ 商城真实的下单锁货：落在服务上（预留由服务写，内嵌直写被拒）
        String order = "ORD-" + merchant;
        stockPort.lock(order, List.of(new SkuQty(sku, 3)));
        assertThat(sql.queryForObject("select reserved from inv_stock_balance where owner_id = ? and item_id = ?",
                BigDecimal.class, owner, item)).isEqualByComparingTo("3");
        assertThat(sql.queryForObject("select actor from inv_command where owner_id = ? and command_type = ?",
                String.class, owner, "reservation.reserve")).isEqualTo("SYSTEM:order");
        assertThatThrownBy(() -> embeddedReservations.reserve(owner, "ORD-EMB-" + merchant,
                List.of(new ReservationService.Line(item, location, 1)), 900))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        // ④ 付款确认（按单号，经服务的系统级接口）：实扣 3，生成销售出库
        stockPort.confirm(order);
        assertThat(sql.queryForObject("select on_hand from inv_stock_balance where owner_id = ? and item_id = ?",
                BigDecimal.class, owner, item)).isEqualByComparingTo("7");
        assertThat(sql.queryForObject("select status from inv_reservation where owner_id = ? and external_ref = ?",
                String.class, owner, order)).isEqualTo("COMMITTED");

        // ⑤ 商家后台库存页：转发到服务，看到的是同一本账
        // 店主身份（整个商家、全部权限），判权在商城这一层照常走
        BizContext.set(new BizContext(merchant, Set.of(), Set.of(), Set.of(), null, true));
        try {
            assertThat(bizStock.itemBySku(sku).name()).isNotBlank();
            assertThat(bizStock.balances("all", null, 10)).singleElement()
                    .satisfies(b -> assertThat(b.onHand()).isEqualTo(7));
        } finally {
            BizContext.clear();
        }

        // ⑥ 回滚：翻回内嵌（同样先对账），商城照常走内嵌
        assertThat(flip(owner, "EMBEDDED").getBody().get("writeAuthority")).isEqualTo("EMBEDDED");
        stockPort.lock("ORD2-" + merchant, List.of(new SkuQty(sku, 2)));
        assertThat(sql.queryForObject("select reserved from inv_stock_balance where owner_id = ? and item_id = ?",
                BigDecimal.class, owner, item)).isEqualByComparingTo("2");
    }

    @SuppressWarnings("rawtypes")
    private static org.springframework.http.ResponseEntity<Map> flip(String owner, String to) {
        return admin().post().uri("/api/inventory/admin/v1/owners/{o}/write-authority/flip", owner)
                .body(Map.of("to", to)).retrieve()
                .onStatus(s -> s.value() == 409, (req, res) -> { })
                .toEntity(Map.class);
    }

    private static RestClient admin() {
        return RestClient.builder().baseUrl(serviceUrl)
                .defaultHeader("X-Inventory-Admin-Token", ADMIN_TOKEN)
                .defaultHeader("X-Inventory-Actor", "OPS:e2e")
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
