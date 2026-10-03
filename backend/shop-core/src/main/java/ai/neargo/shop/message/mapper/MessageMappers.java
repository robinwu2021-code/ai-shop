package ai.neargo.shop.message.mapper;

import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.message.entity.MsgSubscribe;
import ai.neargo.shop.message.entity.MsgTemplate;
import ai.neargo.shop.message.entity.MsgTicket;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** message 域的 Mapper 集合。 */
public final class MessageMappers {

    public interface FaqMapper extends BaseMapper<ai.neargo.shop.message.entity.MsgFaq> {
    }


    private MessageMappers() {
    }

    public interface MessageMapper extends BaseMapper<MsgMessage> {

        /**
         * 这个 dedupKey 用过没有 —— <b>连软删的行一起看</b>。
         *
         * <p><b>为什么不能用 {@code selectCount}</b>：它走 MyBatis-Plus 的逻辑删除过滤
         * （只看 {@code deleted = 0}），而库上的唯一键是**单列** {@code uk_msg_dedup(dedup_key)}、
         * 不含 deleted。两者口径不一致的后果是：某条站内信被软删之后，
         * 查重说「没用过」→ insert → 撞唯一键 → {@code DuplicateKeyException}
         * 抛给 OutboxDispatcher → **那条事件无限重投**。
         *
         * <p>症状极具误导性：重投把 {@code sys_outbox.retrying} 顶起来，
         * 最后变红的是判「投递任务是不是停了」的监控，
         * 而错误信息指向一个与真因毫无关系的地方（2026-09-29 在测试里真实发生过）。
         *
         * <p>所以这里问的必须是**唯一键那个空间**：写原生 SQL，不经过逻辑删除插件。
         */
        @org.apache.ibatis.annotations.Select(
                "SELECT COUNT(1) FROM notify_message WHERE dedup_key = #{dedupKey}")
        long countByDedupKeyIncludingDeleted(@org.apache.ibatis.annotations.Param("dedupKey") String dedupKey);
    }

    public interface TicketMapper extends BaseMapper<MsgTicket> {
    }

    public interface SubscribeMapper extends BaseMapper<MsgSubscribe> {
    }
    /** 消息模板。停用即刻生效，引用它的推送发不出去。 */
    public interface TemplateMapper extends BaseMapper<MsgTemplate> {
    }

    /** 短信/邮件/订阅消息/推送的发送记录。**只追加**，没有更新与删除。 */
    public interface NotifyLogMapper extends BaseMapper<ai.neargo.shop.message.entity.SysNotifyLog> {
    }

    /** App 推送设备绑定（ADR-018）。 */
    public interface PushTokenMapper extends BaseMapper<ai.neargo.shop.message.entity.MsgPushToken> {
    }

    /** 场景×通道触达配置（运营可配「哪个事件走哪些通道」）。 */
    public interface SceneChannelMapper
            extends BaseMapper<ai.neargo.shop.message.entity.MsgSceneChannel> {
    }

    /** 触达渠道注册表（通道类型×供应商×接入范围×归属）。 */
    public interface NotifyChannelMapper
            extends BaseMapper<ai.neargo.shop.message.entity.NotifyChannel> {
    }

    /** 平台营销广播推送任务。 */
    public interface PushTaskMapper
            extends BaseMapper<ai.neargo.shop.message.entity.NotifyPushTask> {
    }

}
