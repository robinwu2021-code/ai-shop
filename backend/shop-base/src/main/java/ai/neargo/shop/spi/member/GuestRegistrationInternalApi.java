package ai.neargo.shop.spi.member;

import ai.neargo.shop.spi.member.GuestRegistrationPort.Claim;
import ai.neargo.shop.spi.member.GuestRegistrationPort.ClaimCommand;
import ai.neargo.shop.spi.member.GuestRegistrationPort.GuestFact;
import ai.neargo.shop.spi.member.GuestRegistrationPort.RegisterCommand;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Registration;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Scope;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Complete request echoes prevent a successful response for another principal or store being accepted as fact. */
public interface GuestRegistrationInternalApi {
    String BASE = "/internal/guest-registration/";
    @PostExchange(BASE + "register") Registered register(@RequestBody Register request);
    @PostExchange(BASE + "get") Found get(@RequestBody Get request);
    @PostExchange(BASE + "verify") Verified verify(@RequestBody Verify request);
    @PostExchange(BASE + "claim") Claimed claim(@RequestBody ClaimRequest request);
    record Register(Scope scope, RegisterCommand command) { }
    record Get(Scope scope, String guestNo) { }
    record Verify(Scope scope, String guestNo, String claimToken) {
        @Override public String toString() { return "Verify[claimToken=REDACTED]"; }
    }
    record ClaimRequest(Scope scope, String guestNo, ClaimCommand command) { }
    record Registered(Register request, Registration registration) { }
    record Found(Get request, GuestFact guest) { }
    record Verified(Verify request, GuestFact guest) { }
    record Claimed(ClaimRequest request, Claim claim) { }
}
