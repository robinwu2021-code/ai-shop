package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.DiningIdentityContracts.DeviceCredentialVO;
import ai.neargo.shop.food.dto.DiningIdentityContracts.DeviceSessionReq;
import ai.neargo.shop.food.dto.DiningIdentityContracts.DeviceVO;
import ai.neargo.shop.food.dto.DiningIdentityContracts.PartyVO;
import ai.neargo.shop.food.dto.DiningIdentityContracts.RegisterDeviceReq;
import ai.neargo.shop.food.dto.DiningIdentityContracts.SessionCredentialVO;
import ai.neargo.shop.food.dto.DiningIdentityContracts.StaffPartyReq;
import ai.neargo.shop.food.dto.DiningIdentityContracts.VersionReq;
import ai.neargo.shop.food.service.DiningIdentityService;
import ai.neargo.shop.food.spi.DiningIdentityInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Keeps the RemoteDiningIdentityService business boundary scoped to original restaurant facts and
 * lifecycle authority.
 */
public class RemoteDiningIdentityService implements DiningIdentityService {
    private final DiningIdentityInternalApi api;

    public RemoteDiningIdentityService(DiningIdentityInternalApi api) {
        this.api = api;
    }

    @Override
    public DeviceVO device(String e, String s, String no) {
        return device(
                e,
                s,
                no,
                call(() -> api.device(new DiningIdentityInternalApi.DeviceQuery(e, s, no))));
    }

    @Override
    public DeviceCredentialVO registerDevice(
            String e, String s, String a, RegisterDeviceReq r, String k) {
        var result =
                call(
                        () ->
                                api.registerDevice(
                                        new DiningIdentityInternalApi.Register(e, s, a, r, k)));
        if (result == null) {
            bad();
        }
        device(e, s, r.deviceNo(), result.device());
        secret(result.rawCredential(), "fd_");
        return result;
    }

    @Override
    public DeviceVO revokeDevice(String e, String s, String a, String no, VersionReq r, String k) {
        var result =
                device(
                        e,
                        s,
                        no,
                        call(
                                () ->
                                        api.revokeDevice(
                                                new DiningIdentityInternalApi.DeviceRevoke(
                                                        e, s, a, no, r, k))));
        if (!"REVOKED".equals(result.status())) {
            bad();
        }
        return result;
    }

    @Override
    public SessionCredentialVO createDeviceSession(
            String e, String s, String no, String credential, DeviceSessionReq r, String k) {
        var result =
                session(
                        e,
                        s,
                        call(
                                () ->
                                        api.createDeviceSession(
                                                new DiningIdentityInternalApi.DeviceSession(
                                                        e, s, no, credential, r, k))));
        if (!"DEVICE".equals(result.party().kind())
                || !no.equals(result.party().deviceNo())
                || !result.party().scope().contains("CART_DEVICE")
                || result.party().buyerNo() != null
                || result.party().checkNo() != null) {
            bad();
        }
        return result;
    }

    @Override
    public SessionCredentialVO createStaffParty(
            String e, String s, String a, StaffPartyReq r, String k) {
        var result =
                session(
                        e,
                        s,
                        call(
                                () ->
                                        api.createStaffParty(
                                                new DiningIdentityInternalApi.StaffParty(
                                                        e, s, a, r, k))));
        if (!"GUEST".equals(result.party().kind())
                || !result.party().scope().contains("STAFF_PARTY")
                || !Objects.equals(r.checkNo(), result.party().checkNo())
                || result.party().buyerNo() != null) {
            bad();
        }
        return result;
    }

    @Override
    public PartyVO revokeParty(String e, String s, String a, String no, VersionReq r, String k) {
        return party(
                e,
                s,
                no,
                call(
                        () ->
                                api.revokeParty(
                                        new DiningIdentityInternalApi.PartyRevoke(
                                                e, s, a, no, r, k))));
    }

    @Override
    public PartyVO party(String e, String s, String no) {
        return party(
                e,
                s,
                no,
                call(() -> api.party(new DiningIdentityInternalApi.PartyQuery(e, s, no))));
    }

    @Override
    public void validateDeviceSession(String no) {
        if (!Boolean.TRUE.equals(
                call(
                        () ->
                                api.validateDeviceSession(
                                        new DiningIdentityInternalApi.SessionQuery(no))))) {
            bad();
        }
    }

    private SessionCredentialVO session(String e, String s, SessionCredentialVO result) {
        if (result == null || result.party() == null) {
            bad();
        }
        party(e, s, result.party().sessionNo(), result.party());
        secret(result.accessToken(), "fs_");
        return result;
    }

    private PartyVO party(String e, String s, String no, PartyVO r) {
        if (r == null
                || !e.equals(r.entityNo())
                || !s.equals(r.storeNo())
                || !no.equals(r.sessionNo())
                || r.expiresAt() == null
                || r.scope() == null
                || r.scope().isEmpty()
                || r.version() < 0
                || r.deviceGeneration() < 0
                || !Set.of("GUEST", "DEVICE").contains(Objects.toString(r.kind(), ""))) {
            bad();
        }
        return r;
    }

    private DeviceVO device(String e, String s, String no, DeviceVO r) {
        if (r == null
                || !e.equals(r.entityNo())
                || !s.equals(r.storeNo())
                || !no.equals(r.deviceNo())
                || r.version() < 0
                || r.generation() < 1
                || !Set.of("ACTIVE", "REVOKED").contains(Objects.toString(r.status(), ""))) {
            bad();
        }
        return r;
    }

    private static void secret(String token, String prefix) {
        if (token != null
                && (!token.startsWith(prefix) || token.length() > 128 || token.length() < 32)) {
            bad();
        }
    }

    private <T> T call(Supplier<T> action) {
        try {
            return ServiceCalls.call(ServiceName.FOOD, action);
        } catch (ServiceCallException f) {
            ErrorCode code =
                    switch (f.statusCode()) {
                        case 400 -> ErrorCode.BAD_REQUEST;
                        case 403 -> ErrorCode.FORBIDDEN;
                        case 404 -> ErrorCode.NOT_FOUND;
                        case 409 -> ErrorCode.CONFLICT;
                        default -> null;
                    };
            if (code != null) {
                throw BizException.of(code);
            }
            throw f;
        }
    }

    private static void bad() {
        throw BizException.of(ErrorCode.CONFLICT);
    }
}
