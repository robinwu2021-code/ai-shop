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

/** Real TCP client -> standalone security/controller/service -> disposable MySQL, with production archive config. */
class BeautyStandaloneEndToEndIT {
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
                ((GenericApplicationContext) application).registerBean(ServiceProductFactsPort.class,
                        () -> (entity, store, sku) -> new ServiceProductFactsPort.Fact(entity, store, sku,
                                "G", 9900, 60)))
                .run("--server.port=0", "--server.address=127.0.0.1", "--shop.beauty.enabled=true",
                        "--spring.datasource.url=" + source.getUrl(), "--spring.datasource.username=root",
                        "--spring.datasource.password=", "--shop.services.internal-token=" + token,
                        "--spring.config.import=classpath:beauty-publication-archive.yml"))) {
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
            assertThat(sql.queryForList("SELECT DISTINCT created_by FROM bty_service_definition_version", String.class))
                    .containsExactly("REMOTE-ACTOR");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_action_log WHERE operator_ref='REMOTE-ACTOR'",
                    Integer.class)).isEqualTo(2);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE status='ARCHIVED'", Integer.class))
                    .isEqualTo(2);
            var unauthorized = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url
                    + BeautyPolicyInternalApi.READ_PATH)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}") ).build(), HttpResponse.BodyHandlers.discarding());
            assertThat(unauthorized.statusCode()).isEqualTo(401);
        }
    }
}
