package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.DiningIdentityContracts.DeviceCredentialVO;
import ai.neargo.shop.food.dto.DiningIdentityContracts.DeviceSessionReq;
import ai.neargo.shop.food.dto.DiningIdentityContracts.DeviceVO;
import ai.neargo.shop.food.dto.DiningIdentityContracts.PartyVO;
import ai.neargo.shop.food.dto.DiningIdentityContracts.RegisterDeviceReq;
import ai.neargo.shop.food.dto.DiningIdentityContracts.SessionCredentialVO;
import ai.neargo.shop.food.dto.DiningIdentityContracts.StaffPartyReq;
import ai.neargo.shop.food.dto.DiningIdentityContracts.VersionReq;

/**
 * Keeps the DiningIdentityService business boundary scoped to original restaurant facts and
 * lifecycle authority.
 */
public interface DiningIdentityService {
    DeviceVO device(String entity, String store, String deviceNo);

    DeviceCredentialVO registerDevice(
            String entity, String store, String actor, RegisterDeviceReq request, String key);

    DeviceVO revokeDevice(
            String entity,
            String store,
            String actor,
            String deviceNo,
            VersionReq request,
            String key);

    SessionCredentialVO createDeviceSession(
            String entity,
            String store,
            String deviceNo,
            String credential,
            DeviceSessionReq request,
            String key);

    SessionCredentialVO createStaffParty(
            String entity, String store, String actor, StaffPartyReq request, String key);

    PartyVO revokeParty(
            String entity,
            String store,
            String actor,
            String sessionNo,
            VersionReq request,
            String key);

    /** Scope/liveness fact only. The caller must separately prove staff/customer authorization. */
    PartyVO party(String entity, String store, String sessionNo);

    void validateDeviceSession(String sessionNo);
}
