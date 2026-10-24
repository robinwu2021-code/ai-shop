package ai.neargo.shop.beauty.client;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.dto.AppointmentChangePreviewVO;
import ai.neargo.shop.beauty.dto.AppointmentDetailVO;
import ai.neargo.shop.beauty.dto.AppointmentPreviewVO;
import ai.neargo.shop.beauty.dto.AppointmentQuery;
import ai.neargo.shop.beauty.dto.AppointmentSummaryVO;
import ai.neargo.shop.beauty.dto.AppointmentVO;
import ai.neargo.shop.beauty.dto.CancelAppointmentReq;
import ai.neargo.shop.beauty.dto.CancellationVO;
import ai.neargo.shop.beauty.dto.ChangeAppointmentReq;
import ai.neargo.shop.beauty.dto.CreateAppointmentReq;
import ai.neargo.shop.beauty.dto.CreateMyAppointmentReq;
import ai.neargo.shop.beauty.dto.MyAppointmentDetailVO;
import ai.neargo.shop.beauty.dto.MyAppointmentQuery;
import ai.neargo.shop.beauty.dto.MyAppointmentVO;
import ai.neargo.shop.beauty.dto.NoShowVO;
import ai.neargo.shop.beauty.dto.OpenAppointmentWorkOrderReq;
import ai.neargo.shop.beauty.dto.PreviewAppointmentChangeReq;
import ai.neargo.shop.beauty.dto.PreviewAppointmentReq;
import ai.neargo.shop.beauty.dto.PreviewMyAppointmentChangeReq;
import ai.neargo.shop.beauty.dto.PreviewMyAppointmentReq;
import ai.neargo.shop.beauty.dto.RecordNoShowReq;
import ai.neargo.shop.beauty.dto.WorkOrderVO;
import ai.neargo.shop.beauty.service.AppointmentService;
import ai.neargo.shop.beauty.spi.BeautyBookingInternalApi;
import ai.neargo.shop.beauty.spi.BeautyReceptionInternalApi;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.svc.InternalBindings;

import java.util.Objects;

/** Same authenticated service contract across a process boundary, without retrying uncertain writes. */
public final class RemoteAppointmentService implements AppointmentService {
    private final BeautyBookingInternalApi api;
    private final BeautyReceptionInternalApi reception;
    private final InternalBindings bindings;

    /**
     * The OPEN protocol is mandatory even when older booking reads retain their original wire
     * contract.
     */
    public RemoteAppointmentService(
            BeautyBookingInternalApi api,
            BeautyReceptionInternalApi reception,
            InternalBindings bindings) {
        this.api = Objects.requireNonNull(api);
        this.reception = Objects.requireNonNull(reception);
        this.bindings = Objects.requireNonNull(bindings);
    }

    @Override
    public WorkOrderVO openWorkOrderFromAppointment(
            String entityNo,
            String currentStoreNo,
            String actorNo,
            String appointmentNo,
            OpenAppointmentWorkOrderReq req,
            String idempotencyKey) {
        return BeautyReceptionCalls.call(
                "BAPI-052",
                "OPEN_APPOINTMENT",
                entityNo,
                currentStoreNo,
                actorNo,
                appointmentNo,
                new BeautyReceptionInternalApi.OpenAppointment(appointmentNo, req, idempotencyKey),
                bindings,
                reception::openWorkOrderFromAppointment);
    }

    @Override
    public AppointmentPreviewVO previewAppointment(String entityNo, String currentStoreNo, String actorNo,
            PreviewAppointmentReq req) {
        DelegatedIdentityContext.outside();
        return BeautyServiceCalls.call(() -> BeautyBookingResponses.durablePreview(api.previewAppointment(new
                BeautyBookingInternalApi.PreviewAppointment(entityNo, currentStoreNo, actorNo, req,
                DelegatedIdentityContext.current(actorNo)))));
    }
    @Override
    public AppointmentVO createAppointment(String entityNo, String currentStoreNo, String actorNo,
            CreateAppointmentReq req, String idempotencyKey) {
        DelegatedIdentityContext.outside();
        return BeautyServiceCalls.call(() -> BeautyBookingResponses.require(api.createAppointment(new
                BeautyBookingInternalApi.CreateAppointment(entityNo, currentStoreNo, actorNo, req,
                idempotencyKey, DelegatedIdentityContext.current(actorNo)))));
    }
    @Override
    public PageData<AppointmentSummaryVO> listAppointments(String entityNo, String currentStoreNo, String
            actorNo, AppointmentQuery query) {
        DelegatedIdentityContext.outside();
        return BeautyServiceCalls.call(() -> BeautyBookingResponses.require(api.listAppointments(new
                BeautyBookingInternalApi.ListAppointments(entityNo, currentStoreNo, actorNo, query,
                DelegatedIdentityContext.current(actorNo)))));
    }
    @Override
    public AppointmentDetailVO getAppointment(String entityNo, String currentStoreNo, String actorNo, String
            appointmentNo) {
        DelegatedIdentityContext.outside();
        return BeautyBookingResponses.identity(BeautyServiceCalls.call(() ->
                BeautyBookingResponses.require(api.getAppointment(new
                BeautyBookingInternalApi.GetAppointment(entityNo, currentStoreNo, actorNo, appointmentNo,
                DelegatedIdentityContext.current(actorNo))))), appointmentNo);
    }
    @Override
    public AppointmentChangePreviewVO previewAppointmentChange(String entityNo, String currentStoreNo, String
            actorNo, String appointmentNo, PreviewAppointmentChangeReq req) {
        DelegatedIdentityContext.outside();
        return BeautyServiceCalls.call(() -> BeautyBookingResponses.require(api.previewAppointmentChange(new
                BeautyBookingInternalApi.PreviewAppointmentChange(entityNo, currentStoreNo, actorNo,
                appointmentNo, req, DelegatedIdentityContext.current(actorNo)))));
    }
    @Override
    public AppointmentVO changeAppointment(String entityNo, String currentStoreNo, String actorNo, String
            appointmentNo, ChangeAppointmentReq req, String idempotencyKey) {
        DelegatedIdentityContext.outside();
        return BeautyBookingResponses.identity(BeautyServiceCalls.call(() ->
                BeautyBookingResponses.require(api.changeAppointment(new
                BeautyBookingInternalApi.ChangeAppointment(entityNo, currentStoreNo, actorNo, appointmentNo,
                req, idempotencyKey, DelegatedIdentityContext.current(actorNo))))), appointmentNo);
    }
    @Override
    public CancellationVO cancelAppointment(String entityNo, String currentStoreNo, String actorNo, String
            appointmentNo, CancelAppointmentReq req, String idempotencyKey) {
        DelegatedIdentityContext.outside();
        return BeautyBookingResponses.identity(BeautyServiceCalls.call(() ->
                BeautyBookingResponses.require(api.cancelAppointment(new
                BeautyBookingInternalApi.CancelAppointment(entityNo, currentStoreNo, actorNo, appointmentNo,
                req, idempotencyKey, DelegatedIdentityContext.current(actorNo))))), appointmentNo);
    }
    @Override
    public NoShowVO recordNoShow(String entityNo, String currentStoreNo, String actorNo, String appointmentNo,
            RecordNoShowReq req, String idempotencyKey) {
        DelegatedIdentityContext.outside();
        return BeautyBookingResponses.identity(BeautyServiceCalls.call(() ->
                BeautyBookingResponses.require(api.recordNoShow(new
                BeautyBookingInternalApi.RecordNoShow(entityNo, currentStoreNo, actorNo, appointmentNo, req,
                idempotencyKey, DelegatedIdentityContext.current(actorNo))))), appointmentNo);
    }
    @Override
    public AppointmentPreviewVO previewMyAppointment(String userNo, PreviewMyAppointmentReq req) {
        DelegatedIdentityContext.outside();
        return BeautyServiceCalls.call(() -> BeautyBookingResponses.durablePreview(api.previewMyAppointment(new
                BeautyBookingInternalApi.PreviewMyAppointment(userNo, req, DelegatedIdentityContext.current(userNo)))));
    }
    @Override
    public MyAppointmentVO createMyAppointment(String userNo, CreateMyAppointmentReq req, String idempotencyKey) {
        DelegatedIdentityContext.outside();
        return BeautyServiceCalls.call(() -> BeautyBookingResponses.require(api.createMyAppointment(new
                BeautyBookingInternalApi.CreateMyAppointment(userNo, req, idempotencyKey,
                DelegatedIdentityContext.current(userNo)))));
    }
    @Override
    public PageData<MyAppointmentVO> listMyAppointments(String userNo, MyAppointmentQuery query) {
        DelegatedIdentityContext.outside();
        return BeautyServiceCalls.call(() -> BeautyBookingResponses.require(api.listMyAppointments(new
                BeautyBookingInternalApi.ListMyAppointments(userNo, query, DelegatedIdentityContext.current(userNo)))));
    }
    @Override
    public MyAppointmentDetailVO getMyAppointment(String userNo, String appointmentNo) {
        DelegatedIdentityContext.outside();
        return BeautyBookingResponses.identity(BeautyServiceCalls.call(() ->
                BeautyBookingResponses.require(api.getMyAppointment(new
                BeautyBookingInternalApi.GetMyAppointment(userNo, appointmentNo,
                DelegatedIdentityContext.current(userNo))))), appointmentNo);
    }
    @Override
    public AppointmentChangePreviewVO previewMyAppointmentChange(String userNo, String appointmentNo,
            PreviewMyAppointmentChangeReq req) {
        DelegatedIdentityContext.outside();
        return BeautyServiceCalls.call(() -> BeautyBookingResponses.require(api.previewMyAppointmentChange(new
                BeautyBookingInternalApi.PreviewMyAppointmentChange(userNo, appointmentNo, req,
                DelegatedIdentityContext.current(userNo)))));
    }
    @Override
    public MyAppointmentVO changeMyAppointment(String userNo, String appointmentNo, ChangeAppointmentReq req,
            String idempotencyKey) {
        DelegatedIdentityContext.outside();
        return BeautyBookingResponses.identity(BeautyServiceCalls.call(() ->
                BeautyBookingResponses.require(api.changeMyAppointment(new
                BeautyBookingInternalApi.ChangeMyAppointment(userNo, appointmentNo, req, idempotencyKey,
                DelegatedIdentityContext.current(userNo))))), appointmentNo);
    }
    @Override
    public CancellationVO cancelMyAppointment(String userNo, String appointmentNo, CancelAppointmentReq req,
            String idempotencyKey) {
        DelegatedIdentityContext.outside();
        return BeautyBookingResponses.identity(BeautyServiceCalls.call(() ->
                BeautyBookingResponses.require(api.cancelMyAppointment(new
                BeautyBookingInternalApi.CancelMyAppointment(userNo, appointmentNo, req, idempotencyKey,
                DelegatedIdentityContext.current(userNo))))), appointmentNo);
    }
}
