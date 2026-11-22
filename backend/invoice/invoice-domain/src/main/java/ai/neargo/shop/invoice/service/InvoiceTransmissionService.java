package ai.neargo.shop.invoice.service;

import ai.neargo.shop.spi.invoice.InvoicePort.Scope;

/** 把到期的上传任务送到开票通道；失败按种类分级处理，不改发票号码与金额（TDD-发票模块 §12）。 */
public interface InvoiceTransmissionService {

    /** @return 本轮成功送达的条数 */
    int transmitDue(int limit);

    /** 账号核验通过后解除主体的上传暂停，把 HELD 任务放回待发。@return 放回的条数 */
    int releaseHeld(String tenantNo, String entityNo, String regimeCode);

    /** 把一张发票（含其折让）待人工处理的上传任务放回待发；没有可重传的任务时拒绝。@return 放回的条数 */
    int retry(Scope scope, String invoiceNo);
}
