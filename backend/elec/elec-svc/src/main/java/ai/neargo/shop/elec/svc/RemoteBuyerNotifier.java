package ai.neargo.shop.elec.svc;

import ai.neargo.elec.api.ElecInternal;
import ai.neargo.shop.elec.gateway.ElecBuyerNotifier;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 询价结果通知买家：交给主系统（openid、订阅额度、站内信都在那边）。<b>不重试</b>：
 * 重复一次就是用户手机上两条。失败只记日志，调用方据返回值写 buyer_notified_at。
 */
@Component
public class RemoteBuyerNotifier implements ElecBuyerNotifier {

    private static final Logger log = LoggerFactory.getLogger(RemoteBuyerNotifier.class);

    /** 点开通知落到的小程序页面（分包路径） */
    static final String PAGE = "pkg-elec/rfq/index?rfqNo=";

    private final MainSystemApi main;

    public RemoteBuyerNotifier(MainSystemApi main) {
        this.main = main;
    }

    @Override
    public boolean rfqResult(String userNo, String rfqNo, String result, String summary) {
        try {
            ElecInternal.NoticeResult r = ServiceCalls.call(ServiceName.PLATFORM, () -> main.notifyQuoted(
                    new ElecInternal.QuotedNotice(userNo, rfqNo, result, summary, PAGE + rfqNo)));
            return r != null && (r.inApp() || r.wx());
        } catch (RuntimeException e) {
            log.warn("询价结果交给主系统失败 rfqNo={} {}", rfqNo, e.toString());
            return false;
        }
    }
}
