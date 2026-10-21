package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.dto.AppointmentAssignmentReq;
import ai.neargo.shop.beauty.dto.AppointmentChangePreviewVO;
import ai.neargo.shop.beauty.dto.AppointmentDetailVO;
import ai.neargo.shop.beauty.dto.AppointmentItemReq;
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
import ai.neargo.shop.beauty.dto.PreviewAppointmentChangeReq;
import ai.neargo.shop.beauty.dto.PreviewAppointmentReq;
import ai.neargo.shop.beauty.dto.PreviewMyAppointmentChangeReq;
import ai.neargo.shop.beauty.dto.PreviewMyAppointmentReq;
import ai.neargo.shop.beauty.dto.RecordNoShowReq;
import ai.neargo.shop.beauty.entity.BtyAppointment;
import ai.neargo.shop.beauty.entity.BtyAppointmentArrangement;
import ai.neargo.shop.beauty.entity.BtyAppointmentOperation;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.service.AppointmentService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.idem.IdempotencyScope;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Public appointment use cases derive scope from live actors and persisted previews, never a client frozen plan. */
public final class AppointmentServiceImpl implements AppointmentService {
    private final AppointmentPersistence db;
    private final AppointmentAccess access;
    private final AppointmentMatching matching;
    private final AppointmentPreparationService preparations;
    private final AppointmentCoordinator coordinator;
    public AppointmentServiceImpl(AppointmentPersistence db, AppointmentAccess access, AppointmentMatching matching,
            AppointmentPreparationService preparations, AppointmentCoordinator coordinator) {
        this.db = db; this.access = access; this.matching = matching;
        this.preparations = preparations; this.coordinator = coordinator;
    }
    @Override
    public AppointmentPreviewVO previewAppointment(String entity, String store, String actor, PreviewAppointmentReq
            req) {
        access.merchant(entity, store, actor, true);
        AppointmentPersistence.require(store.equals(req.storeNo()));
        var scope = matching.scope(store); AppointmentPersistence.require(entity.equals(scope.entityNo()));
        return preparations.save(matching.match(scope, false, req.items(), req.requestedAssignments(), null,
                req.startAt()), req.customerRef(), null, 0);
    }
    @Override
    public AppointmentPreviewVO previewMyAppointment(String user, PreviewMyAppointmentReq req) {
        access.consumer(user);
        return preparations.save(matching.match(matching.scope(req.storeNo()), true, req.items(), List.of(),
                req.requestedPersonnelNo(), req.startAt()), user, null, 0);
    }
    @Override
    public AppointmentVO createAppointment(String entity, String store, String actor, CreateAppointmentReq req,
            String key) {
        access.merchant(entity, store, actor, true);
        var p = db.preview(req.previewNo());
        AppointmentPersistence.require(entity.equals(p.getEntityNo()) && store.equals(p.getStoreNo()));
        // The preview's canonical customer is immutable. Re-resolve a supplied member reference before comparing.
        preparations.customer(p, req.customerRef());
        return create(req.previewNo(), req.sourceRefs(), req.consentRef(), key, db.json.writeValueAsString(req));
    }
    @Override
    public MyAppointmentVO createMyAppointment(String user, CreateMyAppointmentReq req, String key) {
        access.consumer(user);
        return mine(create(req.previewNo(), req.sourceRefs(), req.consentRef(), key, db.json.writeValueAsString(req)));
    }
    private AppointmentVO create(String preview, List<String> refs, String consent, String key, String request) {
        financial(refs, consent);
        var p = db.preview(preview); preparations.own(p);
        AppointmentPersistence.require(p.getAppointmentNo() == null);
        var op = preparations.prepare(p, null, 0, "CREATE", key, IdempotencyScope.requestHash("CREATE", request), null,
                db.json.writeValueAsString(Map.of("previewNo", preview)));
        return outcome(coordinator.advance(op.getOperationNo()));
    }
    @Override
    public AppointmentChangePreviewVO previewAppointmentChange(String entity, String store, String actor,
            String number, PreviewAppointmentChangeReq req) {
        var root = merchantRoot(entity, store, actor, number, true);
        return changePreview(root, req.expectedRevision(), req.items(), req.startAt(), req.assignments(), null, false);
    }
    @Override
    public AppointmentChangePreviewVO previewMyAppointmentChange(String user, String number,
            PreviewMyAppointmentChangeReq req) {
        access.consumer(user); var root = owned(number, true);
        return changePreview(root, req.expectedRevision(), req.items(), req.startAt(), List.of(),
                req.requestedPersonnelNo(), true);
    }
    private AppointmentChangePreviewVO changePreview(BtyAppointment root, Long expected, List<AppointmentItemReq> items,
            OffsetDateTime start, List<AppointmentAssignmentReq> assignments, String person, boolean publicOnly) {
        revision(root, expected);
        AppointmentPersistence.require(db.current(root) != null && "CONFIRMED".equals(root.getStatus()));
        var match = matching.match(matching.scope(root.getStoreNo()), publicOnly, items, assignments, person, start,
                coordinator.replacement(root));
        var preview = preparations.save(match, root.getCustomerRef(), root, expected);
        return new AppointmentChangePreviewVO(preview.previewNo(), expected, preview);
    }
    @Override
    public AppointmentVO changeAppointment(String entity, String store, String actor, String number,
            ChangeAppointmentReq req, String key) {
        return change(merchantRoot(entity, store, actor, number, true), req, key);
    }
    @Override
    public MyAppointmentVO changeMyAppointment(String user, String number, ChangeAppointmentReq req, String key) {
        access.consumer(user); return mine(change(owned(number, true), req, key));
    }
    private AppointmentVO change(BtyAppointment root, ChangeAppointmentReq req, String key) {
        financial(List.of(), req.consentRef());
        String hash = IdempotencyScope.requestHash("CHANGE", root.getAppointmentNo(), db.json.writeValueAsString(req));
        var prior = preparations.find(root.getEntityNo(), root.getStoreNo(), key, hash);
        if (prior != null) { return outcome(coordinator.advance(prior.getOperationNo())); }
        revision(root, req.expectedRevision()); AppointmentPersistence.require("CONFIRMED".equals(root.getStatus()));
        var preview = db.preview(req.previewNo()); preparations.own(preview);
        AppointmentPersistence.require(root.getAppointmentNo().equals(preview.getAppointmentNo())
                && req.expectedRevision().equals(preview.getExpectedRevision()));
        var op = preparations.prepare(preview, root, req.expectedRevision(), "CHANGE", key, hash,
                coordinator.hold(root), db.json.writeValueAsString(req));
        return outcome(coordinator.advance(op.getOperationNo()));
    }
    @Override
    public CancellationVO cancelAppointment(String entity, String store, String actor, String number,
            CancelAppointmentReq req, String key) {
        return cancel(merchantRoot(entity, store, actor, number, true), req, key, false, null);
    }
    @Override
    public CancellationVO cancelMyAppointment(String user, String number, CancelAppointmentReq req, String key) {
        access.consumer(user); return cancel(owned(number, true), req, key, false, null);
    }
    @Override
    public NoShowVO recordNoShow(String entity, String store, String actor, String number,
            RecordNoShowReq req, String key) {
        var root = merchantRoot(entity, store, actor, number, true);
        AppointmentPersistence.require(!root.getStartAt().isAfter(AppointmentPersistence.now()));
        var result = cancel(root, new CancelAppointmentReq(req.expectedRevision(), req.reason()), key, true,
                db.json.writeValueAsString(req));
        return new NoShowVO(result.appointmentNo(), result.revision(), result.status(), result.operationNo(),
                result.operationState());
    }
    private CancellationVO cancel(BtyAppointment root, CancelAppointmentReq req, String key, boolean noShow, String
            fullRequest) {
        String action = noShow ? "NO_SHOW" : "CANCEL";
        String hash = IdempotencyScope.requestHash(action, root.getAppointmentNo(),
                fullRequest == null ? db.json.writeValueAsString(req) : fullRequest);
        var prior = preparations.find(root.getEntityNo(), root.getStoreNo(), key, hash);
        if (prior != null) { return cancellation(outcome(coordinator.advance(prior.getOperationNo()))); }
        revision(root, req.expectedRevision());
        var last = db.read(() -> db.operations.selectOne(Wrappers.<BtyAppointmentOperation>lambdaQuery()
                .eq(BtyAppointmentOperation::getTenantNo, "MAIN").eq(BtyAppointmentOperation::getEntityNo,
                        root.getEntityNo())
                .eq(BtyAppointmentOperation::getAppointmentNo, root.getAppointmentNo())
                .orderByDesc(BtyAppointmentOperation::getId).last("LIMIT 1")));
        AppointmentPersistence.require(last != null && root.getHoldNo() != null);
        var pointer = db.current(root);
        AppointmentPersistence.require(pointer != null);
        var arrangement = db.read(() -> db.arrangements.selectOne(Wrappers.<BtyAppointmentArrangement>lambdaQuery()
                .eq(BtyAppointmentArrangement::getTenantNo, "MAIN")
                .eq(BtyAppointmentArrangement::getEntityNo, root.getEntityNo())
                .eq(BtyAppointmentArrangement::getAppointmentNo, root.getAppointmentNo())
                .eq(BtyAppointmentArrangement::getArrangementRevision, pointer.getArrangementRevision())));
        var preview = db.preview(arrangement == null ? last.getPreviewNo() : arrangement.getPreviewNo());
        // The current arrangement owns the cancellation policy, even after a rejected change preview.
        var current = snapshot(root);
        if (current == null) { current = db.json.readValue(preview.getSnapshot(), AppointmentMatching.Snapshot.class); }
        if (SecurityUtils.requireUser().isConsumer()) {
            AppointmentPersistence.require(current.cancelRule().allowCustomerCancel()
                    && AppointmentPersistence.now().isBefore(root.getStartAt()
                        .minusMinutes(current.cancelRule().freeCancelBeforeMinutes())));
        }
        var op = preparations.prepare(preview, root, req.expectedRevision(), action, key, hash, coordinator.hold(root),
                fullRequest == null ? db.json.writeValueAsString(req) : fullRequest);
        return cancellation(outcome(coordinator.advance(op.getOperationNo())));
    }
    @Override
    public AppointmentDetailVO getAppointment(String entity, String store, String actor, String number) {
        return detail(merchantRoot(entity, store, actor, number, false));
    }
    @Override
    public MyAppointmentDetailVO getMyAppointment(String user, String number) {
        access.consumer(user); var detail = detail(owned(number, false));
        return new MyAppointmentDetailVO(detail.appointment(), detail.items(), detail.assignments(),
                detail.availableActions());
    }
    private AppointmentDetailVO detail(BtyAppointment root) {
        var s = snapshot(root);
        return new AppointmentDetailVO(summary(root), s == null ? List.of() : s.items(),
                s == null ? List.of() : s.assignments(), s != null && "CONFIRMED".equals(root.getStatus())
                        ? List.of("CHANGE", "CANCEL") : List.of());
    }
    private AppointmentMatching.Snapshot snapshot(BtyAppointment root) {
        var current = db.current(root);
        if (current == null) { return null; }
        var arrangement = db.read(() -> db.arrangements.selectOne(Wrappers.<BtyAppointmentArrangement>lambdaQuery()
                .eq(BtyAppointmentArrangement::getTenantNo, "MAIN")
                .eq(BtyAppointmentArrangement::getEntityNo, root.getEntityNo())
                .eq(BtyAppointmentArrangement::getAppointmentNo, root.getAppointmentNo())
                .eq(BtyAppointmentArrangement::getArrangementRevision, current.getArrangementRevision())));
        return arrangement == null ? null : db.json.readValue(arrangement.getSnapshot(),
                AppointmentMatching.Snapshot.class);
    }
    @Override
    public PageData<AppointmentSummaryVO> listAppointments(String entity, String store, String actor,
            AppointmentQuery q) {
        access.merchant(entity, store, actor, false);
        if (q.storeNo() != null && !store.equals(q.storeNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
        var query = query(q.from(), q.to(), q.status()).eq(BtyAppointment::getEntityNo, entity)
                .eq(BtyAppointment::getStoreNo, store);
        if (q.personnelNo() != null) {
            query.apply("EXISTS (SELECT 1 FROM bty_appointment_item i JOIN bty_appointment_assignment a "
                    + "ON a.tenant_no=i.tenant_no AND a.entity_no=i.entity_no "
                    + "AND a.appointment_item_no=i.appointment_item_no "
                    + "JOIN bty_appointment_current c ON c.tenant_no=i.tenant_no AND c.entity_no=i.entity_no "
                    + "AND c.appointment_no=i.appointment_no AND c.arrangement_revision=a.appointment_revision "
                    + "WHERE i.appointment_no=bty_appointment.appointment_no AND i.entity_no=bty_appointment.entity_no "
                    + "AND a.personnel_no={0} AND i.deleted=0 AND a.deleted=0)", q.personnelNo());
        }
        if (q.assignmentState() != null) {
            AppointmentPersistence.require(List.of("ASSIGNED", "UNASSIGNED").contains(q.assignmentState()));
            query.apply(("UNASSIGNED".equals(q.assignmentState()) ? "NOT " : "")
                    + "EXISTS (SELECT 1 FROM bty_appointment_current c "
                    + "WHERE c.appointment_no=bty_appointment.appointment_no "
                    + "AND c.entity_no=bty_appointment.entity_no AND c.arrangement_revision>0)");
        }
        var page = page(query, q.page(), q.size());
        return new PageData<>(page.getRecords().stream().map(this::summary).toList(), page.getTotal(),
                page.getCurrent(), page.getSize());
    }
    @Override
    public PageData<MyAppointmentVO> listMyAppointments(String user, MyAppointmentQuery q) {
        access.consumer(user);
        var page = page(query(q.from(), q.to(), q.status()).eq(BtyAppointment::getCustomerRef, user), q.page(),
                q.size());
        return new PageData<>(page.getRecords().stream().map(root -> new MyAppointmentVO(root.getAppointmentNo(),
                root.getVersion(), root.getStatus(), currentOperation(root), null)).toList(),
                page.getTotal(), page.getCurrent(), page.getSize());
    }
    private LambdaQueryWrapper<BtyAppointment> query(OffsetDateTime from, OffsetDateTime to, String status) {
        var q = Wrappers.<BtyAppointment>lambdaQuery().eq(BtyAppointment::getTenantNo, "MAIN");
        if (from != null) { q.ge(BtyAppointment::getStartAt,
                from.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()); }
        if (to != null) { q.lt(BtyAppointment::getStartAt,
                to.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()); }
        if (status != null) { q.eq(BtyAppointment::getStatus, status); }
        return q.orderByDesc(BtyAppointment::getStartAt, BtyAppointment::getId);
    }
    private Page<BtyAppointment> page(LambdaQueryWrapper<BtyAppointment> query, Integer page, Integer size) {
        int p = page == null ? 1 : page; int n = size == null ? 20 : size;
        AppointmentPersistence.require(p > 0 && p <= 10000 && n > 0 && n <= 100);
        return db.read(() -> db.roots.selectPage(new Page<>(p, n), query));
    }
    private String currentOperation(BtyAppointment root) {
        var current = db.current(root);
        return current == null ? null : current.getOperationNo();
    }
    private AppointmentSummaryVO summary(BtyAppointment root) {
        var current = db.current(root);
        return new AppointmentSummaryVO(root.getAppointmentNo(), root.getStoreNo(), root.getVersion(), root.getStatus(),
                root.getStartAt().atOffset(ZoneOffset.UTC), root.getEndAt().atOffset(ZoneOffset.UTC),
                current == null ? null : current.getArrangementRevision());
    }
    private BtyAppointment merchantRoot(String entity, String store, String actor, String number, boolean write) {
        access.merchant(entity, store, actor, write); var root = owned(number, write);
        if (!entity.equals(root.getEntityNo()) || !store.equals(root.getStoreNo())) { throw
                BizException.of(ErrorCode.FORBIDDEN); }
        return root;
    }
    private BtyAppointment owned(String number, boolean write) {
        var root = db.root(number); access.root(root, write); return root;
    }
    private void revision(BtyAppointment root, Long expected) {
        AppointmentPersistence.require(expected != null && Objects.equals(root.getVersion(), expected));
    }
    private void financial(List<String> refs, String consent) {
        if (refs != null && !refs.isEmpty() || consent != null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
    }
    private AppointmentVO outcome(BtyAppointmentOperation op) {
        var root = db.root(op.getAppointmentNo());
        return new AppointmentVO(root.getAppointmentNo(), root.getVersion(), root.getStatus(), op.getOperationNo(),
                op.getState());
    }
    private MyAppointmentVO mine(AppointmentVO v) {
        return new MyAppointmentVO(v.appointmentNo(), v.revision(), v.status(), v.operationNo(), v.operationState());
    }
    private CancellationVO cancellation(AppointmentVO v) {
        return new CancellationVO(v.appointmentNo(), v.revision(), v.status(), v.operationNo(), v.operationState());
    }
}
