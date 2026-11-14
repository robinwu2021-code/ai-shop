package ai.neargo.shop.member.service;

import ai.neargo.shop.spi.member.GuestRegistrationPort.Scope;
import ai.neargo.shop.spi.member.GuestRegistrationPort.ConsumerScope;
import ai.neargo.shop.spi.member.GuestRegistrationPort.CurrentClaim;
import ai.neargo.shop.spi.member.GuestRegistrationPort.ClaimedCustomer;
import ai.neargo.shop.spi.member.GuestRegistrationPort.GuestRef;
import java.util.List;
import ai.neargo.shop.spi.member.GuestRegistrationPort.RegisterCommand;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Registration;
import ai.neargo.shop.spi.member.GuestRegistrationPort.GuestFact;
import ai.neargo.shop.spi.member.GuestRegistrationPort.ClaimCommand;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Claim;

/** Shared guest lifecycle stays with the member owner across industry deployments. */
public interface MemberGuestRegistrationService {
    /** A genuine claimed guest can identify its active user without synthetic membership. */
    ClaimedCustomer resolveClaimedCustomer(Scope scope, String guestNo, String claimToken);

    /** Current entitlement requires the actual active consumer account. */
    CurrentClaim currentClaim(ConsumerScope scope, GuestRef guest);

    /** A complete owner set is mandatory for private historical guest reads. */
    List<CurrentClaim> currentClaims(ConsumerScope scope);

    Registration register(Scope scope, RegisterCommand command);
    GuestFact get(Scope scope, String guestNo);
    GuestFact verify(Scope scope, String guestNo, String claimToken);
    Claim claim(Scope scope, String guestNo, ClaimCommand command);
}
