package ai.neargo.shop.invoice.service;

import ai.neargo.shop.invoice.dto.InvoiceDtos.CarrierVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.ChoiceResultVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.InvoiceChoiceReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.SaveCarrierReq;
import java.util.List;

/**
 * C 端买家的载具与发票开法（TDD-发票模块 §8）。买家身份一律取自登录态；会员载具的号码由服务端填，买家只能选不能填。
 */
public interface InvoiceBuyerService {

    List<CarrierVO> carriers(String tenantNo, String userNo);

    CarrierVO saveCarrier(String tenantNo, String userNo, SaveCarrierReq request);

    void deleteCarrier(String tenantNo, String userNo, String carrierNo);

    /** 给自己的订单选开法：逐张子单记下；已付清且商家开了自动开立的当场开出。 */
    List<ChoiceResultVO> choose(String tenantNo, String userNo, InvoiceChoiceReq request);
}
