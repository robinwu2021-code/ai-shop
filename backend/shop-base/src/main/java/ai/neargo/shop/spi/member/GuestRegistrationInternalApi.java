package ai.neargo.shop.spi.member;

import ai.neargo.shop.spi.member.GuestRegistrationPort.Claim;
import ai.neargo.shop.spi.member.GuestRegistrationPort.ClaimCommand;
import ai.neargo.shop.spi.member.GuestRegistrationPort.GuestFact;
import ai.neargo.shop.spi.member.GuestRegistrationPort.RegisterCommand;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Registration;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Scope;
import ai.neargo.shop.spi.member.GuestRegistrationPort.ConsumerScope;
import ai.neargo.shop.spi.member.GuestRegistrationPort.ClaimedCustomer;
import ai.neargo.shop.spi.member.GuestRegistrationPort.CurrentClaim;
import ai.neargo.shop.spi.member.GuestRegistrationPort.GuestRef;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Complete request echoes prevent a successful response for another principal or store being accepted as fact. */
public interface GuestRegistrationInternalApi {
    String BASE = "/internal/guest-registration/";
    @PostExchange(BASE + "register") Registered register(@RequestBody Register request);
    @PostExchange(BASE + "get") Found get(@RequestBody Get request);
    @PostExchange(BASE + "verify") Verified verify(@RequestBody Verify request);
    @PostExchange(BASE + "claim") Claimed claim(@RequestBody ClaimRequest request);

    String COUNTER_CUSTOMER_ENDPOINT = "GUEST_COUNTER_CUSTOMER";

    /** This merchant selector is bound independently from old guest token verification. */
    @PostExchange(BASE + "resolve-claimed-customer")
    InternalReply<ClaimedCustomerResolution> resolveClaimedCustomer(
            @RequestBody InternalCall<Verify> request);

    /** Echo the entire token-bearing request without exposing it in diagnostic text. */
    record ClaimedCustomerResolution(Verify request, ClaimedCustomer customer) {
        @Override
        public String toString() {
            return "ClaimedCustomerResolution[REDACTED]";
        }
    }

    String CURRENT_ENDPOINT = "GUEST_CURRENT_CLAIM";

    /** Bound consumer routes are separate from merchant lifecycle get/verify. */
    @PostExchange(BASE + "current-claim")
    InternalReply<CurrentClaimResponse> currentClaim(
            @RequestBody InternalCall<CurrentClaimRequest> request);

    /** The complete response never truncates the member owner's claim set. */
    @PostExchange(BASE + "current-claims")
    InternalReply<CurrentClaimsResponse> currentClaims(
            @RequestBody InternalCall<CurrentClaimsRequest> request);

    record CurrentClaimRequest(ConsumerScope scope, GuestRef guest) {}

    record CurrentClaimsRequest(ConsumerScope scope) {}

    record CurrentClaimResponse(CurrentClaimRequest request, CurrentClaim claim) {}

    record CurrentClaimsResponse(CurrentClaimsRequest request, List<CurrentClaim> claims) {
        public CurrentClaimsResponse {
            claims = List.copyOf(claims);
        }
    }

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
