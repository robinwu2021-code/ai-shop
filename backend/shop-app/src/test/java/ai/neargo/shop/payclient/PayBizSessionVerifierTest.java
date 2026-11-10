package ai.neargo.shop.payclient;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.auth.store.TokenHash;
import ai.neargo.shop.auth.Realm;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PayBizSessionVerifierTest {
  JdbcTemplate jdbc;
  PayBizSessionVerifier verifier;

  @BeforeEach
  void setUp() {
    var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    jdbc = new JdbcTemplate(ds);
    jdbc.execute(
        "CREATE TABLE mch_session(token_hash VARCHAR PRIMARY KEY,user_no VARCHAR,subject_kind"
            + " VARCHAR,expires_at TIMESTAMP,revoked_at TIMESTAMP)");
    jdbc.execute(
        "CREATE TABLE usr_account(user_no VARCHAR PRIMARY KEY,status VARCHAR,tenant_no"
            + " VARCHAR,deleted INT)");
    jdbc.execute(
        "CREATE TABLE mch_account(mch_account_no VARCHAR PRIMARY KEY,status VARCHAR,tenant_no"
            + " VARCHAR,deleted INT)");
    jdbc.update("INSERT INTO usr_account VALUES ('SAME','NORMAL','MAIN',0)");
    jdbc.update("INSERT INTO mch_account VALUES ('SAME','ACTIVE','MAIN',0)");
    session("btk_user", "USR");
    session("btk_staff", "MCH");
    verifier = new PayBizSessionVerifier(JdbcClient.create(ds));
  }

  void session(String token, String kind) {
    jdbc.update(
        "INSERT INTO mch_session VALUES (?, 'SAME', ?, ?, NULL)",
        TokenHash.of(token),
        kind,
        LocalDateTime.now().plusHours(1));
  }

  @Test
  void sameRefPreservesSubjectTypeAndReadsRevocationImmediately() {
    assertEquals(Realm.MERCHANT, verifier.verify("btk_user").realm());
    assertEquals(SubjectKind.USR, verifier.verify("btk_user").subjectKind());
    assertEquals(SubjectKind.MCH, verifier.verify("btk_staff").subjectKind());
    jdbc.update(
        "UPDATE mch_session SET revoked_at=CURRENT_TIMESTAMP WHERE token_hash=?",
        TokenHash.of("btk_user"));
    denied(HttpStatus.UNAUTHORIZED, "btk_user");
    assertEquals(SubjectKind.MCH, verifier.verify("btk_staff").subjectKind());
  }

  @Test
  void disabledDeletedTenantAndWrongKindAreForbiddenWithoutBorrowingOtherType() {
    jdbc.update("UPDATE usr_account SET status='BANNED'");
    denied(HttpStatus.FORBIDDEN, "btk_user");
    assertEquals(SubjectKind.MCH, verifier.verify("btk_staff").subjectKind());
    jdbc.update("UPDATE mch_account SET tenant_no='OTHER'");
    denied(HttpStatus.FORBIDDEN, "btk_staff");
    jdbc.update("UPDATE mch_account SET tenant_no='MAIN',deleted=1");
    denied(HttpStatus.FORBIDDEN, "btk_staff");
    jdbc.update("UPDATE mch_account SET deleted=0,status='DISABLED'");
    denied(HttpStatus.FORBIDDEN, "btk_staff");
    session("btk_ops", "OPS");
    denied(HttpStatus.FORBIDDEN, "btk_ops");
  }

  @Test
  void expiredMissingAndWrongRealmHaveRealFailureStatuses() {
    jdbc.update("UPDATE mch_session SET expires_at=?", LocalDateTime.now().minusSeconds(1));
    denied(HttpStatus.UNAUTHORIZED, "btk_user");
    denied(HttpStatus.UNAUTHORIZED, "btk_missing");
    denied(HttpStatus.UNAUTHORIZED, null);
    denied(HttpStatus.FORBIDDEN, "ctk_consumer");
    denied(HttpStatus.FORBIDDEN, "otk_operator");
  }

  @Test
  void dependencyFailureIs503WithoutDatabaseDetails() {
    jdbc.execute("DROP TABLE mch_session");
    var error = assertThrows(PayBizException.class, () -> verifier.verify("btk_user"));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status());
    assertFalse(error.getMessage().contains("SELECT"));
    assertNull(error.getCause());
  }

  void denied(HttpStatus status, String token) {
    assertEquals(
        status, assertThrows(PayBizException.class, () -> verifier.verify(token)).status());
  }
}
