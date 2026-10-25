package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Native ended-day work review. No payment execution or financial completion is implied. */
@TestPropertySource(
        properties = {
            "shop.seed.enabled=true",
            "shop.outbox.sources.platform.mode=UNIFIED",
            "shop.outbox.sources.inventory.mode=UNIFIED"
        })
class FoodOperationalCloseNativeHttpIT extends FoodEmbeddedStartupIT {
    @LocalServerPort int port;
    @Autowired TokenStore tokens;
    @Autowired ObjectMapper json;
    static final String STORE = "ST-M0001";
    final HttpClient client = HttpClient.newHttpClient();

    String key() {
        return UUID.randomUUID().toString();
    }

    String token(String account) {
        return tokens.issue(
                TokenStore.SessionData.of(LoginUser.merchant(account, "native close reviewer")));
    }

    JsonNode call(String method, String path, Object body, String token, String key)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-No", STORE)
                        .header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key);
        request.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    JsonNode post(String path, Object body, String token, String key) throws Exception {
        var result = call("POST", path, body, token, key);
        assertThat(result.path("code").asInt()).as(result.toString()).isZero();
        return result.path("data");
    }

    JsonNode get(String path, String token) throws Exception {
        var result = call("GET", path, null, token, null);
        assertThat(result.path("code").asInt()).as(result.toString()).isZero();
        return result.path("data");
    }

    @Test
    void endedDayRequiresDualActorCarryAndPreservesUnresolvedWorkIntoNextDay() throws Exception {
        String owner = token("SF-M0001");
        if (sql.queryForObject(
                        "SELECT COUNT(*) FROM fnb_store_policy WHERE store_no=?",
                        Integer.class,
                        STORE)
                == 0)
            post(
                    "/biz/x/food/stores/" + STORE + "/policies",
                    Map.of(
                            "timezone",
                            "UTC",
                            "cutoverMinute",
                            240,
                            "effectiveFrom",
                            Instant.now()
                                    .minus(7, ChronoUnit.DAYS)
                                    .truncatedTo(ChronoUnit.MILLIS)
                                    .toString(),
                            "payPolicy",
                            "POSTPAY",
                            "capabilities",
                            List.of(),
                            "version",
                            0),
                    owner,
                    key());
        var staff =
                post(
                        "/biz/staff",
                        Map.of(
                                "loginPhone",
                                "13900009901",
                                "displayName",
                                "native independent reviewer"),
                        owner,
                        key());
        String reviewerNo = staff.path("mchAccountNo").asString();
        assertThat(reviewerNo).isNotBlank();
        var role =
                post(
                        "/biz/roles",
                        Map.of(
                                "name",
                                "NF_" + key().substring(0, 6),
                                "perms",
                                List.of("biz:finance", "biz:order:view")),
                        owner,
                        key());
        post(
                "/biz/staff/" + reviewerNo + "/store",
                Map.of("storeNo", STORE, "role", role.path("roleCode").asString(), "granted", true),
                owner,
                key());
        String reviewer = token(reviewerNo);
        var date = LocalDate.now(ZoneOffset.UTC).minusDays(3);
        var nextDate = date.plusDays(1);
        var day = post("/biz/x/food/business-days/" + date + ":open", Map.of(), owner, key());
        var next = post("/biz/x/food/business-days/" + nextDate + ":open", Map.of(), owner, key());
        String source = "NC" + key().replace("-", "").substring(0, 20),
                hash = PricingSnapshotHash.of(source);
        // Historical local pending-work fixture; source owner recovery is outside this scenario.
        var occurred = date.atTime(12, 0);
        sql.update(
                "INSERT INTO"
                    + " fnb_operation_case(entity_no,store_no,case_no,source_kind,source_no,active_source,owner_no,status,source_hash,reason,created_at,created_by,updated_at,updated_by)"
                    + " VALUES(?,?,?,'PRINT_JOB',?,?,?,'OPEN',?,'historical unconfirmed"
                    + " print',?,'SF-M0001',?,'SF-M0001')",
                "M0001",
                STORE,
                source,
                source,
                hash,
                "SF-M0001",
                hash,
                occurred,
                occurred);
        var close =
                post(
                        "/biz/x/food/closes:preview",
                        Map.of("businessDate", date.toString(), "currency", "CNY"),
                        owner,
                        key());
        var sign =
                Map.of(
                        "closeNo",
                        close.path("closeNo").asString(),
                        "snapshotHash",
                        close.path("snapshotHash").asString());
        assertThat(
                        call("POST", "/biz/x/food/closes:sign", sign, reviewer, key())
                                .path("code")
                                .asInt())
                .isEqualTo(10409);
        var issues =
                get(
                        "/biz/x/food/closes/" + close.path("closeNo").asString() + "/exceptions",
                        owner);
        assertThat(issues).hasSize(1);
        var issue = issues.get(0);
        assertThat(issue.path("kind").asString()).isEqualTo("SYNC");
        String exceptionPath =
                "/biz/x/food/closes/"
                        + close.path("closeNo").asString()
                        + "/exceptions/"
                        + issue.path("exceptionNo").asString();
        String proposalKey = key();
        var proposal =
                Map.of(
                        "version",
                        issue.path("version").asLong(),
                        "ownerNo",
                        reviewerNo,
                        "nextDayNo",
                        next.path("dayNo").asString(),
                        "reason",
                        "native follow-up responsibility");
        var assigned = post(exceptionPath + ":propose-carry", proposal, owner, proposalKey);
        assertThat(post(exceptionPath + ":propose-carry", proposal, owner, proposalKey))
                .isEqualTo(assigned);
        var approve = Map.of("version", assigned.path("version").asLong());
        assertThat(
                        call("POST", exceptionPath + ":approve-carry", approve, owner, key())
                                .path("code")
                                .asInt())
                .isEqualTo(10409);
        String approvalKey = key();
        var carried = post(exceptionPath + ":approve-carry", approve, reviewer, approvalKey);
        assertThat(carried.path("status").asString()).isEqualTo("CARRIED");
        assertThat(post(exceptionPath + ":approve-carry", approve, reviewer, approvalKey))
                .isEqualTo(carried);
        assertThat(call("POST", "/biz/x/food/closes:sign", sign, owner, key()).path("code").asInt())
                .isEqualTo(10403);
        String signKey = key();
        var signed = post("/biz/x/food/closes:sign", sign, reviewer, signKey);
        assertThat(signed.path("status").asString()).isEqualTo("SIGNED");
        assertThat(signed.path("report")).isEqualTo(close.path("report"));
        assertThat(signed.path("snapshotHash")).isEqualTo(close.path("snapshotHash"));
        assertThat(post("/biz/x/food/closes:sign", sign, reviewer, signKey)).isEqualTo(signed);
        assertThat(signed.path("report").path("issues")).hasSize(1);
        var nextReport =
                get(
                        "/biz/x/food/reports/business-day?businessDate="
                                + nextDate
                                + "&currency=CNY",
                        owner);
        assertThat(nextReport.path("issues").toString())
                .contains(issue.path("exceptionNo").asString());
        assertThat(
                        sql.queryForObject(
                                "SELECT status FROM fnb_operation_case WHERE case_no=?",
                                String.class,
                                source))
                .isEqualTo("OPEN");
        assertThat(
                        sql.queryForObject(
                                "SELECT status FROM fnb_business_day WHERE day_no=?",
                                String.class,
                                day.path("dayNo").asString()))
                .isEqualTo("CLOSING");
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM fnb_close_exception WHERE exception_no=? AND"
                                        + " status='CARRIED' AND approved_by=? AND owner_no=? AND next_day_no=?",
                                Integer.class,
                                issue.path("exceptionNo").asString(),
                                reviewerNo,
                                reviewerNo,
                                next.path("dayNo").asString()))
                .isEqualTo(1);
    }
}
