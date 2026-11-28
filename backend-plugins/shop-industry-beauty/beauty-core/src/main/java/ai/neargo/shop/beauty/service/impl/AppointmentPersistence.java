package ai.neargo.shop.beauty.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.beauty.entity.BtyAppointment;
import ai.neargo.shop.beauty.entity.BtyAppointmentArrangement;
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
    final ObjectMapper json;
    private final TransactionTemplate writes;
    /** 行业库（工单、服务任务等服务执行的表）的写事务；行业句柄未绑定时与 {@link #writes} 是同一个库。 */
    private final TransactionTemplate industryWrites;

    public AppointmentPersistence(BeautyMappers.AppointmentMapper roots,
            BeautyMappers.AppointmentPreparationMapper preparations, BeautyMappers.AppointmentOperationMapper
                    operations,
            BeautyMappers.AppointmentCurrentMapper currents, BeautyMappers.AppointmentArrangementMapper arrangements,
            BeautyMappers.AppointmentResourceIntentMapper intents, BeautyMappers.AppointmentResourceFenceMapper fences,
            BeautyMappers.AppointmentItemMapper items,
            ObjectMapper json, PlatformTransactionManager manager) {
        this(roots, preparations, operations, currents, arrangements, intents, fences, items,
                json, manager, manager);
    }

    /**
     * 预约在交易库、服务执行在行业库：两个事务管理器分开给。{@link #tx} 写预约，{@link #industryTx} 写工单与服务执行。
     */
    public AppointmentPersistence(BeautyMappers.AppointmentMapper roots,
            BeautyMappers.AppointmentPreparationMapper preparations, BeautyMappers.AppointmentOperationMapper
                    operations,
            BeautyMappers.AppointmentCurrentMapper currents, BeautyMappers.AppointmentArrangementMapper arrangements,
            BeautyMappers.AppointmentResourceIntentMapper intents, BeautyMappers.AppointmentResourceFenceMapper fences,
            BeautyMappers.AppointmentItemMapper items,
            ObjectMapper json, PlatformTransactionManager manager,
            PlatformTransactionManager industryManager) {
        this.roots = roots;
        this.preparations = preparations;
        this.operations = operations;
        this.currents = currents;
        this.arrangements = arrangements;
        this.intents = intents;
        this.fences = fences;
        this.items = items;
        this.json = json;
        writes = new TransactionTemplate(manager);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        industryWrites = new TransactionTemplate(industryManager);
        industryWrites.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        industryWrites.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public <T> T tx(Supplier<T> body) {
        return DataScopeContext.executeWithoutScope(() -> writes.execute(status -> body.get()));
    }
    public <T> T industryTx(Supplier<T> body) {
        return DataScopeContext.executeWithoutScope(() -> industryWrites.execute(status -> body.get()));
    }
    public <T> T read(Supplier<T> body) { return DataScopeContext.executeWithoutScope(body); }
    /**
     * 只保留属于这张工单来源预约的任务。任务（行业库）与预约明细（交易库）分库后不能在一条 SQL 里联查，
     * 读出来再按预约明细核对：明细不存在、已删除或属于别的预约的任务不暴露。
     */
    public java.util.List<ai.neargo.shop.beauty.entity.BtyServiceTask> ownedTasks(String entity, String appointmentNo,
            java.util.List<ai.neargo.shop.beauty.entity.BtyServiceTask> rows) {
        var itemNos = rows.stream().map(ai.neargo.shop.beauty.entity.BtyServiceTask::getAppointmentItemNo)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (itemNos.isEmpty()) { return rows; }
        var owned = read(() -> items.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.beauty.entity.BtyAppointmentItem>lambdaQuery()
                        .eq(ai.neargo.shop.beauty.entity.BtyAppointmentItem::getTenantNo, "MAIN")
                        .eq(ai.neargo.shop.beauty.entity.BtyAppointmentItem::getEntityNo, entity)
                        .in(ai.neargo.shop.beauty.entity.BtyAppointmentItem::getAppointmentItemNo, itemNos)
                        .eq(ai.neargo.shop.beauty.entity.BtyAppointmentItem::getDeleted, 0)))
                .stream().filter(item -> java.util.Objects.equals(item.getAppointmentNo(), appointmentNo))
                .map(ai.neargo.shop.beauty.entity.BtyAppointmentItem::getAppointmentItemNo)
                .collect(java.util.stream.Collectors.toSet());
        return rows.stream().filter(task -> task.getAppointmentItemNo() == null
                || owned.contains(task.getAppointmentItemNo())).toList();
    }
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
    /** 预览即草稿修订（TDD §2.6 第 4–6 项，步 4-3）；确认后同一行升为生效版，按预览号仍能找到。 */
    public BtyAppointmentArrangement preview(String number) {
        var value = read(() -> arrangements.selectOne(Wrappers.<BtyAppointmentArrangement>lambdaQuery()
                .eq(BtyAppointmentArrangement::getTenantNo, "MAIN").eq(BtyAppointmentArrangement::getPreviewNo,
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
