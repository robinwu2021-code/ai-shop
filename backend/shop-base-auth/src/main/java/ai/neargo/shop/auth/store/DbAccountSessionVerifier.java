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
    private final Realm realm;
    private final TransactionTemplate fresh;
    private final FreshAccountReadDataSource routing;

    public DbAccountSessionVerifier(
            SessionDao sessions,
            CompositeIdentityLoader<LoginUser> identities,
            Clock accountClock,
            PlatformTransactionManager manager) {
        this(sessions, identities, accountClock, manager, Realm.MERCHANT);
    }

    public DbAccountSessionVerifier(SessionDao sessions, CompositeIdentityLoader<LoginUser> identities,
            Clock accountClock, PlatformTransactionManager manager, Realm realm) {
        if (realm != Realm.MERCHANT && realm != Realm.OPERATOR) throw new IllegalArgumentException("unsupported realm");
        this.realm = realm;
        this.sessions = sessions;
        this.identities = identities;
        this.accountClock = accountClock;
        routing = manager instanceof org.springframework.jdbc.datasource.DataSourceTransactionManager jdbc
                && jdbc.getDataSource() instanceof FreshAccountReadDataSource source ? source : null;
        fresh = new TransactionTemplate(manager);
        fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        fresh.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        fresh.setReadOnly(true);
    }

    public <T> Optional<T> withFresh(
            String bearer, Instant now, Function<AccountSessionVerification, T> read) {
        if (bearer == null || !bearer.startsWith(realm.tokenPrefix()))
            return Optional.empty();
        var hash = TokenHash.of(bearer);
        java.util.function.Supplier<Optional<T>> action = () -> sessions.findByHash(hash)
                                .filter(
                                        row ->
                                                row.isLive(
                                                        LocalDateTime.ofInstant(
                                                                now, accountClock.getZone())))
                                .flatMap(
                                        row -> {
                                            SubjectKind kind;
                                            if (realm == Realm.OPERATOR && "OPS".equals(row.subjectKind()))
                                                kind = SubjectKind.OPS;
                                            else if (realm != Realm.MERCHANT) return Optional.empty();
                                            else if ("MCH".equals(row.subjectKind()))
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
                                                                                    realm),
                                                                            hash,
                                                                            row.expiresAt()
                                                                                    .atZone(
                                                                                            accountClock
                                                                                                    .getZone())
                                                                                    .toInstant(),
                                                                            now))
                                                    .map(read);
                                        });
        return routing == null ? fresh.execute(tx -> action.get()) : routing.read(fresh, action);
    }
}
