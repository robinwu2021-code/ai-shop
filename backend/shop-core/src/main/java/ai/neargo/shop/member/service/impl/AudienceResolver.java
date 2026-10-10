package ai.neargo.shop.member.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.dto.MemberVOs.MemberQuery;
import ai.neargo.shop.member.entity.MbrMember;
import ai.neargo.shop.member.entity.MbrReachLog;
import ai.neargo.shop.member.mapper.MemberMappers.MemberMapper;
import ai.neargo.shop.member.mapper.MemberMappers.ReachLogMapper;
import ai.neargo.shop.member.service.MemberSegmentService;
import ai.neargo.shop.member.service.MemberService;
import ai.neargo.shop.spi.member.MemberQueryPort.Audience;
import ai.neargo.shop.spi.member.MemberQueryPort.AudienceItem;
import ai.neargo.shop.spi.member.MemberQueryPort.AudienceResolution;
import ai.neargo.shop.spi.member.MemberQueryPort.Skip;
import ai.neargo.shop.spi.platform.SettingPort;
import ai.neargo.shop.spi.user.PersonPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 受众解析：一组受众项 → 命中谁、谁收得到、谁为什么收不到。
 *
 * <p><b>发消息、发券、活动三处只有这一个实现</b>。此前三处各筛一遍：发消息不挡被拉黑的人、
 * 发券不看频次、活动只认四个固定项 —— 同一群人在三个页面上是三个数，商家分不清哪个对。
 *
 * <p>判定顺序与跳过原因沿用此前 {@code MemberReachServiceImpl#sift}（线索 → 退订 → 无账号 → 频次），
 * 只补了一档 {@code BLOCKED}：被商家拉黑的人此前在发券里挡住了、在发消息里没挡。
 */
@Component
public class AudienceResolver {

    private static final Logger log = LoggerFactory.getLogger(AudienceResolver.class);

    static final String SKIP_LEAD = "LEAD";
    static final String SKIP_BLOCKED = "BLOCKED";
    static final String SKIP_OPT_OUT = "OPT_OUT";
    static final String SKIP_NO_ACCOUNT = "NO_ACCOUNT";
    static final String SKIP_TOO_SOON = "TOO_SOON";

    private static final long DAY = 86_400_000L;
    /** 批量取会员行的粒度。IN 列表太长 MySQL 会走全表 */
    private static final int CHUNK = 500;

    /**
     * 频次闸按场景分档：{@code member.reach.min-days.<scene>}。
     * 公告与唤回不是一回事 —— 一周三条公告让人烦，一周唤回三次让人拉黑。
     */
    private static final String KEY_MIN_DAYS = "member.reach.min-days.";
    private static final Map<String, Integer> DEFAULT_MIN_DAYS = Map.of(
            MbrReachLog.SCENE_NOTICE, 3,
            MbrReachLog.SCENE_WAKEUP, 14,
            MbrReachLog.SCENE_COUPON, 7);
    /** 没登记过的场景按最保守的那一档 */
    private static final int FALLBACK_MIN_DAYS = 14;

    private final MemberService memberService;
    private final MemberSegmentService segmentService;
    private final MemberMapper memberMapper;
    private final ReachLogMapper reachMapper;
    private final PersonPort personPort;
    private final SettingPort settingPort;

    public AudienceResolver(MemberService memberService, MemberSegmentService segmentService,
                            MemberMapper memberMapper, ReachLogMapper reachMapper,
                            PersonPort personPort, SettingPort settingPort) {
        this.memberService = memberService;
        this.segmentService = segmentService;
        this.memberMapper = memberMapper;
        this.reachMapper = reachMapper;
        this.personPort = personPort;
        this.settingPort = settingPort;
    }

    /** 在商家会话里调用（数据域就是本店）；买家会话里的判定走 {@code MemberQueryPortImpl#matchesRule} */
    public AudienceResolution resolve(String entityNo, List<AudienceItem> items, String scene) {
        List<AudienceItem> clean = items == null ? List.of() : items.stream()
                .filter(i -> i != null && i.type() != null && !i.type().isBlank())
                .toList();
        if (clean.isEmpty()) {
            throw BizException.of(ErrorCode.MEMBER_AUDIENCE_REQUIRED);
        }
        boolean nonMember = clean.stream().anyMatch(i -> AudienceItem.NON_MEMBER.equals(i.type()));
        if (nonMember) {
            if (clean.size() > 1) {
                // 「非本店会员 + 沉睡会员」自相矛盾：沉睡的一定是会员
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            return new AudienceResolution(0, List.of(), List.of(), false);
        }

        Set<String> matched = new LinkedHashSet<>();
        for (AudienceItem it : clean) {
            matched.addAll(match(entityNo, it));
        }

        Map<String, Integer> skipped = new LinkedHashMap<>();
        List<Audience> reachable = new ArrayList<>();
        long now = System.currentTimeMillis();
        Integer minDays = scene == null ? null : minDays(scene);
        List<String> nos = new ArrayList<>(matched);
        for (int i = 0; i < nos.size(); i += CHUNK) {
            List<MbrMember> rows = memberMapper.selectList(Wrappers.<MbrMember>lambdaQuery()
                    .eq(MbrMember::getEntityNo, entityNo)
                    .in(MbrMember::getMemberNo, nos.subList(i, Math.min(i + CHUNK, nos.size()))));
            for (MbrMember m : rows) {
                String userNo = userNo(m);
                String reason = skipReason(entityNo, m, userNo, scene, minDays, now);
                if (reason != null) {
                    skipped.merge(reason, 1, Integer::sum);
                    continue;
                }
                reachable.add(new Audience(m.getMemberNo(), userNo));
            }
        }
        List<Skip> skips = skipped.entrySet().stream()
                .map(e -> new Skip(e.getKey(), e.getValue())).toList();
        return new AudienceResolution(matched.size(), reachable, skips, true);
    }

    /** 单个受众项命中的会员号。条件内部的口径全部交给 {@link MemberService#match}，这里不另写 */
    private List<String> match(String entityNo, AudienceItem it) {
        String v = it.value();
        return switch (it.type()) {
            case AudienceItem.ALL -> memberService.match(entityNo, query(null, null, List.of()));
            case AudienceItem.LEVEL -> memberService.match(entityNo, query(v, null, List.of()));
            case AudienceItem.SOURCE -> memberService.match(entityNo, query(null, v, List.of()));
            case AudienceItem.TAG -> memberService.match(entityNo, query(null, null, List.of(v)));
            case AudienceItem.SEGMENT -> segmentService.matchAll(entityNo, v);
            default -> {
                // 未知类型不当成「全部」：那是发券场景里最贵的一个默认值
                log.warn("[受众] 不认识的受众项 type={} value={}，按空处理", it.type(), v);
                yield List.of();
            }
        };
    }

    private static MemberQuery query(String level, String source, List<String> tagNos) {
        return new MemberQuery(null, level, source, null, null, tagNos,
                null, null, null, null, 1, 0);
    }

    /** @return null = 收得到 */
    private String skipReason(String entityNo, MbrMember m, String userNo, String scene,
                              Integer minDays, long now) {
        // 线索一律不发：商家录进来的号，本人从没同意过接收任何东西
        if (MbrMember.LEAD.equals(m.getStatus())) {
            return SKIP_LEAD;
        }
        if (!MbrMember.ACTIVE.equals(m.getStatus())) {
            return SKIP_BLOCKED;
        }
        if (m.getReachOptOut() != null && m.getReachOptOut() == 1) {
            return SKIP_OPT_OUT;
        }
        if (userNo == null || userNo.isBlank()) {
            return SKIP_NO_ACCOUNT;
        }
        if (scene != null) {
            Long last = lastSentAt(entityNo, m.getMemberNo(), scene);
            if (last != null && now - last < minDays * DAY) {
                return SKIP_TOO_SOON;
            }
        }
        return null;
    }

    /** 线索与被拉黑的人不必查人档 —— 反正发不出去，省一次跨域调用 */
    private String userNo(MbrMember m) {
        if (!MbrMember.ACTIVE.equals(m.getStatus())) {
            return null;
        }
        return m.getPersonNo() == null ? null
                : personPort.find(m.getPersonNo()).map(PersonPort.PersonView::userNo).orElse(null);
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

    /** 场景的最小间隔天数。读不出来按最保守的一档 —— 配错了不该变成「不限频次」 */
    int minDays(String scene) {
        String raw = settingPort.get(KEY_MIN_DAYS + scene,
                String.valueOf(DEFAULT_MIN_DAYS.getOrDefault(scene, FALLBACK_MIN_DAYS)));
        try {
            return Integer.parseInt(raw.trim().replace("\"", ""));
        } catch (RuntimeException e) {
            log.warn("[触达] 频次配置读不出来 scene={} raw={}，按 {} 天兜底", scene, raw, FALLBACK_MIN_DAYS);
            return FALLBACK_MIN_DAYS;
        }
    }
}
