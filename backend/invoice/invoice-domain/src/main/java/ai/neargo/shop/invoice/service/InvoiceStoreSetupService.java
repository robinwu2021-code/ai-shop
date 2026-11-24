package ai.neargo.shop.invoice.service;

import ai.neargo.shop.invoice.dto.InvoiceDtos.StoreItemReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.StoreItemVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.StoreSettingsReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.StoreSettingsVO;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import java.util.List;

/**
 * 门店开票设定与常用品项（TDD-统一订单与开票资料 AC6 / AC11）。都是门店级：主体与门店取 {@link Scope}，请求体不收。
 *
 * <p>为什么归发票域而不是订单：它们回答的是「这家店开票时怎么带默认」—— 默认课税别、常用品名、开票时机，
 * 订单只在建单那一刻读一次、复制进订单行。放在订单这边，发票模块打包排除时就会留下一堆没人用的表。
 */
public interface InvoiceStoreSetupService {

    /** 本店常用品项上限：POS 键盘下方的快选列，再多就不是「常用」了 */
    int MAX_STORE_ITEMS = 50;

    /** 没设过回 {@code (null, MANUAL)}。 */
    StoreSettingsVO settings(Scope scope);

    StoreSettingsVO saveSettings(Scope scope, StoreSettingsReq request);

    /** 按 sort、建立先后排；含已停用的（设置页要能重新启用）。 */
    List<StoreItemVO> items(Scope scope);

    /** 新增一项；已达 {@link #MAX_STORE_ITEMS} 回 {@code INVOICE_STORE_ITEM_LIMIT}。 */
    StoreItemVO addItem(Scope scope, StoreItemReq request);

    /** 整项覆盖；不是本店的回 404。已建的订单行不受影响（建单时已复制）。 */
    StoreItemVO updateItem(Scope scope, String storeItemNo, StoreItemReq request);

    /** 删除；不存在视为已删（幂等）。 */
    void deleteItem(Scope scope, String storeItemNo);

    /** 本店的一项（含停用的）；不存在或别店的为 null。 */
    StoreItemVO item(Scope scope, String storeItemNo);
}
