package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.DiningIdentityContracts.DeviceCredentialVO;
import ai.neargo.shop.food.dto.DiningIdentityContracts.DeviceSessionReq;
import ai.neargo.shop.food.dto.DiningIdentityContracts.DeviceVO;
import ai.neargo.shop.food.dto.DiningIdentityContracts.PartyVO;
import ai.neargo.shop.food.dto.DiningIdentityContracts.RegisterDeviceReq;
import ai.neargo.shop.food.dto.DiningIdentityContracts.SessionCredentialVO;
import ai.neargo.shop.food.dto.DiningIdentityContracts.StaffPartyReq;
import ai.neargo.shop.food.dto.DiningIdentityContracts.VersionReq;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Keeps the DiningIdentityInternalApi protocol scoped to original restaurant facts and lifecycle
 * authority.
 */
public interface DiningIdentityInternalApi {
    String ROOT = "/internal/food/identity";

    record DeviceQuery(String entityNo, String storeNo, String deviceNo) {}

    record Register(
            String entityNo,
            String storeNo,
            String actorNo,
            RegisterDeviceReq definition,
            String key) {}

    record DeviceRevoke(
            String entityNo,
            String storeNo,
            String actorNo,
            String deviceNo,
            VersionReq definition,
            String key) {}

    record DeviceSession(
            String entityNo,
            String storeNo,
            String deviceNo,
            String credential,
            DeviceSessionReq definition,
            String key) {
        @Override
        public String toString() {
            return "DeviceSession[deviceNo=" + deviceNo + ", credential=REDACTED]";
        }
    }

    record StaffParty(
            String entityNo,
            String storeNo,
            String actorNo,
            StaffPartyReq definition,
            String key) {}

    record PartyRevoke(
            String entityNo,
            String storeNo,
            String actorNo,
            String sessionNo,
            VersionReq definition,
            String key) {}

    record PartyQuery(String entityNo, String storeNo, String sessionNo) {}

    record SessionQuery(String sessionNo) {}

    record DeviceAccess(String entityNo, String storeNo, CartAccess access) {}

    @PostExchange(ROOT + "/device")
    DeviceVO device(@RequestBody DeviceQuery query);

    @PostExchange(ROOT + "/register-device")
    DeviceCredentialVO registerDevice(@RequestBody Register command);

    @PostExchange(ROOT + "/revoke-device")
    DeviceVO revokeDevice(@RequestBody DeviceRevoke command);

    @PostExchange(ROOT + "/device-session")
    SessionCredentialVO createDeviceSession(@RequestBody DeviceSession command);

    @PostExchange(ROOT + "/staff-party")
    SessionCredentialVO createStaffParty(@RequestBody StaffParty command);

    @PostExchange(ROOT + "/revoke-party")
    PartyVO revokeParty(@RequestBody PartyRevoke command);

    @PostExchange(ROOT + "/party")
    PartyVO party(@RequestBody PartyQuery query);

    @PostExchange(ROOT + "/authenticate-device-session")
    PartyVO authenticateDeviceSession(@RequestBody DeviceAccess query);

    @PostExchange(ROOT + "/validate-device-session")
    Boolean validateDeviceSession(@RequestBody SessionQuery query);
}
