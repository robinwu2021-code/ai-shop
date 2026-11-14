package ai.neargo.shop.pay.channel.followup;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.entity.PayChannelCallLog;
import ai.neargo.shop.pay.channel.entity.PayChannelFollowup;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelCallLogMapper;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelFollowupMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 补查表落库，<b>独立事务</b>（同调用记录）：登记发生在调用方的业务事务里，业务回滚不能带走「这笔结果不明、要查」。
 * 调用坐标（地区、接入模式、币种）从原调用记录取，不在补查表里另存一份。
 */
public final class MybatisFollowUpStore implements FollowUpStore {

    static final String PENDING = "PENDING";
    private final PayChannelFollowupMapper mapper;
    private final PayChannelCallLogMapper calls;
    private final TransactionTemplate tx;

    public MybatisFollowUpStore(PayChannelFollowupMapper mapper, PayChannelCallLogMapper calls,
                                PlatformTransactionManager payTxManager) {
        this.mapper = mapper;
        this.calls = calls;
        this.tx = new TransactionTemplate(payTxManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * {@code open_key} = 操作|渠道|我方单号。表设计是「操作|stl_payment_id|pay_refund_id」，但调用元数据里的这两个 id
     * 要到 I3 拆单后才有；单号在同一渠道内同样唯一。
     */
    static String openKey(FollowUpTask t) {
        return t.operation() + "|" + t.payChannel() + "|" + t.outNo();
    }

    @Override
    public boolean open(FollowUpTask task) {
        PayChannelFollowup row = new PayChannelFollowup();
        row.setInvocationNo(task.invocationNo());
        row.setTraceId(task.traceId());
        row.setOperation(task.operation());
        row.setPayChannel(task.payChannel());
        row.setStatus(PENDING);
        row.setOpenKey(openKey(task));
        row.setAttempts(0);
        row.setNextAt(at(task.nextAt()));
        try {
            tx.executeWithoutResult(s -> DataScopeContext.executeWithoutScope(() -> mapper.insert(row)));
            return true;
        } catch (DuplicateKeyException alreadyOpen) {
            return false;
        }
    }

    @Override
    public List<FollowUpTask> due(Instant now, int limit) {
        List<PayChannelFollowup> rows = DataScopeContext.executeWithoutScope(() -> mapper.selectList(
                Wrappers.<PayChannelFollowup>lambdaQuery().eq(PayChannelFollowup::getStatus, PENDING)
                        .le(PayChannelFollowup::getNextAt, at(now)).orderByAsc(PayChannelFollowup::getNextAt)
                        .last("LIMIT " + Math.max(1, limit))));
        List<FollowUpTask> tasks = new ArrayList<>();
        for (PayChannelFollowup r : rows) {
            PayChannelCallLog first = DataScopeContext.executeWithoutScope(() -> calls.selectOne(
                    Wrappers.<PayChannelCallLog>lambdaQuery().eq(PayChannelCallLog::getInvocationNo, r.getInvocationNo())
                            .orderByAsc(PayChannelCallLog::getId).last("LIMIT 1")));
            String outNo = r.getOpenKey().substring(r.getOpenKey().indexOf('|', r.getOperation().length() + 1) + 1);
            tasks.add(new FollowUpTask(r.getId(), r.getVersion() == null ? 0 : r.getVersion(), r.getInvocationNo(),
                    r.getTraceId(), r.getOperation(), r.getPayChannel(), first == null ? null : first.getMarket(),
                    first == null || first.getAccessMode() == null ? null : AccessMode.valueOf(first.getAccessMode()),
                    outNo, first == null ? null : first.getRequestCurrency(),
                    r.getAttempts() == null ? 0 : r.getAttempts(), r.getNextAt().toInstant(ZoneOffset.UTC)));
        }
        return tasks;
    }

    @Override
    public boolean claim(FollowUpTask task, Instant leaseUntil) {
        return write(task, w -> w.set(PayChannelFollowup::getNextAt, at(leaseUntil))) == 1;
    }

    @Override
    public void resolve(FollowUpTask task, String summary) {
        close(task, "RESOLVED", summary);
    }

    @Override
    public void escalate(FollowUpTask task, String summary) {
        close(task, "ESCALATED", summary);
    }

    @Override
    public void retry(FollowUpTask task, Instant nextAt, String summary) {
        write(task, w -> w.set(PayChannelFollowup::getNextAt, at(nextAt))
                .set(PayChannelFollowup::getAttempts, task.attempts() + 1)
                .set(PayChannelFollowup::getLastResult, cut(summary)));
    }

    private void close(FollowUpTask task, String status, String summary) {
        // 关闭时清掉 open_key：同一笔之后再出一次「未知」，要能再开一条
        write(task, w -> w.set(PayChannelFollowup::getStatus, status)
                .set(PayChannelFollowup::getOpenKey, null)
                .set(PayChannelFollowup::getAttempts, task.attempts() + 1)
                .set(PayChannelFollowup::getLastResult, cut(summary))
                .set(PayChannelFollowup::getResolvedAt, LocalDateTime.now(ZoneOffset.UTC)));
    }

    private int write(FollowUpTask task, Consumer<LambdaUpdateWrapper<PayChannelFollowup>> set) {
        var w = Wrappers.<PayChannelFollowup>lambdaUpdate().eq(PayChannelFollowup::getId, task.id())
                .eq(PayChannelFollowup::getStatus, PENDING).eq(PayChannelFollowup::getVersion, task.version())
                .setSql("version = version + 1")
                .set(PayChannelFollowup::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC));
        set.accept(w);
        Integer n = tx.execute(s -> DataScopeContext.executeWithoutScope(() -> mapper.update(null, w)));
        return n == null ? 0 : n;
    }

    private static String cut(String s) {
        return s == null || s.length() <= 255 ? s : s.substring(0, 255);
    }

    private static LocalDateTime at(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
