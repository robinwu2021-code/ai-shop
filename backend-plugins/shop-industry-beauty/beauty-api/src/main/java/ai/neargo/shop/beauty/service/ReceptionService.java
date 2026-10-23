package ai.neargo.shop.beauty.service;

import ai.neargo.shop.beauty.dto.ArrivalChallengeVO;
import ai.neargo.shop.beauty.dto.ArrivalVO;
import ai.neargo.shop.beauty.dto.CheckInReq;
import ai.neargo.shop.beauty.dto.MyArrivalVO;
import ai.neargo.shop.beauty.dto.MyCheckInReq;
import ai.neargo.shop.beauty.dto.ReceiveWalkInReq;
import ai.neargo.shop.beauty.dto.ReceptionExceptionReq;
import ai.neargo.shop.beauty.dto.ReceptionExceptionVO;
import ai.neargo.shop.beauty.dto.WalkInVO;

/** Records verified presence and reception requests without creating service tasks or payment authority. */
public interface ReceptionService {
    /** Reception reserves the complete plan before creating its single frozen service scope. */
    WalkInVO receiveWalkIn(String entityNo, String currentStoreNo, String actorNo,
            ReceiveWalkInReq req, String idempotencyKey);
    /** Current owner proof ties merchant-recorded arrival to the appointment identity. */
    ArrivalVO checkIn(String entityNo, String currentStoreNo, String actorNo, CheckInReq req, String idempotencyKey);
    /** Both personal member proof and live store evidence are required for consumer arrival. */
    MyArrivalVO checkInMyAppointment(String userNo, MyCheckInReq req, String idempotencyKey);
    /** Appends a review request without executing the proposed schedule or cancellation change. */
    ReceptionExceptionVO coordinateReceptionException(String entityNo, String currentStoreNo, String actorNo,
            ReceptionExceptionReq req, String idempotencyKey);
    ArrivalChallengeVO issueArrivalChallenge(String entityNo, String currentStoreNo, String actorNo,
            String idempotencyKey);
}
