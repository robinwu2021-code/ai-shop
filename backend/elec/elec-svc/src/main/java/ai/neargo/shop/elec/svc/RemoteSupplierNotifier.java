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

    private final MainSystemApi main;
    private final ElecPages pages;

    public RemoteSupplierNotifier(MainSystemApi main, ElecPages pages) {
        this.main = main;
        this.pages = pages;
    }

    @Override
    public boolean newDispatch(String accountRef, String supplierNo, int lineCnt) {
        String body = lineCnt <= 1 ? "有一条求购等你报价，先到先报"
                : "有 " + lineCnt + " 条求购等你报价，先到先报";
        // dedupKey 带上条数：同一单后来又派了几行给他，那是一件新的事，该再提醒一次
        return send(new ElecInternal.SupplierNotice(accountRef, ElecInternal.KIND_DISPATCH, "有新的求购", body,
                pages.dispatches("SENT"), "ELEC_DISPATCH:" + supplierNo + ":" + lineCnt + ":"
                + (System.currentTimeMillis() / 60_000)));
    }

    @Override
    public boolean quoteAccepted(String accountRef, String quoteNo, long qty) {
        return send(new ElecInternal.SupplierNotice(accountRef, ElecInternal.KIND_ACCEPTED, "你的报价被选中了",
                "买家选了你的报价，数量 " + qty + "。平台专员会联系你确认合同与交货",
                pages.dispatches("QUOTED"), "ELEC_QUOTE_ACCEPTED:" + quoteNo));
    }

    @Override
    public boolean stockExpiring(String accountRef, String supplierNo, int rows, java.time.LocalDate first) {
        String when = first.equals(java.time.LocalDate.now()) ? "今天"
                : first.getMonthValue() + " 月 " + first.getDayOfMonth() + " 日";
        String body = rows + " 行库存最早" + when + "到期，到期后买家就看不到了。还有货的话点「仍有货」一键续期";
        // dedupKey 按天：同一天重跑（重启后补跑）只留一条
        return send(new ElecInternal.SupplierNotice(accountRef, ElecInternal.KIND_EXPIRING, "库存快到期了", body,
                pages.stocks("EXPIRING"), "ELEC_EXPIRY:" + supplierNo + ":" + java.time.LocalDate.now()));
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
