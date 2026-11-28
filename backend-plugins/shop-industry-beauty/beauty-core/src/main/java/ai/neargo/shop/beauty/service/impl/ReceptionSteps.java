package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.entity.BtyReceptionOperation;
import ai.neargo.shop.beauty.mapper.BeautyMappers;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 接待操作（{@code bty_reception_operation}，行业库）唯一的写入口（TDD-美业域迁移新库 §2.2.1）：只有这里开事务、锁接待操作。
 * 业务代码只描述每一步做什么；换库、调整先后顺序只改这里。约束测试 {@code ReceptionStepsBoundaryTest} 守住这一点。
 *
 * <p>推进一步（{@link #advance}）的顺序固定为：先在交易事务里做预约侧（锁预约、核对或写入），再在行业事务里锁住接待操作、
 * 阶段仍是出发阶段才落结果。预约侧的写入必须能安全重做（按接待操作里冻结的键判断做过没有）：第一步提交后进程断了，
 * 接待操作停在出发阶段，恢复时再调同一步即可。
 */
public final class ReceptionSteps {
    private final AppointmentPersistence db;
    private final BeautyMappers.ReceptionOperationMapper rows;

    public ReceptionSteps(AppointmentPersistence db, BeautyMappers.ReceptionOperationMapper rows) {
        this.db = db;
        this.rows = rows;
    }

    /**
     * 受理：插入接待操作。{@code tradeGuard}（门店归属锁等主库侧核对）先在交易事务里跑；重复键照常抛出，由调用方按幂等键找赢家。
     */
    public BtyReceptionOperation insert(BtyReceptionOperation op, Runnable tradeGuard) {
        if (tradeGuard != null) db.tx(() -> { tradeGuard.run(); return null; });
        db.industryTx(() -> rows.insert(op));
        return op;
    }

    /** 只改接待操作：在行业事务里锁住这一行再交给 {@code change}；{@code change} 里用 {@link #save} 写回。 */
    public <R> R update(BtyReceptionOperation observed, Function<BtyReceptionOperation, R> change) {
        return db.industryTx(() -> change.apply(lock(observed)));
    }

    /**
     * 推进一步：阶段在 {@code from} 里才做。
     *
     * @param trade 交易事务里做预约侧，返回凭证；写入必须能安全重做
     * @param apply 行业事务里、锁住接待操作且阶段仍在 {@code from} 时，把凭证落到接待操作上
     * @param otherwise 阶段已不在 {@code from}（别人推进过了）时怎么回
     */
    public <E, R> R advance(BtyReceptionOperation observed, Set<String> from, Supplier<E> trade,
            BiFunction<BtyReceptionOperation, E, R> apply, Function<BtyReceptionOperation, R> otherwise) {
        var current = read(observed);
        if (!from.contains(current.getPhase())) return otherwise.apply(current);
        E evidence;
        try {
            evidence = db.tx(trade::get);
        } catch (RuntimeException failure) {
            // 预约侧核对失败，可能是别人刚把这一步推进过去了：那就按推进后的样子回
            var latest = read(observed);
            if (!from.contains(latest.getPhase())) return otherwise.apply(latest);
            throw failure;
        }
        return update(observed, row -> from.contains(row.getPhase()) ? apply.apply(row, evidence)
                : otherwise.apply(row));
    }

    /**
     * 一次性接待（签到、接待异常）：先受理、再做预约侧、最后落结果。预约侧失败时撤回刚受理的那一行，
     * 与原来同一个事务里失败不留痕一致；同一个幂等键重试照常从头来。
     */
    public <E, R> R once(BtyReceptionOperation received, Supplier<E> trade,
            BiFunction<BtyReceptionOperation, E, R> apply, Function<BtyReceptionOperation, R> otherwise) {
        var current = read(received);
        if (!"RECEIVED".equals(current.getPhase())) return otherwise.apply(current);
        E evidence;
        try {
            evidence = db.tx(trade::get);
        } catch (RuntimeException failure) {
            var latest = read(received);
            if (!"RECEIVED".equals(latest.getPhase())) return otherwise.apply(latest);
            // 只在预约侧没做成时撤回；预约侧已提交而落结果失败时保留这一行，重试按它补结果
            if (latest.getResult() == null)
                db.industryTx(() -> rows.discardReceived(latest.getEntityNo(), latest.getStoreNo(),
                        latest.getOperationNo(), latest.getVersion()));
            throw failure;
        }
        return update(received, row -> "RECEIVED".equals(row.getPhase()) ? apply.apply(row, evidence)
                : otherwise.apply(row));
    }

    /** 在 {@link #update}、{@link #advance} 的回调里写回锁住的那一行。 */
    public void save(BtyReceptionOperation row) {
        AppointmentPersistence.require(rows.updateById(row) == 1);
    }

    /** 停止自动重试：清掉下次重试时间与租约。 */
    public void clearRetry(BtyReceptionOperation row) {
        rows.update(null, Wrappers.<BtyReceptionOperation>lambdaUpdate()
                .eq(BtyReceptionOperation::getId, row.getId())
                .set(BtyReceptionOperation::getNextRetryAt, null)
                .set(BtyReceptionOperation::getLeaseUntil, null));
    }

    /** 恢复扫描抢占这一行的租约；没抢到返回 false。 */
    public boolean claimResource(BtyReceptionOperation row, LocalDateTime claimAt, LocalDateTime lease) {
        return db.industryTx(() -> rows.claimResource(row.getEntityNo(), row.getStoreNo(), row.getOperationNo(),
                row.getVersion(), claimAt, lease) == 1);
    }

    /** 恢复扫描收尾：租约还是自己的才放掉，并按需排下次重试。 */
    public void releaseResource(BtyReceptionOperation row, LocalDateTime lease, Function<BtyReceptionOperation,
            LocalDateTime> nextRetryAt) {
        update(row, locked -> {
            if (!Objects.equals(lease, locked.getLeaseUntil())) return null;
            rows.update(null, Wrappers.<BtyReceptionOperation>lambdaUpdate()
                    .eq(BtyReceptionOperation::getId, locked.getId())
                    .eq(BtyReceptionOperation::getVersion, locked.getVersion())
                    .eq(BtyReceptionOperation::getLeaseUntil, lease)
                    .set(BtyReceptionOperation::getNextRetryAt, nextRetryAt.apply(locked))
                    .set(BtyReceptionOperation::getLeaseUntil, null));
            return null;
        });
    }

    private BtyReceptionOperation lock(BtyReceptionOperation observed) {
        var row = rows.selectScopedForUpdate(observed.getEntityNo(), observed.getStoreNo(), observed.getOperationNo());
        AppointmentPersistence.require(row != null);
        return row;
    }

    private BtyReceptionOperation read(BtyReceptionOperation observed) {
        var row = db.read(() -> rows.selectScoped(observed.getEntityNo(), observed.getStoreNo(),
                observed.getOperationNo()));
        AppointmentPersistence.require(row != null);
        return row;
    }
}
