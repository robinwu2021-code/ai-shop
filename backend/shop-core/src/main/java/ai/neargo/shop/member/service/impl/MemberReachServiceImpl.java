package ai.neargo.shop.member.service.impl;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.member.entity.MbrMember;
import ai.neargo.shop.member.entity.MbrReachLog;
import ai.neargo.shop.member.entity.MbrReachTask;
import ai.neargo.shop.member.mapper.MemberMappers.MemberMapper;
import ai.neargo.shop.member.mapper.MemberMappers.ReachLogMapper;
import ai.neargo.shop.member.mapper.MemberMappers.ReachTaskMapper;
import ai.neargo.shop.member.service.MemberReachService;
import ai.neargo.shop.spi.member.MemberQueryPort.Audience;
import ai.neargo.shop.spi.member.MemberQueryPort.AudienceItem;
import ai.neargo.shop.spi.member.MemberQueryPort.AudienceResolution;
import ai.neargo.shop.spi.notify.UserPushPort;
import ai.neargo.shop.spi.user.PersonPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 给会员发消息。
 *
 * <p><b>频次口径全部读 {@code sys_setting}，代码里只有 key</b> ——
 * 运营发现某个场景太烦人要收紧，不该等一次发版。
 */
@Service
public class MemberReachServiceImpl implements MemberReachService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MemberReachServiceImpl.class);

    /**
     * 推送点开后去哪：<b>这家店的首页，带着这一条的号</b>。
     * 此前写死 {@code /pages/index/index} —— 点开落在平台首页，既不知道是哪家店发的，
     * 也回写不了「来了」，效果页因此只能是一片零。
     */
    static final String STORE_LINK = "/pages/store/index?merchantNo=%s&reach=%s";
    private static final int DESC_MAX = 128;

    private final ReachLogMapper reachMapper;
    private final ReachTaskMapper taskMapper;
    private final MemberMapper memberMapper;
    private final AudienceResolver resolver;
    private final UserPushPort pushPort;
    private final PersonPort personPort;
    private final ReachAttribution attribution;
    private final ObjectMapper json;

    public MemberReachServiceImpl(ReachLogMapper reachMapper, ReachTaskMapper taskMapper,
                                  MemberMapper memberMapper, AudienceResolver resolver,
                                  UserPushPort pushPort, PersonPort personPort,
                                  ReachAttribution attribution, ObjectMapper json) {
        this.reachMapper = reachMapper;
        this.taskMapper = taskMapper;
        this.memberMapper = memberMapper;
        this.resolver = resolver;
        this.pushPort = pushPort;
        this.personPort = personPort;
        this.attribution = attribution;
        this.json = json;
    }

    @Override
    public ReachPlan plan(String entityNo, List<AudienceItem> audiences, String scene) {
        AudienceResolution r = sift(entityNo, audiences, scene);
        return new ReachPlan(r.matched(), r.reachable().size(), skips(r));
    }

    @Override
    @Transactional
    public ReachResult send(String entityNo, List<AudienceItem> audiences, String audienceDesc, String scene,
                            String title, String body, String operatorNo) {
        AudienceResolution r = sift(entityNo, audiences, scene);
        String taskNo = BizKey.next(BizKey.REACH);
        long now = System.currentTimeMillis();
        // 只有「单个人群」时才记人群号：多项受众没有一个号能代表它，记一个会让回看按人群聚合时算错
        String segmentNo = audiences != null && audiences.size() == 1
                && AudienceItem.SEGMENT.equals(audiences.get(0).type()) ? audiences.get(0).value() : null;
        int sent = 0;

        for (Audience t : r.reachable()) {
            /*
             * **先记录再推送**。反过来的话，推送成功而记录失败时，
             * 频次闸就不知道我们刚打扰过他 —— 下一次群发会立刻再发一条。
             * 多记一条没发出去的，代价只是这个人这几天收不到；
             * 少记一条已发出去的，代价是他被连着打扰两次。
             */
            MbrReachLog row = new MbrReachLog();
            row.setReachNo(BizKey.next(BizKey.REACH));
            row.setEntityNo(entityNo);
            row.setMemberNo(t.memberNo());
            row.setSegmentNo(segmentNo);
            row.setTaskNo(taskNo);
            row.setChannel("PUSH");
            row.setScene(scene);
            row.setSentAt(now);
            reachMapper.insert(row);

            if (pushPort.pushToUser(t.userNo(), title, body,
                    String.format(STORE_LINK, entityNo, row.getReachNo()))) {
                sent++;
            }
        }
        int skipped = r.matched() - r.reachable().size();
        writeTask(taskNo, entityNo, audiences, audienceDesc, scene, title, body, r, sent, skipped,
                now, operatorNo);
        log.info("[触达] {} 场景 {} 计划 {} 发出 {} 跳过 {}", entityNo, scene, r.matched(), sent, skipped);
        return new ReachResult(taskNo, sent, skipped, skips(r));
    }

    /**
     * 批次头。<b>写在逐人明细之后</b>：发出人数要等推送结果回来才知道；
     * 同一事务里，先写后写对读的人没有区别。
     */
    private void writeTask(String taskNo, String entityNo, List<AudienceItem> audiences, String audienceDesc,
                           String scene, String title, String body, AudienceResolution r,
                           int sent, int skipped, long now, String operatorNo) {
        MbrReachTask t = new MbrReachTask();
        t.setTaskNo(taskNo);
        t.setEntityNo(entityNo);
        t.setScene(scene);
        t.setTitle(title == null ? "" : title);
        t.setBody(body);
        List<AudienceItem> items = audiences == null ? List.of() : audiences;
        t.setAudienceJson(json.writeValueAsString(items));
        t.setAudienceDesc(clip(audienceDesc == null || audienceDesc.isBlank() ? fallbackDesc(items) : audienceDesc));
        t.setMatchedCount(r.matched());
        t.setSentCount(sent);
        t.setSkippedCount(skipped);
        t.setSkipDetail(r.skips().isEmpty() ? null : r.skips().stream()
                .map(x -> x.reason() + ":" + x.count()).collect(Collectors.joining(",")));
        t.setOpenedCount(0);
        t.setOrderedCount(0);
        t.setOrderedAmountMinor(0L);
        t.setSentAt(now);
        t.setStatsUntil(now + attribution.windowMillis());
        t.setOperatorNo(operatorNo);
        taskMapper.insert(t);
    }

    /** 旧版 App 不传描述：拼出受众项本身，至少不是一片空白 */
    private static String fallbackDesc(List<AudienceItem> items) {
        return items.stream().map(i -> AudienceItem.ALL.equals(i.type()) ? i.type() : i.type() + ":" + i.value())
                .collect(Collectors.joining(" · "));
    }

    private static String clip(String s) {
        return s.length() <= DESC_MAX ? s : s.substring(0, DESC_MAX);
    }

    @Override
    public boolean opened(String reachNo, String userNo) {
        return attribution.onOpened(reachNo, userNo, System.currentTimeMillis());
    }

    @Override
    public List<ReachTaskVO> tasks(String entityNo, long page, long size) {
        return taskMapper.selectPage(new Page<>(Math.max(page, 1), size <= 0 ? 20 : Math.min(size, 100)),
                        Wrappers.<MbrReachTask>lambdaQuery()
                                .eq(MbrReachTask::getEntityNo, entityNo)
                                .orderByDesc(MbrReachTask::getSentAt))
                .getRecords().stream()
                .map(t -> vo(t, List.of(), Math.max(nz(t.getSentCount()) - nz(t.getOpenedCount()), 0))).toList();
    }

    @Override
    public Optional<ReachTaskVO> task(String entityNo, String taskNo) {
        MbrReachTask t = taskMapper.selectOne(Wrappers.<MbrReachTask>lambdaQuery()
                .eq(MbrReachTask::getEntityNo, entityNo)
                .eq(MbrReachTask::getTaskNo, taskNo));
        if (t == null) {
            return Optional.empty();
        }
        List<MbrReachLog> rows = reachMapper.selectList(Wrappers.<MbrReachLog>lambdaQuery()
                .eq(MbrReachLog::getEntityNo, entityNo)
                .eq(MbrReachLog::getTaskNo, taskNo)
                .isNotNull(MbrReachLog::getOrderedAt)
                .orderByDesc(MbrReachLog::getOrderedAmountMinor)
                .last("limit " + ORDERED_MEMBERS_LIMIT));
        Map<String, MbrMember> members = rows.isEmpty() ? Map.of()
                : memberMapper.selectList(Wrappers.<MbrMember>lambdaQuery()
                        .eq(MbrMember::getEntityNo, entityNo)
                        .in(MbrMember::getMemberNo, rows.stream().map(MbrReachLog::getMemberNo).toList()))
                .stream().collect(Collectors.toMap(MbrMember::getMemberNo, Function.identity(), (a, b) -> a));
        List<OrderedMember> ordered = rows.stream().map(r -> {
            MbrMember m = members.get(r.getMemberNo());
            String tail = m == null || m.getPersonNo() == null ? null
                    : personPort.find(m.getPersonNo()).map(PersonPort.PersonView::phoneTail).orElse(null);
            return new OrderedMember(r.getMemberNo(), m == null ? null : m.getRemark(), tail,
                    r.getOrderedAmountMinor() == null ? 0 : r.getOrderedAmountMinor(), r.getOrderedAt());
        }).toList();
        /*
         * 「没来的」按明细数，不按 发出 − 来了：推送失败的那几个人也有明细行（先记后推），
         * 「没来的存人群」存进去的是按明细筛的 —— 两个数要是同一把尺，按钮上的人数才对得上。
         */
        long notOpened = reachMapper.selectCount(Wrappers.<MbrReachLog>lambdaQuery()
                .eq(MbrReachLog::getEntityNo, entityNo)
                .eq(MbrReachLog::getTaskNo, taskNo)
                .isNull(MbrReachLog::getOpenedAt));
        return Optional.of(vo(t, ordered, (int) notOpened));
    }

    private static ReachTaskVO vo(MbrReachTask t, List<OrderedMember> ordered, int notOpened) {
        int sent = nz(t.getSentCount());
        int opened = nz(t.getOpenedCount());
        return new ReachTaskVO(t.getTaskNo(), t.getScene(), t.getTitle(), t.getBody(), t.getAudienceDesc(),
                t.getSentAt(), t.getStatsUntil(), System.currentTimeMillis() > t.getStatsUntil(),
                nz(t.getMatchedCount()), sent, nz(t.getSkippedCount()), parseSkips(t.getSkipDetail()),
                opened, nz(t.getOrderedCount()),
                t.getOrderedAmountMinor() == null ? 0 : t.getOrderedAmountMinor(),
                ordered, notOpened);
    }

    private static List<ReachPlan.Skip> parseSkips(String detail) {
        if (detail == null || detail.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(detail.split(",")).map(p -> p.split(":"))
                .filter(p -> p.length == 2)
                .map(p -> new ReachPlan.Skip(p[0], Integer.parseInt(p[1].trim()))).toList();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    /**
     * 筛人。<b>plan 与 send 共用这一处</b>，且与发券、活动共用同一个 {@link AudienceResolver} ——
     * 两处各筛一遍，商家看到的「能发 25 人」与实际发出的数量会对不上。
     */
    private AudienceResolution sift(String entityNo, List<AudienceItem> audiences, String scene) {
        return resolver.resolve(entityNo, audiences, scene);
    }

    private static List<ReachPlan.Skip> skips(AudienceResolution r) {
        return r.skips().stream().map(s -> new ReachPlan.Skip(s.reason(), s.count())).toList();
    }
}
