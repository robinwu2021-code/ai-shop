package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.commerce.channel.mock.MockCommercePlatform;
import ai.neargo.shop.commerce.channel.mock.MockCommercePlatform.OrderSeed;
import ai.neargo.shop.commerce.channel.mock.MockCommercePlatform.Scenario;
import ai.neargo.shop.food.dto.DiningChannelSourceFacts.Key;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;

import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * References only: every original command, invoice and owner identity is read from native
 * persistence.
 */
final class FoodChannelVerticalFixture {
    record References(
            String entityNo,
            String storeNo,
            String happyOrderNo,
            String uncertainOrderNo,
            String cancelledOrderNo) {}

    private final JdbcTemplate sql;
    private final ObjectMapper json;
    private final MockCommercePlatform platform;
    private final String token;
    final References references;

    FoodChannelVerticalFixture(
            JdbcTemplate sql,
            ObjectMapper json,
            MockCommercePlatform platform,
            References references,
            String token)
            throws Exception {
        this.sql = sql;
        this.json = json;
        this.platform = platform;
        this.token = token;
        this.references = references;
        assertThat(references.entityNo()).isNotBlank();
        assertThat(references.storeNo()).isNotBlank();
        assertThat(
                        java.util.Set.of(
                                references.happyOrderNo(),
                                references.uncertainOrderNo(),
                                references.cancelledOrderNo()))
                .hasSize(3);
        for (String order :
                java.util.List.of(
                        references.happyOrderNo(),
                        references.uncertainOrderNo(),
                        references.cancelledOrderNo())) {
            var row = order(order);
            assertThat(row.get("source_owner")).isEqualTo("FOOD");
            assertThat(row.get("core_order_ref")).isNull();
            assertThat(
                            sql.queryForObject(
                                    "SELECT COUNT(*) FROM fnb_channel_order_source WHERE"
                                            + " entity_no=? AND store_no=? AND import_no=?",
                                    Integer.class,
                                    references.entityNo(),
                                    references.storeNo(),
                                    row.get("import_ref")))
                    .isZero();
        }
    }

    Map<String, Object> order(String number) {
        return sql.queryForMap(
                "SELECT * FROM cim_order_link WHERE tenant_no='MAIN' AND entity_no=?"
                        + " AND store_no=? AND channel_order_no=?",
                references.entityNo(),
                references.storeNo(),
                number);
    }

    ChannelOrderGateway.Snapshot snapshot(String number) {
        return json.readValue(
                sql.queryForObject(
                        "SELECT normalized_snapshot FROM cim_order_revision"
                                + " WHERE entity_no=? AND channel_order_no=? AND revision_no=?",
                        String.class,
                        references.entityNo(),
                        number,
                        order(number).get("active_revision_no")),
                ChannelOrderGateway.Snapshot.class);
    }

    ChannelOrderIngestPort.Command command(String number) {
        String original = (String) order(number).get("import_snapshot");
        assertThat(original)
                .as("CIM must freeze its original mapped command before owner preparation")
                .isNotBlank();
        return json.readValue(original, ChannelOrderIngestPort.Command.class);
    }

    Key key(String number) {
        var command = command(number);
        return new Key(
                command.entityNo(),
                command.storeNo(),
                command.importNo(),
                command.original().documentHash());
    }

    void scenario(String number, Scenario scenario) throws Exception {
        var original = snapshot(number);
        var profile =
                sql.queryForObject(
                        "SELECT profile_no FROM cim_store_connection WHERE entity_no=?"
                                + " AND store_no=? AND connection_no=?",
                        String.class,
                        references.entityNo(),
                        references.storeNo(),
                        order(number).get("connection_no"));
        post("/mock/v1/admin/orders", new OrderSeed(profile, original.document(), scenario));
    }

    private void post(String path, Object body) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + platform.port() + path))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                        .build();
        var response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("Original Mock administrative HTTP receipt")
                .isEqualTo(200);
    }

    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Required native fixture variable: " + name);
        return value;
    }
}
