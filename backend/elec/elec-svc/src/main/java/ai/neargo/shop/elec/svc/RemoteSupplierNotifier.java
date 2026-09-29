package ai.neargo.shop.elec.svc;

import ai.neargo.elec.api.ElecInternal;
import ai.neargo.shop.elec.gateway.ElecSupplierNotifier;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 通知供应商：交给主系统（站内信收件箱与订阅额度都在那边）。
 *
 * <p><b>不重试</b>：重复一次就是他手机上两条。失败只记日志，调用方据返回值写 notified_at。
 */
@Component
public class RemoteSupplierNotifier implements ElecSupplierNotifier {

    private static final Logger log = LoggerFactory.getLogger(RemoteSupplierNotifier.class);

    static final String PAGE_LIST = "pkg-elec/supplier-rfqs/index";

    private final MainSystemApi main;

    public RemoteSupplierNotifier(MainSystemApi main) {
        this.main = main;
    }

    @Override
    public boolean newDispatch(String accountRef, String supplierNo, int lineCnt) {
        String body = lineCnt <= 1 ? "有一条求购等你报价，先到先报"
                : "有 " + lineCnt + " 条求购等你报价，先到先报";
        // dedupKey 带上条数：同一单后来又派了几行给他，那是一件新的事，该再提醒一次
        return send(new ElecInternal.SupplierNotice(accountRef, "DISPATCH", "有新的求购", body,
                PAGE_LIST + "?status=SENT", "ELEC_DISPATCH:" + supplierNo + ":" + lineCnt + ":"
                + (System.currentTimeMillis() / 60_000)));
    }

    @Override
    public boolean quoteAccepted(String accountRef, String quoteNo, long qty) {
        return send(new ElecInternal.SupplierNotice(accountRef, "ACCEPTED", "你的报价被选中了",
                "买家选了你的报价，数量 " + qty + "。平台专员会联系你确认合同与交货",
                PAGE_LIST + "?status=QUOTED", "ELEC_QUOTE_ACCEPTED:" + quoteNo));
    }

    private boolean send(ElecInternal.SupplierNotice n) {
        try {
            ElecInternal.NoticeResult r = ServiceCalls.call(ServiceName.PLATFORM, () -> main.notifySupplier(n));
            return r != null && (r.inApp() || r.wx());
        } catch (RuntimeException e) {
            log.warn("通知供应商交给主系统失败 kind={} {}", n.kind(), e.toString());
            return false;
        }
    }
}
