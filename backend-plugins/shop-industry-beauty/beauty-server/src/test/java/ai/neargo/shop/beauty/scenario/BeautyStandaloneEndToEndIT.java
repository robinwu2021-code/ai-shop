package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ai.neargo.shop.beauty.client.RemoteStorePolicyService;
import ai.neargo.shop.beauty.client.RemoteServiceDefinitionService;
import ai.neargo.shop.beauty.config.BeautySchemaValidator;
import ai.neargo.shop.beauty.dto.PublishStorePolicyVersionReq;
import ai.neargo.shop.beauty.dto.PublishServiceDefinitionVersionReq;
import ai.neargo.shop.beauty.dto.ServiceDefinitionQuery;
import ai.neargo.shop.beauty.dto.StorePolicyQuery;
import ai.neargo.shop.beauty.server.BeautyApplication;
import ai.neargo.shop.beauty.spi.BeautyPolicyInternalApi;
import ai.neargo.shop.beauty.spi.BeautyDefinitionInternalApi;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.product.ServiceDefinitionPort;
import ai.neargo.shop.spi.product.ServiceProductFactsPort;
import ai.neargo.shop.svc.InternalHttp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Real TCP client -> standalone security/controller/service -> disposable MySQL. */
class BeautyStandaloneEndToEndIT {
    private final InMemoryDefinitions productDefinitions = new InMemoryDefinitions();

    /** 商品域服务定义的内存桩：按操作号重放，期望版本不符冲突。 */
    static final class InMemoryDefinitions implements ServiceDefinitionPort {
        final java.util.List<Definition> versions = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.Map<String, Definition> replays = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.Set<String> actors = new java.util.concurrent.CopyOnWriteArraySet<>();
        @Override public synchronized Definition publish(Publish c) {
            var replay = c.sourceRef() == null ? null : replays.get(c.sourceRef());
            if (replay != null) { return replay; }
            if (c.expectedVersion() != versions.size()) {
                throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.CONFLICT);
            }
            var d = new Definition(c.entityNo(), c.storeNo(), "G", c.skuNo(), versions.size() + 1, false, c.timing(),
                    c.steps(), c.notes(), c.requirements().stream().map(r -> new Requirement("REQ" + versions.size(),
                    r.roleCode(), r.resourceType(), r.skillGoodsNo(), r.qty(), r.sequenceNo(), r.offsetMin(),
                    r.durationMin(), r.selectionRule())).toList(), ACTIVE, "h", java.time.Instant.now());
            versions.add(d); actors.add(c.actor());
            if (c.sourceRef() != null) { replays.put(c.sourceRef(), d); }
            return d;
        }
        @Override public Definition current(String e, String s, String sku) {
            return versions.isEmpty() ? null : versions.getLast();
        }
        @Override public Definition byVersion(String e, String s, String sku, int v) {
            return v < 1 || v > versions.size() ? null : versions.get(v - 1);
        }
        @Override public java.util.List<Definition> list(String e, String s, java.time.Instant at) {
            return versions.isEmpty() ? java.util.List.of() : java.util.List.of(versions.getLast());
        }
    }

    @Test
    void policyAndDefinitionUseSameDurableContractsAcrossStandaloneHttp() throws Exception {
        String adminUrl = System.getProperty("beauty.test.admin-url");
        assertThat(adminUrl).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mysql\\?.*");
        String database = "beauty_server_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(adminUrl, "root", "")).execute("CREATE DATABASE " + database);
        var source = new DriverManagerDataSource(adminUrl.replace("/mysql?", "/" + database + "?"), "root", "");
        new BeautySchemaValidator().initialize(source, "migrate");
        new ResourceDatabasePopulator(new FileSystemResource("../beauty-core/src/test/resources/"
                + "platform-write-fixture.sql")).execute(source);
        String token = "disposable-standalone-token";
        try (var context = assertDoesNotThrow(()
                -> new SpringApplicationBuilder(BeautyApplication.class).initializers(application ->
                {
                    ((GenericApplicationContext) application).registerBean(ServiceProductFactsPort.class,
                            () -> (entity, store, sku) -> new ServiceProductFactsPort.Fact(entity, store, sku,
                                    "G", 9900, 60));
                    // 服务定义归商品域（TDD §2.8 P5）：独立部署时它在宿主，这里换成内存桩
                    ((GenericApplicationContext) application).registerBean(ServiceDefinitionPort.class,
                            () -> productDefinitions);
                })
                .run("--server.port=0", "--server.address=127.0.0.1", "--shop.beauty.enabled=true",
                        "--spring.datasource.url=" + source.getUrl(), "--spring.datasource.username=root",
                        "--spring.datasource.password=", "--shop.services.internal-token=" + token))) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            String url = "http://127.0.0.1:" + port;
            var http = new InternalHttp(service -> Optional.of(url), token);
            var policies = new RemoteStorePolicyService(http.client("BEAUTY", BeautyPolicyInternalApi.class,
                    Duration.ofSeconds(5)));
            var definitions = new RemoteServiceDefinitionService(http.client("BEAUTY",
                    BeautyDefinitionInternalApi.class,
                    Duration.ofSeconds(5)));
            var policy = new PublishStorePolicyVersionReq(0L, OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                    new PublishStorePolicyVersionReq.Policy(7,
                            new PublishStorePolicyVersionReq.CancellationRule(60, true), Set.of("PREPAID_SINGLE")));
            var published = policies.publishPolicyVersion("E", "S", "REMOTE-ACTOR", policy, "REMOTE-P1");
            assertThat(policies.publishPolicyVersion("E", "S", "REMOTE-ACTOR", policy, "REMOTE-P1"))
                    .isEqualTo(published);
            assertThat(policies.getPolicy("E", "S", "REMOTE-ACTOR", new StorePolicyQuery("S", null))
                    .policyVersionNo()).isEqualTo(published.policyVersionNo());
            assertThatThrownBy(() -> policies.getPolicy("E", "S", "REMOTE-ACTOR", new StorePolicyQuery("OTHER", null)))
                    .isInstanceOf(BizException.class);
            var definition = new PublishServiceDefinitionVersionReq("SKU", 0L,
                    new PublishServiceDefinitionVersionReq.Duration(60, 5, 10),
                    List.of(new PublishServiceDefinitionVersionReq.Requirement("TECH", "STAFF", "G", 1, 0, 0, 60,
                            "ANY_QUALIFIED")));
            var version = definitions.publishDefinitionVersion("E", "S", "REMOTE-ACTOR", definition, "REMOTE-D1");
            assertThat(definitions.publishDefinitionVersion("E", "S", "REMOTE-ACTOR", definition, "REMOTE-D1"))
                    .isEqualTo(version);
            assertThat(definitions.listDefinitions("E", "S", "REMOTE-ACTOR",
                    new ServiceDefinitionQuery("S", null, null, 1, 20)).records()).hasSize(1);
            var sql = new JdbcTemplate(source);
            assertThat(productDefinitions.actors).containsExactly("REMOTE-ACTOR");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_action_log WHERE operator_ref='REMOTE-ACTOR'",
                    Integer.class)).isEqualTo(2);
            var unauthorized = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url
                    + BeautyPolicyInternalApi.READ_PATH)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}") ).build(), HttpResponse.BodyHandlers.discarding());
            assertThat(unauthorized.statusCode()).isEqualTo(401);
        }
    }
}
