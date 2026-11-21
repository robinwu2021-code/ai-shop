package ai.neargo.shop.invoice.service;

/** 把到期的上传任务送到开票通道；失败按退避重排，不改发票号码与金额。 */
public interface InvoiceTransmissionService {

    /** @return 本轮成功送达的条数 */
    int transmitDue(int limit);
}
