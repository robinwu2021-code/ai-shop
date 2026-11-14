package ai.neargo.shop.beauty.client;

import ai.neargo.shop.beauty.dto.ArrivalChallengeVO;
import ai.neargo.shop.beauty.dto.ArrivalVO;
import ai.neargo.shop.beauty.dto.CheckInReq;
import ai.neargo.shop.beauty.dto.MyArrivalVO;
import ai.neargo.shop.beauty.dto.MyCheckInReq;
import ai.neargo.shop.beauty.dto.ReceiveWalkInReq;
import ai.neargo.shop.beauty.dto.ReceptionExceptionReq;
import ai.neargo.shop.beauty.dto.ReceptionExceptionVO;
import ai.neargo.shop.beauty.dto.WalkInVO;
import ai.neargo.shop.beauty.service.ReceptionService;
import ai.neargo.shop.beauty.spi.BeautyReceptionInternalApi;
import ai.neargo.shop.svc.InternalBindings;

import java.util.Objects;

/** Delegates to the same private owner without retrying uncertain business writes. */
public final class RemoteReceptionService implements ReceptionService {
    private final BeautyReceptionInternalApi api;
    private final InternalBindings bindings;

    /** Both typed transport and configured binding secret are mandatory in production. */
    public RemoteReceptionService(BeautyReceptionInternalApi api, InternalBindings bindings) {
        this.api = Objects.requireNonNull(api);
        this.bindings = Objects.requireNonNull(bindings);
    }

    @Override
    public ArrivalVO checkIn(
            String entityNo,
            String currentStoreNo,
            String actorNo,
            CheckInReq req,
            String idempotencyKey) {
        return BeautyReceptionCalls.call(
                "BAPI-047",
                "CHECK_IN",
                entityNo,
                currentStoreNo,
                actorNo,
                req.appointmentNo(),
                new BeautyReceptionInternalApi.CheckIn(req, idempotencyKey),
                bindings,
                api::checkIn);
    }

    @Override
    public WalkInVO receiveWalkIn(
            String entityNo,
            String currentStoreNo,
            String actorNo,
            ReceiveWalkInReq req,
            String idempotencyKey) {
        return BeautyReceptionCalls.call(
                "BAPI-048",
                "RECEIVE_WALK_IN",
                entityNo,
                currentStoreNo,
                actorNo,
                null,
                new BeautyReceptionInternalApi.ReceiveWalkIn(req, idempotencyKey),
                bindings,
                api::receiveWalkIn);
    }

    @Override
    public ReceptionExceptionVO coordinateReceptionException(
            String entityNo,
            String currentStoreNo,
            String actorNo,
            ReceptionExceptionReq req,
            String idempotencyKey) {
        return BeautyReceptionCalls.call(
                "BAPI-053",
                "COORDINATE_EXCEPTION",
                entityNo,
                currentStoreNo,
                actorNo,
                req.appointmentNo(),
                new BeautyReceptionInternalApi.CoordinateException(req, idempotencyKey),
                bindings,
                api::coordinateReceptionException);
    }

    @Override
    public ArrivalChallengeVO issueArrivalChallenge(
            String entityNo, String currentStoreNo, String actorNo, String idempotencyKey) {
        return BeautyReceptionCalls.call(
                "BM04-ARRIVAL-CHALLENGE",
                "ISSUE_ARRIVAL_CHALLENGE",
                entityNo,
                currentStoreNo,
                actorNo,
                null,
                new BeautyReceptionInternalApi.IssueArrivalChallenge(idempotencyKey),
                bindings,
                api::issueArrivalChallenge);
    }

    @Override
    public MyArrivalVO checkInMyAppointment(
            String userNo, MyCheckInReq req, String idempotencyKey) {
        return BeautyReceptionCalls.call(
                "BAPI-154",
                "CHECK_IN_MY_APPOINTMENT",
                null,
                null,
                userNo,
                req.appointmentNo(),
                new BeautyReceptionInternalApi.CheckInMine(req, idempotencyKey),
                bindings,
                api::checkInMyAppointment);
    }
}
