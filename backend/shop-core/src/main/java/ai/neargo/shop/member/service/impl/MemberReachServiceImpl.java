package ai.neargo.shop.member.service.impl;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.member.entity.MbrReachLog;
import ai.neargo.shop.member.mapper.MemberMappers.ReachLogMapper;
import ai.neargo.shop.member.service.MemberReachService;
import ai.neargo.shop.spi.member.MemberQueryPort.Audience;
import ai.neargo.shop.spi.member.MemberQueryPort.AudienceItem;
import ai.neargo.shop.spi.member.MemberQueryPort.AudienceResolution;
import ai.neargo.shop.spi.notify.UserPushPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

    private final ReachLogMapper reachMapper;
    private final AudienceResolver resolver;
    private final UserPushPort pushPort;

    public MemberReachServiceImpl(ReachLogMapper reachMapper, AudienceResolver resolver,
                                  UserPushPort pushPort) {
        this.reachMapper = reachMapper;
        this.resolver = resolver;
        this.pushPort = pushPort;
    }

    @Override
    public ReachPlan plan(String entityNo, List<AudienceItem> audiences, String scene) {
        AudienceResolution r = sift(entityNo, audiences, scene);
        return new ReachPlan(r.matched(), r.reachable().size(), skips(r));
    }

    @Override
    @Transactional
    public ReachResult send(String entityNo, List<AudienceItem> audiences, String scene, String title,
                            String body, String operatorNo) {
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

            if (pushPort.pushToUser(t.userNo(), title, body, "/pages/index/index")) {
                sent++;
            }
        }
        int skipped = r.matched() - r.reachable().size();
        log.info("[触达] {} 场景 {} 计划 {} 发出 {} 跳过 {}", entityNo, scene, r.matched(), sent, skipped);
        return new ReachResult(taskNo, sent, skipped, skips(r));
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
