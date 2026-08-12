package ai.neargo.shop.settle.job;

import ai.neargo.shop.settle.service.ReconService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 对账自查：每 10 分钟扫一轮超时未终态的收款。
 *
 * <p><b>为什么这么频繁</b>：它止的是掉单的血 —— 用户付了钱而我方没收到回调，
 * 每多等一分钟，他就多看一分钟「我的订单不见了」。一天一次的话，
 * 用户早就先来投诉了，而这件事本可以自动修好。
 *
 * <p><b>只在 worker 部署跑</b>（与资质扫描同一条规矩）：多实例各跑一遍时，
 * 同一笔单会被查两次 —— 查单本身无害，但补回支付会并发走两遍成功链路，
 * 幂等挡得住重复入账，挡不住两条重复的通知。
 */
@Profile("worker")
@Component
public class ReconScanJob {

    private static final Logger log = LoggerFactory.getLogger(ReconScanJob.class);

    private final ReconService reconService;

    public ReconScanJob(ReconService reconService) {
        this.reconService = reconService;
    }

    @Scheduled(cron = "0 */10 * * * *")
    public void scan() {
        ReconService.ScanResult r = reconService.scan(System.currentTimeMillis());
        if (r.scanned() == 0) {
            return;
        }
        /*
         * 有补回或关单就打 WARN：这两件事都意味着回调链路漏了一笔，
         * 而回调持续漏单是要人去查的（通道配置、回调域名、我方 502），
         * 不该淹没在 INFO 里。
         */
        if (r.repaired() > 0 || r.closed() > 0) {
            log.warn("[recon] 自查 {} 笔：**补回 {}** · 关单 {} · 留待下轮 {} —— "
                            + "补回不为零说明支付回调漏了单，要查回调链路",
                    r.scanned(), r.repaired(), r.closed(), r.deferred());
        } else {
            log.info("[recon] 自查 {} 笔，无需处置（留待下轮 {}）", r.scanned(), r.deferred());
        }
    }
}
