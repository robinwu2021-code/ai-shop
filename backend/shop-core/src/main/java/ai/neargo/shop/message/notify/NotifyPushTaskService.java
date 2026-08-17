package ai.neargo.shop.message.notify;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.message.entity.MsgPushToken;
import ai.neargo.shop.message.entity.NotifyPushTask;
import ai.neargo.shop.message.mapper.MessageMappers.PushTaskMapper;
import ai.neargo.shop.message.mapper.MessageMappers.PushTokenMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 平台营销广播推送任务（设计：触达推送中台-模块抽象 · N6）。
 *
 * <p>运营主动发起的一次群发：圈人群、预估触达、定时下发。到点由
 * {@code NotifyPushTaskJob}（worker）捡起 {@link #dispatchDue()}。
 *
 * <p><b>与事件驱动触达分开</b>：那个是「系统必须告诉你」（钱扣了/货到了，站内信必达），
 * 这个是「平台想推给你」（营销，opt-in —— 只发给装了 App、有推送令牌的人）。
 */
@Service
public class NotifyPushTaskService {

    private static final Logger log = LoggerFactory.getLogger(NotifyPushTaskService.class);

    private final PushTaskMapper taskMapper;
    private final PushTokenMapper tokenMapper;
    private final PushSender pushSender;

    public NotifyPushTaskService(PushTaskMapper taskMapper, PushTokenMapper tokenMapper,
                                 PushSender pushSender) {
        this.taskMapper = taskMapper;
        this.tokenMapper = tokenMapper;
        this.pushSender = pushSender;
    }

    /** 创建任务。创建时**预估触达**（当下人群规模），落 QUEUED。 */
    @Transactional
    public NotifyPushTask create(String name, String audienceType, String title, String body,
                                 String link, LocalDateTime scheduledAt, String operator) {
        if (name == null || name.isBlank() || title == null || title.isBlank()
                || body == null || body.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<String> audience = resolveAudience(audienceType);
        NotifyPushTask t = new NotifyPushTask();
        t.setTaskNo(BizKey.next(BizKey.PUSH_TASK));
        t.setName(name);
        t.setAudienceType(audienceType);
        t.setChannel("PUSH");
        t.setTitle(title);
        t.setBody(body);
        t.setLink(link);
        t.setScheduledAt(scheduledAt);
        t.setStatus(NotifyPushTask.STATUS_QUEUED);
        t.setEstimatedCount(audience.size());
        t.setSentCount(0);
        t.setCreatedBy(operator);
        DataScopeContext.executeWithoutScope(() -> taskMapper.insert(t));
        return t;
    }

    public PageData<NotifyPushTask> list(String status, long page, long size) {
        var q = Wrappers.<NotifyPushTask>lambdaQuery()
                .eq(status != null && !status.isBlank(), NotifyPushTask::getStatus, status)
                .orderByDesc(NotifyPushTask::getId);
        return DataScopeContext.executeWithoutScope(
                () -> PageData.of(taskMapper.selectPage(Page.of(page, size), q)));
    }

    /** 取消。**只有 QUEUED 能取消** —— 已在下发或已完成的拦不住了。 */
    @Transactional
    public NotifyPushTask cancel(String taskNo, String operator) {
        NotifyPushTask t = byNo(taskNo);
        if (t == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (!NotifyPushTask.STATUS_QUEUED.equals(t.getStatus())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        t.setStatus(NotifyPushTask.STATUS_CANCELLED);
        t.setUpdatedBy(operator);
        DataScopeContext.executeWithoutScope(() -> taskMapper.updateById(t));
        return t;
    }

    /**
     * 下发所有到点的 QUEUED 任务，返回处理的任务数。worker 定时调。
     *
     * <p>**逐任务标 RUNNING→DONE**：崩在中途的话，重跑会从 RUNNING 的接着来（幂等由
     * 推送侧的 dedup 兜，且营销推送重复一条的代价远小于漏发）。
     */
    public int dispatchDue() {
        LocalDateTime now = LocalDateTime.now();
        List<NotifyPushTask> due = DataScopeContext.executeWithoutScope(() ->
                taskMapper.selectList(Wrappers.<NotifyPushTask>lambdaQuery()
                        .eq(NotifyPushTask::getStatus, NotifyPushTask.STATUS_QUEUED)
                        .and(w -> w.isNull(NotifyPushTask::getScheduledAt)
                                .or().le(NotifyPushTask::getScheduledAt, now))));
        for (NotifyPushTask t : due) {
            dispatchOne(t);
        }
        return due.size();
    }

    private void dispatchOne(NotifyPushTask t) {
        mark(t, NotifyPushTask.STATUS_RUNNING);
        int sent = 0;
        for (String receiverNo : resolveAudience(t.getAudienceType())) {
            try {
                // 营销广播不响铃（响铃是「必须立刻知道」的事，营销不是）
                pushSender.notify(MsgMessage.RECEIVER_USER, receiverNo,
                        t.getTitle(), t.getBody(), t.getLink());
                sent++;
            } catch (RuntimeException e) {
                // 单个失败不拖累整批（推送侧已留痕）；营销漏一条不是事故
                log.warn("[push-task] {} 发给 {} 失败：{}", t.getTaskNo(), receiverNo, e.getMessage());
            }
        }
        t.setSentCount(sent);
        t.setFinishedAt(LocalDateTime.now());
        mark(t, NotifyPushTask.STATUS_DONE);
    }

    private void mark(NotifyPushTask t, String status) {
        t.setStatus(status);
        DataScopeContext.executeWithoutScope(() -> taskMapper.updateById(t));
    }

    private NotifyPushTask byNo(String taskNo) {
        return DataScopeContext.executeWithoutScope(() ->
                taskMapper.selectOne(Wrappers.<NotifyPushTask>lambdaQuery()
                        .eq(NotifyPushTask::getTaskNo, taskNo).last("limit 1")));
    }

    /** 人群 → 收件人编号列表。一期只有「全体装了 App 的消费者」（有推送令牌 = opt-in）。 */
    private List<String> resolveAudience(String audienceType) {
        if (!NotifyPushTask.AUD_ALL_APP_USER.equals(audienceType)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return DataScopeContext.executeWithoutScope(() ->
                tokenMapper.selectList(Wrappers.<MsgPushToken>lambdaQuery()
                        .select(MsgPushToken::getReceiverNo)
                        .eq(MsgPushToken::getReceiverType, MsgMessage.RECEIVER_USER)
                        .groupBy(MsgPushToken::getReceiverNo))
                .stream().map(MsgPushToken::getReceiverNo).toList());
    }
}
