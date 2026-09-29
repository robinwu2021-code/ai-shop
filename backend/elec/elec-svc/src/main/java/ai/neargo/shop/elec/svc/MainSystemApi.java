package ai.neargo.shop.elec.svc;

import ai.neargo.elec.api.ElecInternal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * 主系统给元器件开的三个内部端点。路径与 record 都来自 elec-api —— 主系统那一侧的
 * {@code InternalElecEndpoint} 引用同一份常量，漂了编译不过。
 *
 * <p><b>不要给 notifyQuoted 加重试</b>：它会发一条微信消息，重复一次就是用户手机上两条。
 */
public interface MainSystemApi {

    @PostExchange(ElecInternal.SESSION)
    ElecInternal.Session session(@RequestBody ElecInternal.SessionReq req);

    @GetExchange(ElecInternal.USER)
    ElecInternal.User user(@PathVariable("userNo") String userNo);

    @PostExchange(ElecInternal.NOTIFY_QUOTED)
    ElecInternal.NoticeResult notifyQuoted(@RequestBody ElecInternal.QuotedNotice notice);
}
