package ai.neargo.shop.payclient;

import ai.neargo.auth.store.TokenHash;
import ai.neargo.shop.auth.LoginUser;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Fresh, typed account authentication; no local store or payment membership is inferred. */
public final class PayBizSessionVerifier {
  private final JdbcClient jdbc;

  public PayBizSessionVerifier(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public LoginUser verify(String token) {
    if (token != null && (token.startsWith("ctk_") || token.startsWith("otk_")))
      throw new PayBizException(HttpStatus.FORBIDDEN);
    if (token == null || !token.startsWith("btk_") || token.length() <= 4 || token.length() > 8192)
      throw new PayBizException(HttpStatus.UNAUTHORIZED);
    // A single uncached statement sees session revocation and account state in one DB snapshot.
    Optional<LoginUser> user;
    try {
      user =
          jdbc.sql(
                  """
SELECT s.user_no, s.subject_kind,
       CASE WHEN s.subject_kind='USR' THEN u.status ELSE m.status END AS status,
       CASE WHEN s.subject_kind='USR' THEN u.tenant_no ELSE m.tenant_no END AS tenant_no,
       CASE WHEN s.subject_kind='USR' THEN u.deleted ELSE m.deleted END AS deleted
FROM mch_session s
LEFT JOIN usr_account u ON s.subject_kind='USR' AND u.user_no=s.user_no
LEFT JOIN mch_account m ON s.subject_kind='MCH' AND m.mch_account_no=s.user_no
WHERE s.token_hash=:hash AND s.revoked_at IS NULL AND s.expires_at>:now
""")
              .param("hash", TokenHash.of(token))
              .param("now", LocalDateTime.now())
              .query(
                  (rs, row) -> {
                    String kind = rs.getString("subject_kind");
                    String no = rs.getString("user_no");
                    String expected = "USR".equals(kind) ? "NORMAL" : "ACTIVE";
                    if (!("USR".equals(kind) || "MCH".equals(kind))
                        || !expected.equals(rs.getString("status"))
                        || !"MAIN".equals(rs.getString("tenant_no"))
                        || rs.getInt("deleted") != 0
                        || no == null
                        || !no.matches("[A-Za-z0-9_-]{1,64}"))
                      throw new PayBizException(HttpStatus.FORBIDDEN);
                    return "USR".equals(kind)
                        ? LoginUser.merchantByUser(no, "")
                        : LoginUser.merchant(no, "");
                  })
              .optional();
    } catch (DataAccessException unavailable) {
      // Session-store failure must never become a stale-cache login or a 401.
      throw new PayBizException(HttpStatus.SERVICE_UNAVAILABLE);
    }
    return user.orElseThrow(() -> new PayBizException(HttpStatus.UNAUTHORIZED));
  }
}
