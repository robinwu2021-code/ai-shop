package ai.neargo.shop.auth.store;

import ai.neargo.auth.store.*;
import ai.neargo.shop.auth.*;

import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.Optional;
import java.util.function.Function;

/** The session DAO and MyBatis identity/member readers join a new primary DB snapshot. */
public final class DbAccountSessionVerifier implements AccountSessionVerifier {
    private final SessionDao sessions;
    private final CompositeIdentityLoader<LoginUser> identities;
    private final Clock accountClock;
    private final TransactionTemplate fresh;

    public DbAccountSessionVerifier(
            SessionDao sessions,
            CompositeIdentityLoader<LoginUser> identities,
            Clock accountClock,
            PlatformTransactionManager manager) {
        this.sessions = sessions;
        this.identities = identities;
        this.accountClock = accountClock;
        fresh = new TransactionTemplate(manager);
        fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        fresh.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        fresh.setReadOnly(true);
    }

    public <T> Optional<T> withFresh(
            String bearer, Instant now, Function<AccountSessionVerification, T> read) {
        if (bearer == null || !bearer.startsWith(Realm.MERCHANT.tokenPrefix()))
            return Optional.empty();
        var hash = TokenHash.of(bearer);
        return fresh.execute(
                tx ->
                        sessions.findByHash(hash)
                                .filter(
                                        row ->
                                                row.isLive(
                                                        LocalDateTime.ofInstant(
                                                                now, accountClock.getZone())))
                                .flatMap(
                                        row -> {
                                            SubjectKind kind;
                                            if ("MCH".equals(row.subjectKind()))
                                                kind = SubjectKind.MCH;
                                            else if ("USR".equals(row.subjectKind()))
                                                kind = SubjectKind.USR;
                                            else return Optional.empty();
                                            return identities
                                                    .load(row.userNo(), kind)
                                                    .filter(
                                                            u ->
                                                                    u.subjectKind() == kind
                                                                            && row.userNo()
                                                                                    .equals(
                                                                                            u
                                                                                                    .userNo()))
                                                    .map(
                                                            u ->
                                                                    new AccountSessionVerification(
                                                                            u.withRealm(
                                                                                    Realm.MERCHANT),
                                                                            hash,
                                                                            row.expiresAt()
                                                                                    .atZone(
                                                                                            accountClock
                                                                                                    .getZone())
                                                                                    .toInstant(),
                                                                            now))
                                                    .map(read);
                                        }));
    }
}
