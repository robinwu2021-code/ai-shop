package ai.neargo.shop.member.service;

import ai.neargo.shop.spi.member.GuestRegistrationPort.Scope;
import ai.neargo.shop.spi.member.GuestRegistrationPort.RegisterCommand;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Registration;
import ai.neargo.shop.spi.member.GuestRegistrationPort.GuestFact;
import ai.neargo.shop.spi.member.GuestRegistrationPort.ClaimCommand;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Claim;

/** Shared guest lifecycle stays with the member owner across industry deployments. */
public interface MemberGuestRegistrationService {
    Registration register(Scope scope, RegisterCommand command);
    GuestFact get(Scope scope, String guestNo);
    GuestFact verify(Scope scope, String guestNo, String claimToken);
    Claim claim(Scope scope, String guestNo, ClaimCommand command);
}
