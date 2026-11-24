package ai.neargo.shop.invoice.service;

import ai.neargo.shop.invoice.dto.InvoiceDtos.ProviderCheckVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.UploadBacklogVO;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import java.time.Duration;
import java.time.Instant;

/** 把到期的上传任务送到开票通道；失败按种类分级处理，不改发票号码与金额（TDD-发票模块 §12）。 */
public interface InvoiceTransmissionService {

    /** @return 本轮成功送达的条数 */
    int transmitDue(int limit);

    /** 账号核验通过后解除主体的上传暂停，把 HELD 任务放回待发。@return 放回的条数 */
    int releaseHeld(String tenantNo, String entityNo, String regimeCode);

    /** 把一张发票（含其折让）待人工处理的上传任务放回待发；没有可重传的任务时拒绝。@return 放回的条数 */
    int retry(Scope scope, String invoiceNo);

    /** 同步上传：这张票（含折让）待传的任务立刻送出，返回被通道接受的条数（shop.invoice.transmission.sync）。 */
    int transmitNow(Scope scope, String invoiceNo);

    /**
     * 期限巡检：未送达的任务到预警时点告警一次（WARN），过了法定期限再告警一次（ERROR）。
     * @return [新预警条数, 新逾期条数]
     */
    int[] checkDeadlines(int limit);

    /**
     * 向加值中心查一张发票的状态并对账：服务商已收到而本地上传任务未标送达的，补记为已送达；
     * 与本地状态不一致且没有待补传的任务时告警。只对服务商类通道（鲸跃）可用。
     */
    ProviderCheckVO checkWithProvider(Scope scope, String invoiceNo);

    /**
     * 注销进度巡检（TDD-发票-注销与同号重开 §3.2）：注销中且注销报文已送达的发票，每隔 {@code interval} 向服务商查一次；
     * 完成 → REVOKED（释放号码与来源单），失败 → 回到 ISSUED；超过 {@code pendingAlert} 仍未结束告警一次；
     * 已注销未重开且距期末不足 {@code reissueAlertBefore} 时提醒一次。
     * @return [完成数, 失败数, 告警数]
     */
    int[] checkRevocations(Instant now, Duration interval, Duration pendingAlert,
            Duration reissueAlertBefore);

    /** 本门店的上传积压概览（B 端列表页提示用）。 */
    UploadBacklogVO backlog(Scope scope);
}
