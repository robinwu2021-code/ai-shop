package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.common.config.ModuleDataSources;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import ai.neargo.auth.store.TokenHash;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.user.service.AuthService;
import ai.neargo.shop.user.service.LoginTicketService;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

@Service
public class LoginTicketServiceImpl implements LoginTicketService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    /** 本服务签发的两种票据用途：登录、免登录切换到商家端（{@code issue} 里按有没有来源会话定） */
    private static final String PURPOSE_LOGIN = "LOGIN";
    private static final String PURPOSE_SWITCH = "SWITCH";
    private static final String PURPOSES = "'" + PURPOSE_LOGIN + "','" + PURPOSE_SWITCH + "'";

    public LoginTicketServiceImpl(@Qualifier(ModuleDataSources.PLATFORM_DATA_SOURCE) DataSource dataSource,
                                  @Qualifier(ModuleDataSources.PLATFORM_TX_MANAGER) PlatformTransactionManager manager,
                                  @Value("${shop.auth.login-ticket.ttl:PT5M}") Duration ttl) {
        if (ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("login-ticket ttl must be positive");
        // 票据表与会话表同在平台基础库：手写 SQL 和事务都走这个模块的句柄
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(manager);
        // Credential entry is its own atomic unit, never an earlier caller's RR snapshot.
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.ttl = ttl;
    }

    @Override
    public Issued issue(AuthService.VerifiedPrincipal p, Boolean agreed, String market, List<String> identities,
                        boolean canCreate, String originToken, String originPrincipal, String originKind) {
        // Opportunistic expiry cleanup shares the indexed expiry boundary; no new scheduled job.
        // 票据表五种用途共用（TDD-平台基础库换表 第 2 步 I）：这里只清、只认登录与切换两种
        jdbc.update("DELETE FROM auth_ticket WHERE expires_at<CURRENT_TIMESTAMP AND purpose IN (" + PURPOSES + ")");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("INSERT INTO auth_ticket(ticket_hash,purpose,user_no,verified_type,verified_value,agreed,"
                        + "market_code,origin_token_hash,origin_principal,origin_kind,identity_nos,can_create_owner,expires_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP))", TokenHash.of(token), originToken == null ? PURPOSE_LOGIN : PURPOSE_SWITCH,
                p.userNo(), p.type(), p.value(), Boolean.TRUE.equals(agreed), market,
                originToken == null ? null : TokenHash.of(originToken), originPrincipal, originKind,
                String.join(",", identities), canCreate, ttl.toSeconds());
        return new Issued(token, ttl.toSeconds());
    }

    @Override
    public <T> T locked(String token, Function<Ticket, T> action) {
        return transactions.execute(status -> {
            var rows = jdbc.query("SELECT * FROM auth_ticket WHERE ticket_hash=? AND consumed_at IS NULL"
                            + " AND expires_at>CURRENT_TIMESTAMP AND deleted=0 AND purpose IN (" + PURPOSES + ") FOR UPDATE", (rs, n) -> new Ticket(
                            rs.getString("ticket_hash"), rs.getString("purpose"), new AuthService.VerifiedPrincipal(
                            rs.getString("verified_type"), rs.getString("verified_value"), rs.getString("user_no")),
                            rs.getBoolean("agreed"), rs.getString("market_code"), rs.getString("origin_token_hash"),
                            rs.getString("origin_principal"), rs.getString("origin_kind"),
                            rs.getString("identity_nos").isEmpty() ? List.of() : Arrays.asList(rs.getString("identity_nos").split(",")),
                            rs.getBoolean("can_create_owner"), rs.getObject("expires_at", LocalDateTime.class)), TokenHash.of(token));
            if (rows.isEmpty()) throw BizException.of(ErrorCode.LOGIN_TICKET_INVALID);
            return action.apply(rows.getFirst());
        });
    }

    @Override
    public void narrow(Ticket ticket, List<String> identities) {
        jdbc.update("UPDATE auth_ticket SET identity_nos=?,updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE ticket_hash=?",
                String.join(",", identities), ticket.hash());
    }

    @Override
    public void consume(Ticket ticket) {
        if (jdbc.update("UPDATE auth_ticket SET consumed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP,version=version+1"
                + " WHERE ticket_hash=? AND consumed_at IS NULL AND expires_at>CURRENT_TIMESTAMP", ticket.hash()) != 1)
            throw BizException.of(ErrorCode.LOGIN_TICKET_INVALID);
    }
}
