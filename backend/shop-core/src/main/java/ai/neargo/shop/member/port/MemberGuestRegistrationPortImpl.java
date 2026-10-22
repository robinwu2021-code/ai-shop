package ai.neargo.shop.member.port;

import ai.neargo.shop.member.service.MemberGuestRegistrationService;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import org.springframework.stereotype.Component;

/** Single public provider delegates lifecycle authority to the member service. */
@Component
public class MemberGuestRegistrationPortImpl implements GuestRegistrationPort {
    private final MemberGuestRegistrationService service;
    public MemberGuestRegistrationPortImpl(MemberGuestRegistrationService service) { this.service = service; }
    @Override public Registration register(Scope scope, RegisterCommand command) {
        return service.register(scope, command);
    }
    @Override public GuestFact get(Scope scope, String guestNo) { return service.get(scope, guestNo); }
    @Override public GuestFact verify(Scope scope, String guestNo, String token) {
        return service.verify(scope, guestNo, token);
    }
    @Override public Claim claim(Scope scope, String guestNo, ClaimCommand command) {
        return service.claim(scope, guestNo, command);
    }
}
