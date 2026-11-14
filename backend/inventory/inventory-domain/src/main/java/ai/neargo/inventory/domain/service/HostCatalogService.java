package ai.neargo.inventory.domain.service;

import ai.neargo.inventory.domain.entity.InvItem;

import java.util.List;

/**
 * 宿主推送物料（TDD §2.6，T2.4）。宿主按自己的物料号推，库存不回头查宿主。
 *
 * <p>规则来自业主的业态档案：{@code SERIAL} 要开通 SERIAL 能力包；要求放行凭证的物料，
 * 凭证类型必须是档案登记过的。<b>有流水之后跟踪方式与计量单位不可改</b> —— 改了历史流水就解释不通。
 */
public interface HostCatalogService {

    /** 按宿主物料号新建或更新，返回库存的物料号。 */
    String upsert(Caller caller, String externalRef, ItemSpec spec);

    List<InvItem> list(String ownerId);

    /**
     * @param trackingMode {@code QUANTITY} / {@code SERIAL}；空 = QUANTITY
     * @param gatePolicy   {@code NONE} / {@code REF_REQUIRED}；空 = NONE
     * @param attributes   宿主自管的展示属性 JSON
     */
    record ItemSpec(String name, String baseUom, String trackingMode, String gatePolicy, String gateRefType,
                    String attributes) {
    }
}
