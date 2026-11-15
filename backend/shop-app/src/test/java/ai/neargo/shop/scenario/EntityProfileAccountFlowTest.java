package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import ai.neargo.shop.merchant.service.EntityRegistrationProjection;
import ai.neargo.shop.merchant.service.EntityRegistrationProjection.Target;
import ai.neargo.shop.merchantbridge.EntityRegistrationProjectionCoordinator;
import ai.neargo.shop.payclient.PayEntityProfileApi;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;

@SpringBootTest(
    properties = {
      "shop.pay.entity-profiles.enabled=true",
      "shop.pay.entity-profiles.projection-delay=3600000"
    })
class EntityProfileAccountFlowTest extends PlatformEntityCreationFlowTest {
  @MockitoBean PayEntityProfileApi pay;

  @BeforeEach
  void noProfileWorkspaceYet() {
    when(pay.summaries(any())).thenReturn(Map.of());
  }

  @Test
  void ownerCanMaintainNoStoreEntityAndClientCannotChooseSourceOrActor() throws Exception {
    String token = login();
    String no =
        create(token, "workspace", "NearGo")
            .path("data")
            .path("entity")
            .path("entityNo")
            .asString();
    when(pay.read(eq(no), anyString())).thenReturn(Map.of("entityNo", no, "version", 0));
    var read =
        json.readTree(
            mvc()
                .perform(
                    get("/biz/entities/" + no + "/profile-draft")
                        .header("Authorization", "Bearer " + token))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(read.path("code").asInt()).as(read.toString()).isZero();
    when(pay.save(eq(no), anyMap())).thenReturn(Map.of("entityNo", no, "version", 1));
    var saved =
        json.readTree(
            mvc()
                .perform(
                    post("/biz/entities/" + no + "/profile-draft")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"requestNo\":\"r1\",\"expectedVersion\":0,\"schemaVersion\":\"TW_ENTITY_1\",\"sourceScope\":\"ATTACK\",\"actorRef\":\"OTHER\",\"fields\":{\"name\":\"NearGo\"},\"documents\":{}}"))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(saved.path("code").asInt()).as(saved.toString()).isZero();
    saved =
        json.readTree(
            mvc()
                .perform(
                    post("/biz/entities/" + no + "/profile-draft")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"requestNo\":\"r1\",\"expectedVersion\":0,\"schemaVersion\":\"TW_ENTITY_1\",\"fields\":{\"name\":\"NearGo\"},\"documents\":{}}"))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(saved.path("code").asInt()).as(saved.toString()).isZero();
    var body = ArgumentCaptor.forClass(Map.class);
    verify(pay, times(2)).save(eq(no), body.capture());
    assertThat(body.getValue().get("sourceScope")).isNotEqualTo("ATTACK");
    assertThat(body.getValue().get("actorRef")).isNotEqualTo("OTHER");
    when(pay.save(eq(no), anyMap()))
        .thenThrow(
            new ServiceCallException(CallOutcome.REMOTE_ERROR, "PAY", 409, "fixture conflict"));
    var conflict =
        json.readTree(
            mvc()
                .perform(
                    post("/biz/entities/" + no + "/profile-draft")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"requestNo\":\"stale\",\"expectedVersion\":0,\"schemaVersion\":\"TW_ENTITY_1\",\"fields\":{},\"documents\":{}}"))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(conflict.path("code").asInt()).as(conflict.toString()).isEqualTo(10409);
    clearInvocations(pay);
    var denied =
        json.readTree(
            mvc()
                .perform(
                    get("/biz/entities/" + no + "/profile-draft")
                        .header("Authorization", "Bearer " + login()))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(denied.path("code").asInt()).as(denied.toString()).isEqualTo(10403);
    verifyNoInteractions(pay);
  }

  @Test
  void committedEntityDoesNotMaskPendingProfileInitializationAndOriginalInputIsBound()
      throws Exception {
    String token = login();
    String body =
        "{\"requestNo\":\"init-recovery\",\"name\":\"NearGo\",\"legalForm\":\"ENTERPRISE\",\"profileFields\":{\"taxNo\":\"12345678\",\"contactName\":\"Fixture\",\"contactMobile\":\"0912345678\",\"contactEmail\":\"fixture@example.com\"}}";
    when(pay.save(anyString(), anyMap())).thenThrow(new ResourceAccessException("fixture timeout"));
    var failed =
        json.readTree(
            mvc()
                .perform(
                    post("/biz/entities")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(failed.path("code").asInt()).isNotZero();
    when(pay.result(anyString(), anyString(), anyString(), anyString()))
        .thenThrow(
            new ServiceCallException(CallOutcome.REMOTE_ERROR, "PAY", 404, "fixture missing"));
    var recovered =
        json.readTree(
            mvc()
                .perform(
                    get("/biz/entities/create-results/init-recovery")
                        .header("Authorization", "Bearer " + token))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(recovered.path("code").asInt()).as(recovered.toString()).isZero();
    assertThat(recovered.path("data").path("profileInitialization").asString())
        .isEqualTo("PENDING");
    String no = recovered.path("data").path("entity").path("entityNo").asString();
    when(pay.save(eq(no), anyMap())).thenReturn(Map.of("entityNo", no, "version", 1));
    var complete =
        json.readTree(
            mvc()
                .perform(
                    post("/biz/entities")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(complete.path("code").asInt()).as(complete.toString()).isZero();
    assertThat(complete.path("data").path("entity").path("entityNo").asString()).isEqualTo(no);
    assertThat(complete.path("data").path("profileInitialization").asString())
        .isEqualTo("COMPLETE");
    var changed =
        json.readTree(
            mvc()
                .perform(
                    post("/biz/entities")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("Fixture", "Other")))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(changed.path("code").asInt()).isEqualTo(10409);
    assertThat(
            jdbc.queryForMap(
                    "SELECT initial_profile_hash,profile_request_no FROM"
                        + " mch_entity_creation_request WHERE entity_no=?",
                    no)
                .toString())
        .doesNotContain("Fixture", "fixture@example.com", "0912345678");
  }

  @Autowired EntityRegistrationProjection registration;
  @Autowired EntityRegistrationProjectionCoordinator projector;

  @Test
  void approvedRegistrationProjectsOnlyReviewedFactsAndDoesNotCreateOperationalResources()
      throws Exception {
    String token = login();
    String no =
        create(token, "projection", "NearGo")
            .path("data")
            .path("entity")
            .path("entityNo")
            .asString();
    String tenant =
        jdbc.queryForObject("SELECT tenant_no FROM mch_entity WHERE entity_no=?", String.class, no);
    jdbc.update("UPDATE mch_entity SET market='TW' WHERE entity_no=?", no);
    registration.observe(tenant, no, "TEST-U", "submit-r1");
    when(pay.approvedRegistration(eq(no), anyString()))
        .thenReturn(
            Map.of(
                "available",
                true,
                "entityNo",
                no,
                "market",
                "TW",
                "revision",
                1,
                "revisionRef",
                "PM1-R1",
                "name",
                "Reviewed NearGo",
                "legalForm",
                "ENTERPRISE",
                "registrationNo",
                "12345678",
                "evidenceRef",
                "file:entity-profile/" + "a".repeat(32)));
    when(pay.result(eq(no), eq("submit-r1"), anyString(), anyString()))
        .thenReturn(Map.of("submittedProfileVersion", 1, "submittedRevisionNo", "PM1-R1"));
    var target = new Target(tenant, no, "TEST-U", "submit-r1", 1);
    projector.project(target);
    projector.project(target);
    assertThat(
            jdbc.queryForMap(
                "SELECT status,verified,biz_qualification FROM mch_entity WHERE entity_no=?", no))
        .containsEntry("status", "ACTIVE")
        .containsEntry("biz_qualification", "REGISTERED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM mch_qualification WHERE entity_no=? AND"
                    + " qual_type='BUSINESS_LICENSE' AND qual_number='12345678'",
                Integer.class,
                no))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM mch_store WHERE entity_no=?", Integer.class, no))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM mch_payment_merchant WHERE entity_no=?", Integer.class, no))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM mch_entity_plan WHERE entity_no=?", Integer.class, no))
        .isEqualTo(1);
    when(pay.summaries(any()))
        .thenReturn(
            Map.of(
                no,
                Map.of(
                    "platformReview",
                    "REJECTED",
                    "version",
                    5,
                    "approvedVersion",
                    1,
                    "merchantNo",
                    "PM1")));
    var listed =
        json.readTree(
            mvc()
                .perform(get("/biz/entities").header("Authorization", "Bearer " + token))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(listed.path("data").get(0).path("review").asString()).isEqualTo("REJECTED");
    assertThat(listed.path("data").get(0).path("status").asString()).isEqualTo("ACTIVE");
    jdbc.update("UPDATE mch_entity SET status='SUSPENDED' WHERE entity_no=?", no);
    when(pay.approvedRegistration(eq(no), anyString()))
        .thenReturn(
            Map.of(
                "available",
                true,
                "entityNo",
                no,
                "market",
                "TW",
                "revision",
                2,
                "revisionRef",
                "PM1-R2",
                "name",
                "Revised NearGo",
                "legalForm",
                "ENTERPRISE",
                "registrationNo",
                "12345678",
                "evidenceRef",
                "file:entity-profile/" + "b".repeat(32)));
    registration.observe(tenant, no, "TEST-U", "submit-r2");
    when(pay.result(eq(no), eq("submit-r2"), anyString(), anyString()))
        .thenReturn(Map.of("submittedProfileVersion", 2, "submittedRevisionNo", "PM1-R2"));
    projector.project(new Target(tenant, no, "TEST-U", "submit-r2", 2));
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM mch_entity WHERE entity_no=?", String.class, no))
        .isEqualTo("SUSPENDED");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM mch_entity_profile_projection WHERE entity_no=?",
                String.class,
                no))
        .isEqualTo("BLOCKED");
  }

  Map<String, Object> registrationFacts(String entity, int version, String name) {
    return Map.of(
        "available",
        true,
        "entityNo",
        entity,
        "market",
        "TW",
        "revision",
        version,
        "revisionRef",
        "PM-R" + version,
        "name",
        name,
        "legalForm",
        "ENTERPRISE",
        "registrationNo",
        "12345678",
        "evidenceRef",
        "file:entity-profile/" + "a".repeat(32));
  }

  @Test
  void oldApprovalCannotConsumeNewerObservedSubmissionOrRaceItsGeneration() throws Exception {
    String token = login();
    String no =
        create(token, "projection-generation", "NearGo")
            .path("data")
            .path("entity")
            .path("entityNo")
            .asString();
    String tenant =
        jdbc.queryForObject("SELECT tenant_no FROM mch_entity WHERE entity_no=?", String.class, no);
    jdbc.update("UPDATE mch_entity SET market='TW' WHERE entity_no=?", no);
    registration.observe(tenant, no, "U", "r1");
    var old = new Target(tenant, no, "U", "r1", 1);
    registration.observe(tenant, no, "U", "r2");
    var latest = new Target(tenant, no, "U", "r2", 2);
    when(pay.result(eq(no), eq("r1"), anyString(), anyString()))
        .thenReturn(Map.of("submittedProfileVersion", 1, "submittedRevisionNo", "PM-R1"));
    when(pay.result(eq(no), eq("r2"), anyString(), anyString()))
        .thenReturn(Map.of("submittedProfileVersion", 2, "submittedRevisionNo", "PM-R2"));
    when(pay.approvedRegistration(eq(no), anyString()))
        .thenReturn(registrationFacts(no, 1, "Approved R1"));
    projector.project(old);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM mch_entity WHERE entity_no=?", String.class, no))
        .isEqualTo("PENDING_LICENSE");
    projector.project(latest);
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM mch_entity_profile_projection WHERE entity_no=?",
                String.class,
                no))
        .isEqualTo("WAITING");
    when(pay.approvedRegistration(eq(no), anyString()))
        .thenReturn(registrationFacts(no, 2, "Approved R2"));
    projector.project(latest);
    assertThat(
            jdbc.queryForObject("SELECT name FROM mch_entity WHERE entity_no=?", String.class, no))
        .isEqualTo("Approved R2");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM mch_entity_profile_projection WHERE entity_no=?",
                String.class,
                no))
        .isEqualTo("APPLIED");
  }

  @Test
  void expiredIdentityAndRevokedRegistrationCannotBeSilentlyReplaced() throws Exception {
    for (String status : List.of("EXPIRED", "REVOKED")) {
      String token = login();
      String no =
          create(token, "history-" + status, "NearGo")
              .path("data")
              .path("entity")
              .path("entityNo")
              .asString();
      String tenant =
          jdbc.queryForObject(
              "SELECT tenant_no FROM mch_entity WHERE entity_no=?", String.class, no);
      jdbc.update("UPDATE mch_entity SET market='TW' WHERE entity_no=?", no);
      var now = Timestamp.valueOf(LocalDateTime.now());
      jdbc.update(
          "INSERT INTO"
              + " mch_qualification(tenant_no,qual_no,entity_no,qual_type,qual_name,qual_number,status,created_at,updated_at)"
              + " VALUES(?,?,?,'BUSINESS_LICENSE','Historical',?,?,?,?)",
          tenant,
          "OLD-" + no,
          no,
          status.equals("EXPIRED") ? "87654321" : "12345678",
          status,
          now,
          now);
      registration.observe(tenant, no, "U", "r1");
      when(pay.result(eq(no), anyString(), anyString(), anyString()))
          .thenReturn(Map.of("submittedProfileVersion", 1, "submittedRevisionNo", "PM-R1"));
      when(pay.approvedRegistration(eq(no), anyString()))
          .thenReturn(registrationFacts(no, 1, "Different registration"));
      projector.project(new Target(tenant, no, "U", "r1", 1));
      assertThat(
              jdbc.queryForObject(
                  "SELECT state FROM mch_entity_profile_projection WHERE entity_no=?",
                  String.class,
                  no))
          .isEqualTo("BLOCKED");
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM mch_qualification WHERE entity_no=? AND status='VALID'",
                  Integer.class,
                  no))
          .isZero();
    }
  }

  @Test
  void rejectedNewAttemptStillProjectsPreviouslyApprovedEffectiveRegistration() throws Exception {
    String token = login();
    String no =
        create(token, "rejected-attempt", "NearGo")
            .path("data")
            .path("entity")
            .path("entityNo")
            .asString();
    String tenant =
        jdbc.queryForObject("SELECT tenant_no FROM mch_entity WHERE entity_no=?", String.class, no);
    jdbc.update("UPDATE mch_entity SET market='TW' WHERE entity_no=?", no);
    registration.observe(tenant, no, "U", "accepted");
    registration.observe(tenant, no, "U", "stale-attempt");
    when(pay.result(eq(no), eq("stale-attempt"), anyString(), anyString()))
        .thenThrow(
            new ServiceCallException(
                CallOutcome.REMOTE_ERROR, "PAY", 404, "fixture missing rejected command"));
    when(pay.approvedRegistration(eq(no), anyString()))
        .thenReturn(registrationFacts(no, 1, "Effective approved R1"));
    projector.project(new Target(tenant, no, "U", "stale-attempt", 2));
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM mch_entity WHERE entity_no=?", String.class, no))
        .isEqualTo("ACTIVE");
    assertThat(
            jdbc.queryForObject(
                "SELECT approved_revision FROM mch_entity_profile_projection WHERE entity_no=?",
                Long.class,
                no))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM mch_entity_profile_projection WHERE entity_no=?",
                String.class,
                no))
        .isEqualTo("WAITING");
  }
}
