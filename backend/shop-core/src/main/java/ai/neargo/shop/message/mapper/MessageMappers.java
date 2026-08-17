package ai.neargo.shop.message.mapper;

import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.message.entity.MsgSubscribe;
import ai.neargo.shop.message.entity.MsgTemplate;
import ai.neargo.shop.message.entity.MsgTicket;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** message 域的 Mapper 集合。 */
public final class MessageMappers {

    private MessageMappers() {
    }

    public interface MessageMapper extends BaseMapper<MsgMessage> {
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
