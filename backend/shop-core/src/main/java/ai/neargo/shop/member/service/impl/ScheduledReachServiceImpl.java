package ai.neargo.shop.member.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import org.springframework.dao.DuplicateKeyException;
import ai.neargo.shop.member.entity.MbrReachLog;
import ai.neargo.shop.member.entity.MbrReachTask;
import ai.neargo.shop.member.mapper.MemberMappers.ReachLogMapper;
import ai.neargo.shop.member.mapper.MemberMappers.ReachTaskMapper;
import ai.neargo.shop.member.mapper.ReachRecipientMapper;
import ai.neargo.shop.member.service.ScheduledReachService;
import ai.neargo.shop.spi.member.MemberQueryPort.AudienceItem;
import ai.neargo.shop.spi.notify.UserInboxPort;
import ai.neargo.shop.spi.notify.UserPushPort;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ScheduledReachServiceImpl implements ScheduledReachService {
    private final ReachTaskMapper tasks;
    private final ReachRecipientMapper recipients;
    private final ReachLogMapper logs;
    private final AudienceResolver resolver;
    private final ReachAttribution attribution;
    private final MerchantQueryPort merchants;
    private final MarketPort markets;
    private final UserInboxPort inbox;
    private final UserPushPort push;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public ScheduledReachServiceImpl(ReachTaskMapper tasks, ReachRecipientMapper recipients, ReachLogMapper logs,
            AudienceResolver resolver, ReachAttribution attribution, MerchantQueryPort merchants, MarketPort markets,
            UserInboxPort inbox, UserPushPort push, ObjectMapper json, PlatformTransactionManager manager) {
        this.tasks = tasks;
        this.recipients = recipients;
        this.logs = logs;
        this.resolver = resolver;
        this.attribution = attribution;
        this.merchants = merchants;
        this.markets = markets;
        this.inbox = inbox;
        this.push = push;
        this.json = json;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public String schedule(String entityNo, Request r, String operatorNo, String requestKey) {
        String hash = IdempotencyScope.requestHash(json.writeValueAsString(r), operatorNo);
        String replay = previous(entityNo, requestKey, hash);
        if (replay != null) return replay;
        ZoneId zone = markets.find(merchants.marketOf(entityNo)).filter(MarketPort.MarketBrief::enabled)
                .map(m -> ZoneId.of(m.timeZone())).orElseThrow(() -> BizException.of(ErrorCode.BAD_REQUEST));
        var offsets = zone.getRules().getValidOffsets(r.localTime());
        if (offsets.size() != 1) throw BizException.of(ErrorCode.BAD_REQUEST);
        long at = r.localTime().toInstant(offsets.getFirst()).toEpochMilli();
        if (at <= System.currentTimeMillis()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var estimate = resolver.resolve(entityNo, r.audiences(), r.scene());
        MbrReachTask t = new MbrReachTask();
        t.setTaskNo(BizKey.next(BizKey.REACH));
        t.setEntityNo(entityNo);
        t.setDeliveryStatus("SCHEDULED");
        t.setScheduledAt(at);
        t.setScheduleZone(zone.getId());
        t.setAudienceJson(json.writeValueAsString(r.audiences()));
        t.setAudienceDesc(MemberReachServiceImpl.clip(r.audienceDesc() == null || r.audienceDesc().isBlank()
                ? MemberReachServiceImpl.fallbackDesc(r.audiences()) : r.audienceDesc()));
        t.setScene(r.scene());
        t.setTitle(r.title());
        t.setBody(r.body());
        t.setOperatorNo(operatorNo);
        t.setMatchedCount(estimate.matched());
        t.setSentAt(0L);
        t.setStatsUntil(0L);
        t.setRequestKey(requestKey);
        t.setRequestHash(hash);
        try {
            tasks.insert(t);
        } catch (DuplicateKeyException collision) {
            String winner = previous(entityNo, requestKey, hash);
            if (winner == null) throw collision;
            return winner;
        }
        return t.getTaskNo();
    }

    private String previous(String entityNo, String key, String hash) {
        MbrReachTask row = tasks.selectOne(Wrappers.<MbrReachTask>lambdaQuery()
                .eq(MbrReachTask::getEntityNo, entityNo).eq(MbrReachTask::getRequestKey, key));
        if (row == null) return null;
        if (!hash.equals(row.getRequestHash())) throw BizException.of(ErrorCode.CONFLICT);
        return row.getTaskNo();
    }

    @Override
    public void cancel(String entityNo, String taskNo) {
        int changed = tasks.update(null, Wrappers.<MbrReachTask>lambdaUpdate()
                .eq(MbrReachTask::getEntityNo, entityNo).eq(MbrReachTask::getTaskNo, taskNo)
                .eq(MbrReachTask::getDeliveryStatus, "SCHEDULED").gt(MbrReachTask::getScheduledAt, System.currentTimeMillis())
                .set(MbrReachTask::getDeliveryStatus, "CANCELLED"));
        if (changed == 0) throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    @Override
    public int dispatchDue(int maxRecipientsPerTask) {
        long now = System.currentTimeMillis();
        var due = tasks.selectList(Wrappers.<MbrReachTask>lambdaQuery().select(MbrReachTask::getTaskNo)
                .in(MbrReachTask::getDeliveryStatus, "SCHEDULED", "SENDING")
                .le(MbrReachTask::getScheduledAt, now).orderByAsc(MbrReachTask::getScheduledAt));
        int processed = 0;
        for (var task : due) {
            String no = task.getTaskNo();
            tx.executeWithoutResult(status -> start(no));
            for (int i = 0; i < maxRecipientsPerTask; i++) {
                Delivery delivery = tx.execute(status -> deliverNext(no));
                if (delivery == null) break;
                processed++;
                // 站内信已提交。外部推送尽力且最多尝试一次，恢复不会重推已完成会员。
                if (delivery.userNo() != null && push.pushToUser(delivery.userNo(), delivery.title(),
                        delivery.body(), delivery.link())) {
                    tasks.update(null, Wrappers.<MbrReachTask>lambdaUpdate().eq(MbrReachTask::getTaskNo, no)
                            .setSql("pushed_count = pushed_count + 1"));
                }
            }
        }
        return processed;
    }

    private MbrReachTask lock(String no) {
        return tasks.selectOne(Wrappers.<MbrReachTask>lambdaQuery().eq(MbrReachTask::getTaskNo, no).last("FOR UPDATE"));
    }

    private void start(String no) {
        MbrReachTask t = lock(no);
        if (!"SCHEDULED".equals(t.getDeliveryStatus())) return;
        var items = Arrays.asList(json.readValue(t.getAudienceJson(), AudienceItem[].class));
        var members = List.copyOf(resolver.scheduledCandidates(t.getEntityNo(), items));
        for (int i = 0; i < members.size(); i += 500) {
            recipients.insertCandidates(no, members.subList(i, Math.min(i + 500, members.size())));
        }
        long now = System.currentTimeMillis();
        t.setSentAt(now);
        t.setStatsUntil(now + attribution.windowMillis());
        t.setMatchedCount(members.size());
        t.setDeliveryStatus(members.isEmpty() ? "SENT" : "SENDING");
        tasks.updateById(t);
    }

    private record Delivery(String userNo, String title, String body, String link) {}

    private Delivery deliverNext(String no) {
        MbrReachTask t = lock(no);
        if (!"SENDING".equals(t.getDeliveryStatus())) return null;
        String member = recipients.next(no);
        var candidate = resolver.recipient(t.getEntityNo(), member, t.getScene());
        String skip = candidate.skip();
        String reachNo = null;
        String link = null;
        boolean hasDevice = false;
        if (skip == null) {
            reachNo = BizKey.next(BizKey.REACH);
            link = MemberReachServiceImpl.STORE_LINK.formatted(t.getEntityNo(), reachNo);
            if (inbox.deliverMarketing(candidate.userNo(), t.getTitle(), t.getBody(), link, reachNo)) {
                hasDevice = push.withDevice(List.of(candidate.userNo())).contains(candidate.userNo());
                MbrReachLog row = new MbrReachLog();
                row.setEntityNo(t.getEntityNo());
                row.setTaskNo(no);
                row.setReachNo(reachNo);
                row.setMemberNo(member);
                row.setScene(t.getScene());
                row.setChannel(hasDevice ? "PUSH" : "INBOX");
                row.setSentAt(System.currentTimeMillis());
                logs.insert(row);
            } else {
                skip = "INBOX_REJECTED";
            }
        }
        if (skip == null) {
            t.setSentCount(t.getSentCount() + 1);
        } else {
            t.setSkippedCount(t.getSkippedCount() + 1);
            var counts = new LinkedHashMap<String, Integer>();
            MemberReachServiceImpl.parseSkips(t.getSkipDetail()).forEach(s -> counts.put(s.reason(), s.count()));
            counts.merge(skip, 1, Integer::sum);
            t.setSkipDetail(counts.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).collect(Collectors.joining(",")));
        }
        recipients.finish(no, member, skip == null ? "SENT" : skip, skip == null ? reachNo : null);
        if (t.getSentCount() + t.getSkippedCount() == t.getMatchedCount()) t.setDeliveryStatus("SENT");
        tasks.updateById(t);
        return new Delivery(hasDevice ? candidate.userNo() : null, t.getTitle(), t.getBody(), link);
    }
}
