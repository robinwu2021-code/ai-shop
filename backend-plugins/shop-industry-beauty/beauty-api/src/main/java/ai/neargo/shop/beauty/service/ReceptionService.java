package ai.neargo.shop.beauty.service;

import ai.neargo.shop.beauty.dto.ArrivalChallengeVO;

/** A short-lived store challenge is public arrival evidence, not login or payment authority. */
public interface ReceptionService {
    ArrivalChallengeVO issueArrivalChallenge(String entityNo, String currentStoreNo, String actorNo,
            String idempotencyKey);
}
