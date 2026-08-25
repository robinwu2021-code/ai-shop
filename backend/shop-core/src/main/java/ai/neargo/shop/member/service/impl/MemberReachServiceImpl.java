package ai.neargo.shop.member.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.member.entity.MbrMember;
import ai.neargo.shop.member.entity.MbrReachLog;
import ai.neargo.shop.member.mapper.MemberMappers.MemberMapper;
import ai.neargo.shop.member.mapper.MemberMappers.ReachLogMapper;
import ai.neargo.shop.member.service.MemberReachService;
import ai.neargo.shop.member.service.MemberSegmentService;
import ai.neargo.shop.spi.notify.UserPushPort;
import ai.neargo.shop.spi.platform.SettingPort;
import ai.neargo.shop.spi.user.PersonPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private static final long DAY = 86_400_000L;

    /**
     * 频次闸按场景分档：{@code member.reach.min-days.<scene>}。
     *
     * <p>公告与唤回不是一回事 —— 一周三条公告让人烦，一周唤回三次让人拉黑。
     * 默认值也按这个分：公告 3 天、唤回 14 天、发券通知 7 天。
     */
    private static final String KEY_MIN_DAYS = "member.reach.min-days.";
    private static final Map<String, Integer> DEFAULT_MIN_DAYS = Map.of(
            MbrReachLog.SCENE_NOTICE, 3,
            MbrReachLog.SCENE_WAKEUP, 14,
            MbrReachLog.SCENE_COUPON, 7);
    /** 兜底：没登记过的场景按最保守的那一档 */
    private static final int FALLBACK_MIN_DAYS = 14;

    private final MemberMapper memberMapper;
    private final ReachLogMapper reachMapper;
    private final MemberSegmentService segmentService;
    private final PersonPort personPort;
    private final UserPushPort pushPort;
    private final SettingPort settingPort;

    public MemberReachServiceImpl(MemberMapper memberMapper, ReachLogMapper reachMapper,
                                  MemberSegmentService segmentService, PersonPort personPort,
                                  UserPushPort pushPort, SettingPort settingPort) {
        this.memberMapper = memberMapper;
        this.reachMapper = reachMapper;
        this.segmentService = segmentService;
        this.personPort = personPort;
        this.pushPort = pushPort;
        this.settingPort = settingPort;
    }

    @Override
    public ReachPlan plan(String entityNo, String segmentNo, String scene) {
        Sift s = sift(entityNo, segmentNo, scene);
        return new ReachPlan(s.matched, s.targets.size(), s.skips());
    }

    @Override
    @Transactional
    public ReachResult send(String entityNo, String segmentNo, String scene, String title,
                            String body, String operatorNo) {
        Sift s = sift(entityNo, segmentNo, scene);
        String taskNo = BizKey.next(BizKey.REACH);
        long now = System.currentTimeMillis();
        int sent = 0;

        for (Target t : s.targets) {
            /*
             * **先记录再推送**。反过来的话，推送成功而记录失败时，
             * 频次闸就不知道我们刚打扰过他 —— 下一次群发会立刻再发一条。
             * 多记一条没发出去的，代价只是这个人这几天收不到；
             * 少记一条已发出去的，代价是他被连着打扰两次。
             */
            MbrReachLog row = new MbrReachLog();
            row.setReachNo(BizKey.next(BizKey.REACH));
            row.setEntityNo(entityNo);
            row.setMemberNo(t.memberNo);
            row.setSegmentNo(segmentNo);
            row.setTaskNo(taskNo);
            row.setChannel("PUSH");
            row.setScene(scene);
            row.setSentAt(now);
            reachMapper.insert(row);

            if (pushPort.pushToUser(t.userNo, title, body, "/pages/index/index")) {
                sent++;
            }
        }
        log.info("[触达] {} 场景 {} 计划 {} 发出 {} 跳过 {}",
                entityNo, scene, s.matched, sent, s.skipCount());
        return new ReachResult(taskNo, sent, s.skipCount(), s.skips());
    }

    /**
     * 筛人。<b>plan 与 send 共用这一处</b> ——
     * 两处各筛一遍，商家看到的「能发 25 人」与实际发出的数量会对不上，
     * 而他没有任何办法知道差在哪。
     */
    private Sift sift(String entityNo, String segmentNo, String scene) {
        long now = System.currentTimeMillis();
        int minDays = minDays(scene);
        Sift out = new Sift();

        List<String> memberNos = segmentNo == null || segmentNo.isBlank()
                ? memberMapper.selectList(Wrappers.<MbrMember>lambdaQuery()
                        .eq(MbrMember::getEntityNo, entityNo))
                        .stream().map(MbrMember::getMemberNo).toList()
                : segmentService.matchAll(entityNo, segmentNo);
        out.matched = memberNos.size();

        for (String memberNo : memberNos) {
            MbrMember m = memberMapper.selectOne(Wrappers.<MbrMember>lambdaQuery()
                    .eq(MbrMember::getMemberNo, memberNo).last("limit 1"));
            if (m == null) {
                continue;
            }
            // 线索一律不发：商家录进来的号，本人从没同意过接收任何东西
            if (MbrMember.LEAD.equals(m.getStatus())) {
                out.skip("LEAD");
                continue;
            }
            if (m.getReachOptOut() != null && m.getReachOptOut() == 1) {
                out.skip("OPT_OUT");
                continue;
            }
            String userNo = m.getPersonNo() == null ? null
                    : personPort.find(m.getPersonNo()).map(PersonPort.PersonView::userNo)
                            .orElse(null);
            if (userNo == null || userNo.isBlank()) {
                out.skip("NO_ACCOUNT");
                continue;
            }
            Long last = lastSentAt(entityNo, memberNo, scene);
            if (last != null && now - last < minDays * DAY) {
                out.skip("TOO_SOON");
                continue;
            }
            out.targets.add(new Target(memberNo, userNo));
        }
        return out;
    }

    private Long lastSentAt(String entityNo, String memberNo, String scene) {
        MbrReachLog last = reachMapper.selectOne(Wrappers.<MbrReachLog>lambdaQuery()
                .eq(MbrReachLog::getEntityNo, entityNo)
                .eq(MbrReachLog::getMemberNo, memberNo)
                .eq(MbrReachLog::getScene, scene)
                .orderByDesc(MbrReachLog::getSentAt)
                .last("limit 1"));
        return last == null ? null : last.getSentAt();
    }

    /** 场景的最小间隔天数。读不出来或没配过就用最保守的那一档 */
    private int minDays(String scene) {
        String raw = settingPort.get(KEY_MIN_DAYS + scene,
                String.valueOf(DEFAULT_MIN_DAYS.getOrDefault(scene, FALLBACK_MIN_DAYS)));
        try {
            return Integer.parseInt(raw.trim().replace("\"", ""));
        } catch (RuntimeException e) {
            // 配错了不该变成「不限频次」—— 那是这个功能最坏的失效方向
            log.warn("[触达] 频次配置读不出来 scene={} raw={}，按 {} 天兜底", scene, raw,
                    FALLBACK_MIN_DAYS);
            return FALLBACK_MIN_DAYS;
        }
    }

    private record Target(String memberNo, String userNo) {
    }

    /** 筛人的中间结果。跳过按原因计数 —— 只报一个总数，商家无从判断要不要改人群 */
    private static final class Sift {
        int matched;
        final List<Target> targets = new ArrayList<>();
        final Map<String, Integer> skipped = new LinkedHashMap<>();

        void skip(String reason) {
            skipped.merge(reason, 1, Integer::sum);
        }

        int skipCount() {
            return skipped.values().stream().mapToInt(Integer::intValue).sum();
        }

        List<ReachPlan.Skip> skips() {
            return skipped.entrySet().stream()
                    .map(e -> new ReachPlan.Skip(e.getKey(), e.getValue())).toList();
        }
    }
}
