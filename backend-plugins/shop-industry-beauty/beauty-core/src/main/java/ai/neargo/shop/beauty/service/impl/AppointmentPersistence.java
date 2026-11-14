package ai.neargo.shop.beauty.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.beauty.entity.BtyAppointment;
import ai.neargo.shop.beauty.entity.BtyAppointmentCurrent;
import ai.neargo.shop.beauty.entity.BtyAppointmentOperation;
import ai.neargo.shop.beauty.entity.BtyAppointmentPreparation;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Shared local transaction primitives; business callers keep all remote owner IO outside these boundaries. */
public final class AppointmentPersistence {
    final BeautyMappers.AppointmentMapper roots;
    final BeautyMappers.AppointmentPreparationMapper preparations;
    final BeautyMappers.AppointmentOperationMapper operations;
    final BeautyMappers.AppointmentCurrentMapper currents;
    final BeautyMappers.AppointmentArrangementMapper arrangements;
    final BeautyMappers.AppointmentResourceIntentMapper intents;
    final BeautyMappers.AppointmentResourceFenceMapper fences;
    final BeautyMappers.AppointmentItemMapper items;
    final BeautyMappers.AppointmentAssignmentMapper assignments;
    final BeautyMappers.ActionLogMapper actions;
    final ObjectMapper json;
    private final TransactionTemplate writes;

    public AppointmentPersistence(BeautyMappers.AppointmentMapper roots,
            BeautyMappers.AppointmentPreparationMapper preparations, BeautyMappers.AppointmentOperationMapper
                    operations,
            BeautyMappers.AppointmentCurrentMapper currents, BeautyMappers.AppointmentArrangementMapper arrangements,
            BeautyMappers.AppointmentResourceIntentMapper intents, BeautyMappers.AppointmentResourceFenceMapper fences,
            BeautyMappers.AppointmentItemMapper items, BeautyMappers.AppointmentAssignmentMapper assignments,
            BeautyMappers.ActionLogMapper actions, ObjectMapper json, PlatformTransactionManager manager) {
        this.roots = roots;
        this.preparations = preparations;
        this.operations = operations;
        this.currents = currents;
        this.arrangements = arrangements;
        this.intents = intents;
        this.fences = fences;
        this.items = items;
        this.assignments = assignments;
        this.actions = actions;
        this.json = json;
        writes = new TransactionTemplate(manager);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public <T> T tx(Supplier<T> body) {
        return DataScopeContext.executeWithoutScope(() -> writes.execute(status -> body.get()));
    }
    public <T> T read(Supplier<T> body) { return DataScopeContext.executeWithoutScope(body); }
    public BtyAppointment root(String number) {
        return read(() -> roots.selectOne(Wrappers.<BtyAppointment>lambdaQuery()
                .eq(BtyAppointment::getTenantNo, "MAIN").eq(BtyAppointment::getAppointmentNo, number)));
    }
    public BtyAppointment lock(BtyAppointment root) {
        return roots.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(), root.getAppointmentNo());
    }
    public BtyAppointmentCurrent current(BtyAppointment root) {
        return read(() -> currents.selectOne(Wrappers.<BtyAppointmentCurrent>lambdaQuery()
                .eq(BtyAppointmentCurrent::getTenantNo, "MAIN").eq(BtyAppointmentCurrent::getEntityNo,
                        root.getEntityNo())
                .eq(BtyAppointmentCurrent::getAppointmentNo, root.getAppointmentNo())));
    }
    public BtyAppointmentPreparation preview(String number) {
        var value = read(() -> preparations.selectOne(Wrappers.<BtyAppointmentPreparation>lambdaQuery()
                .eq(BtyAppointmentPreparation::getTenantNo, "MAIN").eq(BtyAppointmentPreparation::getPreviewNo,
                        number)));
        if (value == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        return value;
    }
    public BtyAppointmentOperation operation(String number) {
        var value = read(() -> operations.selectOne(Wrappers.<BtyAppointmentOperation>lambdaQuery()
                .eq(BtyAppointmentOperation::getTenantNo, "MAIN").eq(BtyAppointmentOperation::getOperationNo, number)));
        if (value == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        return value;
    }
    public void clearCurrent(BtyAppointment root) {
        currents.update(null, Wrappers.<BtyAppointmentCurrent>lambdaUpdate()
                .eq(BtyAppointmentCurrent::getTenantNo, "MAIN").eq(BtyAppointmentCurrent::getEntityNo,
                        root.getEntityNo())
                .eq(BtyAppointmentCurrent::getAppointmentNo, root.getAppointmentNo())
                .set(BtyAppointmentCurrent::getOperationNo, null));
    }
    public static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC).withNano(0); }
    public static void require(boolean condition) {
        if (!condition) { throw BizException.of(ErrorCode.CONFLICT); }
    }
}
