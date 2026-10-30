package ai.neargo.shop.beauty.service;

import ai.neargo.shop.beauty.dto.RegisterGuestReq;
import ai.neargo.shop.beauty.dto.ClaimGuestReq;
import ai.neargo.shop.beauty.dto.GuestRegistrationVO;
import ai.neargo.shop.beauty.dto.GuestClaimVO;

/** Identity authority remains with the member owner; beauty records only verified immutable claim receipts. */
public interface GuestRegistrationService {
    GuestRegistrationVO registerGuest(String entityNo, String currentStoreNo, String actorNo,
            RegisterGuestReq req, String idempotencyKey);
    GuestClaimVO claimGuest(String entityNo, String currentStoreNo, String actorNo, String guestNo,
            ClaimGuestReq req, String idempotencyKey);
}
