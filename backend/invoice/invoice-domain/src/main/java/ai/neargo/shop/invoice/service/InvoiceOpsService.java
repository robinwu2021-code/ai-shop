package ai.neargo.shop.invoice.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.invoice.dto.InvoiceDtos.OpsSellerQuery;
import ai.neargo.shop.invoice.dto.InvoiceDtos.OpsSellerVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.OpsTransmissionQuery;
import ai.neargo.shop.invoice.dto.InvoiceDtos.OpsTransmissionVO;

/**
 * 运营端跨主体视图（TDD-发票模块 §18）：营业人一览（配置、号码余量、上传积压）与上传任务清单。
 * 只读；处置动作复用 B 端同一套服务（核验、领号、重传、查单），由 controller 按发票 / 主体组出范围后调用。
 */
public interface InvoiceOpsService {

    PageData<OpsSellerVO> sellers(String tenantNo, OpsSellerQuery query);

    PageData<OpsTransmissionVO> transmissions(String tenantNo, OpsTransmissionQuery query);
}
