package ai.neargo.shop.user.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

/** Durable, hash-only and single-use credential exchange. */
public interface LoginTicketService {
    record Ticket(String hash, String purpose, AuthService.VerifiedPrincipal principal, boolean agreed,
                  String marketCode, String originHash, String originPrincipal, String originKind,
                  List<String> identities, boolean canCreateOwner, LocalDateTime expiresAt) {}
    record Issued(String ticket, long expiresInSeconds) {}
    Issued issue(AuthService.VerifiedPrincipal principal, Boolean agreed, String marketCode,
                 List<String> identities, boolean canCreateOwner, String originToken,
                 String originPrincipal, String originKind);
    <T> T locked(String ticket, Function<Ticket, T> action);
    void narrow(Ticket ticket, List<String> identities);
    void consume(Ticket ticket);
}
