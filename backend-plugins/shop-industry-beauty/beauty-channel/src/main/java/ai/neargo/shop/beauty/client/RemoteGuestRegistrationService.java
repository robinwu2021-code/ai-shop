package ai.neargo.shop.beauty.client;

import ai.neargo.shop.beauty.dto.ClaimGuestReq;
import ai.neargo.shop.beauty.dto.GuestClaimVO;
import ai.neargo.shop.beauty.dto.GuestRegistrationVO;
import ai.neargo.shop.beauty.dto.RegisterGuestReq;
import ai.neargo.shop.beauty.service.GuestRegistrationService;
import ai.neargo.shop.beauty.spi.BeautyReceptionInternalApi;
import ai.neargo.shop.svc.InternalBindings;

import java.util.Objects;

/** Delegates to the same private owner without retrying uncertain business writes. */
public final class RemoteGuestRegistrationService implements GuestRegistrationService {
    private final BeautyReceptionInternalApi api;
    private final InternalBindings bindings;

    /** Both typed transport and configured binding secret are mandatory in production. */
    public RemoteGuestRegistrationService(
            BeautyReceptionInternalApi api, InternalBindings bindings) {
        this.api = Objects.requireNonNull(api);
        this.bindings = Objects.requireNonNull(bindings);
    }

    @Override
    public GuestRegistrationVO registerGuest(
            String entityNo,
            String currentStoreNo,
            String actorNo,
            RegisterGuestReq req,
            String idempotencyKey) {
        return BeautyReceptionCalls.call(
                "BAPI-031",
                "REGISTER_GUEST",
                entityNo,
                currentStoreNo,
                actorNo,
                null,
                new BeautyReceptionInternalApi.RegisterGuest(req, idempotencyKey),
                bindings,
                api::registerGuest);
    }

    @Override
    public GuestClaimVO claimGuest(
            String entityNo,
            String currentStoreNo,
            String actorNo,
            String guestNo,
            ClaimGuestReq req,
            String idempotencyKey) {
        return BeautyReceptionCalls.call(
                "BAPI-032",
                "CLAIM_GUEST",
                entityNo,
                currentStoreNo,
                actorNo,
                guestNo,
                new BeautyReceptionInternalApi.ClaimGuest(guestNo, req, idempotencyKey),
                bindings,
                api::claimGuest);
    }
}
