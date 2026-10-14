package ai.neargo.shop.member.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.member.entity.MbrMember;
import ai.neargo.shop.member.entity.MbrReachLog;
import ai.neargo.shop.member.mapper.MemberMappers.MemberMapper;
import ai.neargo.shop.member.mapper.MemberMappers.ReachLogMapper;
import ai.neargo.shop.member.mapper.MemberMappers.ReachTaskMapper;
import ai.neargo.shop.spi.platform.SettingPort;
import ai.neargo.shop.spi.user.PersonPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

/**
 * 触达效果回写：「来了」与「成单」。
 *
 * <p><b>只依赖 Mapper 与两个端口</b>，不依赖任何会员 Service ——
 * 下单回写是从 {@code MemberServiceImpl#onOrderPaid} 里调的，
 * 反过来依赖触达服务会绕出一个环。
 *
 * <p>两处都<b>整段绕开数据域</b>：进店时会话是买家（SELF），支付回调时也是，
 * 而 {@code mbr_*} 只按 {@code entity_no} 登记 —— 不绕开的话 update 静默影响 0 行，
 * 效果页恒为 0，与「没人来」长得一模一样。
 */
@Component
public class ReachAttribution {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ReachAttribution.class);

    /** 归因窗口：收到后多少天内下单算这次触达的（AC-13）。进店同一窗口 */
    static final String KEY_WINDOW_DAYS = "member.reach.attribution-days";
    static final int DEFAULT_WINDOW_DAYS = 7;
    private static final long DAY = 24L * 3600_000;

    private final ReachLogMapper reachMapper;
    private final ReachTaskMapper taskMapper;
    private final MemberMapper memberMapper;
    private final PersonPort personPort;
    private final SettingPort settingPort;

    public ReachAttribution(ReachLogMapper reachMapper, ReachTaskMapper taskMapper,
                            MemberMapper memberMapper, PersonPort personPort, SettingPort settingPort) {
        this.reachMapper = reachMapper;
        this.taskMapper = taskMapper;
        this.memberMapper = memberMapper;
        this.personPort = personPort;
        this.settingPort = settingPort;
    }

    /**
     * 他点推送进了店。
     *
     * <p><b>先核是不是本人</b>：reachNo 在推送链接里，谁都能抄一个去刷「来了」。
     * 判据是 这条触达的会员 → 人档 → 当前登录的账号，三者对得上才记。
     * 对不上、过了窗口、已经记过，一律静默不记 —— 不告诉调用方是哪一种，免得被拿来探号。
     *
     * @return 这一下有没有计入
     */
    public boolean onOpened(String reachNo, String userNo, long now) {
        if (reachNo == null || reachNo.isBlank() || userNo == null || userNo.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(DataScopeContext.executeWithoutScope(() -> {
            MbrReachLog r = reachMapper.selectOne(Wrappers.<MbrReachLog>lambdaQuery()
                    .eq(MbrReachLog::getReachNo, reachNo));
            if (r == null || r.getOpenedAt() != null || now > r.getSentAt() + windowMillis()) {
                return false;
            }
            if (!isBuyer(r.getMemberNo(), userNo)) {
                log.warn("[触达] 进店回写对不上本人 reachNo={} userNo={}", reachNo, userNo);
                return false;
            }
            int n = reachMapper.update(null, Wrappers.<MbrReachLog>lambdaUpdate()
                    .set(MbrReachLog::getOpenedAt, now)
                    .eq(MbrReachLog::getReachNo, reachNo)
                    .isNull(MbrReachLog::getOpenedAt));
            if (n == 1 && r.getTaskNo() != null) {
                taskMapper.incOpened(r.getTaskNo());
            }
            return n == 1;
        }));
    }

    /**
     * 他在这家店下了一单：归到<b>窗口内最近的那一次</b>触达（AC-13 / AC-14），
     * 且每次触达<b>只记之后的第一单</b> —— 第二单还算它的话，老顾客的日常复购会全部记到消息头上。
     *
     * <p>调用方已在绕开数据域的事务里、且已按子订单号幂等。
     */
    void onOrdered(String entityNo, String memberNo, String orderNo, long amountMinor, long paidAt) {
        MbrReachLog r = reachMapper.selectOne(Wrappers.<MbrReachLog>lambdaQuery()
                .eq(MbrReachLog::getEntityNo, entityNo)
                .eq(MbrReachLog::getMemberNo, memberNo)
                .ge(MbrReachLog::getSentAt, paidAt - windowMillis())
                .le(MbrReachLog::getSentAt, paidAt)
                .orderByDesc(MbrReachLog::getSentAt)
                .last("limit 1"));
        if (r == null || r.getOrderedAt() != null) {
            return;
        }
        int n = reachMapper.update(null, Wrappers.<MbrReachLog>lambdaUpdate()
                .set(MbrReachLog::getOrderedAt, paidAt)
                .set(MbrReachLog::getOrderedAmountMinor, amountMinor)
                .set(MbrReachLog::getOrderedRef, orderNo)
                .eq(MbrReachLog::getId, r.getId())
                .isNull(MbrReachLog::getOrderedAt));
        if (n == 1 && r.getTaskNo() != null) {
            taskMapper.incOrdered(r.getTaskNo(), amountMinor);
        }
    }

    /** 归因窗口（毫秒）。配错了按默认，不该变成「永远算」或「永远不算」 */
    long windowMillis() {
        String raw = settingPort.get(KEY_WINDOW_DAYS, String.valueOf(DEFAULT_WINDOW_DAYS));
        int days;
        try {
            days = Integer.parseInt(raw.trim().replace("\"", ""));
        } catch (RuntimeException e) {
            days = DEFAULT_WINDOW_DAYS;
        }
        return (days > 0 ? days : DEFAULT_WINDOW_DAYS) * DAY;
    }

    private boolean isBuyer(String memberNo, String userNo) {
        MbrMember m = memberMapper.selectOne(Wrappers.<MbrMember>lambdaQuery()
                .eq(MbrMember::getMemberNo, memberNo));
        if (m == null || m.getPersonNo() == null) {
            return false;
        }
        return personPort.findByUser(userNo)
                .map(p -> m.getPersonNo().equals(p.personNo()))
                .orElse(false);
    }
}
