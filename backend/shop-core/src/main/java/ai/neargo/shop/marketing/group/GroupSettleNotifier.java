package ai.neargo.shop.marketing.group;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.marketing.group.entity.MktGroupBuy;
import ai.neargo.shop.marketing.group.entity.MktGroupMember;
import ai.neargo.shop.marketing.group.mapper.GroupMappers.GroupBuyMapper;
import ai.neargo.shop.marketing.group.mapper.GroupMappers.GroupMemberMapper;
import ai.neargo.shop.spi.marketing.MarketingEvents;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 团有结果了 → 通知全团（TDD-通知与消息推送 §10）。
 *
 * <p><b>为什么单独一个组件</b>：结果有两个来源 —— 成团在 {@code GroupJoinPortImpl}
 * 的原子 UPDATE 之后判定，失败在 {@code GroupServiceImpl#fail}。
 * 让 port 反向依赖 service 会绕一圈、还可能成环；把这件事抽出来，两处都只依赖它。
 */
@Component
public class GroupSettleNotifier {

    private final GroupBuyMapper groupMapper;
    private final GroupMemberMapper memberMapper;

    /** 事件总线。**可选** —— 裁剪部署 / 单测里没装它时不拦业务。 */
    private ai.neargo.shop.event.OutboxEventBus eventBus;

    public GroupSettleNotifier(GroupBuyMapper groupMapper, GroupMemberMapper memberMapper) {
        this.groupMapper = groupMapper;
        this.memberMapper = memberMapper;
    }

    @Autowired(required = false)
    public void setEventBus(ai.neargo.shop.event.OutboxEventBus bus) {
        this.eventBus = bus;
    }

    /**
     * 发一条「团有结果了」。
     *
     * <p><b>幂等靠 {@code notified_at} 的带条件 UPDATE</b>，不靠「状态是不是 FORMED」：
     * 成团是一条原子 UPDATE，每个后付的人都会再走一遍、团照旧是 FORMED ——
     * 挂在状态上的话，第 5、第 6 个人付款时会再通知一遍全团。
     * 这里只有把 NULL 改成非 NULL 的那个线程会发事件。
     *
     * <p><b>不带数据域</b>：成团可能发生在 Outbox 投递线程或定时任务里，那里没有登录态。
     *
     * @param status {@link MktGroupBuy#FORMED} / {@link MktGroupBuy#FAILED}
     */
    public void settled(String groupNo, String status) {
        if (eventBus == null || groupNo == null || groupNo.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean mine = DataScopeContext.executeWithoutScope(() ->
                groupMapper.update(null, Wrappers.<MktGroupBuy>lambdaUpdate()
                        .set(MktGroupBuy::getNotifiedAt, now)
                        .eq(MktGroupBuy::getGroupNo, groupNo)
                        .isNull(MktGroupBuy::getNotifiedAt))) > 0;
        if (!mine) {
            return;   // 别的线程已经发过了
        }
        MktGroupBuy g = DataScopeContext.executeWithoutScope(() ->
                groupMapper.selectOne(Wrappers.<MktGroupBuy>lambdaQuery()
                        .eq(MktGroupBuy::getGroupNo, groupNo).last("limit 1")));
        List<String> userNos = DataScopeContext.executeWithoutScope(() ->
                        memberMapper.selectList(Wrappers.<MktGroupMember>lambdaQuery()
                                .select(MktGroupMember::getUserNo)
                                .eq(MktGroupMember::getGroupNo, groupNo)))
                .stream().map(MktGroupMember::getUserNo).filter(Objects::nonNull)
                .distinct().toList();
        if (userNos.isEmpty()) {
            return;   // 一个人都没有的团：没人可通知，也不是错误
        }
        eventBus.publish(new MarketingEvents.GroupSettled(
                groupNo, status, g == null ? null : g.getTitle(), userNos));
    }
}
