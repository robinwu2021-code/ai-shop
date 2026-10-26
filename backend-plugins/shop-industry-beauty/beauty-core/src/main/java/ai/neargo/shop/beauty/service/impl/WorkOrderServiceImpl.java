package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.dto.MyWorkOrderVO;
import ai.neargo.shop.beauty.dto.OpenWorkOrderReq;
import ai.neargo.shop.beauty.dto.ServiceSubmissionVO;
import ai.neargo.shop.beauty.dto.SubmitServicesReq;
import ai.neargo.shop.beauty.dto.WorkOrderBillVO;
import ai.neargo.shop.beauty.dto.WorkOrderDetailVO;
import ai.neargo.shop.beauty.dto.WorkOrderVO;
import ai.neargo.shop.beauty.entity.BtyWorkOrder;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.service.ServiceSubmissionService;
import ai.neargo.shop.beauty.service.WorkOrderBillService;
import ai.neargo.shop.beauty.service.WorkOrderService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;

import org.springframework.beans.factory.ObjectProvider;

import java.util.Objects;

/** A local scope service keeps order preparation and authorized reads on the same domain boundary. */
public final class WorkOrderServiceImpl implements WorkOrderService {
    private final WorkOrderCoordinator coordinator;
    private final WorkOrderQuery query;
    private final AppointmentPersistence db;
    private final AppointmentAccess access;
    private final BeautyMappers.WorkOrderMapper orders;
    private final ServiceSubmissionService submissions;
    private final WorkOrderBillService bills;
    private BeautyMappers.WorkOrderContextMapper contexts;
    private ObjectProvider<GuestRegistrationPort> guests;

    public WorkOrderServiceImpl(
            WorkOrderCoordinator coordinator,
            WorkOrderQuery query,
            AppointmentPersistence db,
            AppointmentAccess access,
            BeautyMappers.WorkOrderMapper orders,
            ServiceSubmissionService submissions,
            WorkOrderBillService bills) {
        this.coordinator = coordinator; this.query = query; this.db = db; this.access = access; this.orders = orders;
        this.submissions = submissions;
        this.bills = bills;
    }

    public WorkOrderServiceImpl(
            WorkOrderCoordinator coordinator,
            WorkOrderQuery query,
            AppointmentPersistence db,
            AppointmentAccess access,
            BeautyMappers.WorkOrderMapper orders,
            ServiceSubmissionService submissions,
            WorkOrderBillService bills,
            BeautyMappers.WorkOrderContextMapper contexts,
            ObjectProvider<GuestRegistrationPort> guests) {
        this(coordinator, query, db, access, orders, submissions, bills);
        this.contexts = contexts;
        this.guests = guests;
    }

    @Override public WorkOrderVO openWorkOrder(String entity, String store, String actor, OpenWorkOrderReq req,
            String key) { return coordinator.open(entity, store, actor, req, key); }
    @Override public WorkOrderDetailVO getWorkOrder(String entity, String store, String actor, String number) {
        access.merchant(entity, store, actor, false);
        return query.detail(require(db.read(() -> orders.selectScoped(entity, store, number))));
    }
    @Override public MyWorkOrderVO getMyWorkOrder(String user, String number) {
        access.consumer(user);
        var before = SecurityUtils.requireUser();
        var root = db.read(() -> orders.selectOwned(user, number));
        if (root == null) {
            root = require(db.read(() -> orders.selectClaimedCandidate(user, number)));
            final var candidate = root;
            var context =
                    contexts == null
                            ? null
                            : db.read(
                                    () ->
                                            contexts.selectScoped(
                                                    candidate.getEntityNo(),
                                                    candidate.getStoreNo(),
                                                    number));
            if (context == null || !"GUEST".equals(context.getIdentityKind()))
                throw BizException.of(ErrorCode.NOT_FOUND);
            var owner = guests == null ? null : guests.getIfAvailable();
            if (owner == null) throw BizException.of(ErrorCode.NOT_FOUND);
            var claim =
                    owner.currentClaim(
                            new GuestRegistrationPort.ConsumerScope(
                                    before.tenantNo(),
                                    new DelegatedIdentity(
                                            before.tenantNo(),
                                            before.realm().name(),
                                            before.subjectKind().name(),
                                            before.userNo())),
                            new GuestRegistrationPort.GuestRef(
                                    root.getEntityNo(),
                                    root.getStoreNo(),
                                    context.getIdentityRef()));
            access.consumer(user);
            var current = SecurityUtils.requireUser();
            if (!before.tenantNo().equals(current.tenantNo())
                    || !before.realm().equals(current.realm())
                    || !before.subjectKind().equals(current.subjectKind())
                    || !before.userNo().equals(current.userNo())
                    || claim == null
                    || !user.equals(claim.userNo())
                    || claim.claimRevision() != 2
                    || !root.getEntityNo().equals(claim.entityNo())
                    || !root.getStoreNo().equals(claim.storeNo())
                    || !context.getIdentityRef().equals(claim.guestNo()))
                throw BizException.of(ErrorCode.NOT_FOUND);
            var reread =
                    require(
                            db.read(
                                    () ->
                                            orders.selectClaimed(
                                                    candidate.getEntityNo(),
                                                    candidate.getStoreNo(),
                                                    context.getIdentityRef(),
                                                    number)));
            var rereadContext =
                    db.read(
                            () ->
                                    contexts.selectScoped(
                                            candidate.getEntityNo(),
                                            candidate.getStoreNo(),
                                            number));
            if (!Objects.equals(root.getVersion(), reread.getVersion())
                    || rereadContext == null
                    || !Objects.equals(context.getVersion(), rereadContext.getVersion())
                    || !context.getIdentityKind().equals(rereadContext.getIdentityKind())
                    || !context.getIdentityRef().equals(rereadContext.getIdentityRef())
                    || !root.getCustomerRef().equals(reread.getCustomerRef()))
                throw BizException.of(ErrorCode.NOT_FOUND);
            root = reread;
        }
        var detail = query.detail(root);
        return new MyWorkOrderVO(
                detail.workOrder(),
                detail.originalScopeRevision(),
                detail.tasks(),
                detail.availableActions());
    }

    @Override
    public ServiceSubmissionVO submitServices(
            String entity,
            String store,
            String actor,
            String workOrder,
            SubmitServicesReq req,
            String key) {
        return submissions.submitForWorkOrder(entity, store, actor, workOrder, req, key);
    }

    @Override
    public WorkOrderBillVO getBill(String entity, String store, String actor, String workOrder) {
        return bills.getBill(entity, store, actor, workOrder);
    }

    private BtyWorkOrder require(BtyWorkOrder root) {
        if (root == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        return root;
    }
}
