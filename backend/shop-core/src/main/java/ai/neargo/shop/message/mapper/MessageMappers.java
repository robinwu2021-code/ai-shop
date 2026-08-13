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

    /** 短信/邮件发送记录。**只追加**，没有更新与删除。 */
    public interface NotifyLogMapper extends BaseMapper<ai.neargo.shop.message.entity.SysNotifyLog> {
    }

}
