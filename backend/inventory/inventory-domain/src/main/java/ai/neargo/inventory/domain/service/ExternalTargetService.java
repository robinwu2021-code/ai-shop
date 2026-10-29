package ai.neargo.inventory.domain.service;

import ai.neargo.inventory.domain.entity.InvTargetRef;

import java.util.List;

/**
 * 外部目标：宿主单向同步来的收货方 / 接收人（TDD §2.4，T2.4）。
 *
 * <p>类型由业态档案注册（不是枚举）；有上级类型的必须挂在一个已同步的上级下（站点挂商家）。
 * 以 {@code systemCode + type + ref} 唯一；带 {@code sourceVersion}，旧版本到达忽略 ——
 * 宿主重放或乱序推送，不能把新名字改回旧名字。
 */
public interface ExternalTargetService {

    /**
     * @param status {@code ACTIVE} / {@code DISABLED}；空 = ACTIVE
     * @return 落库后的当前记录（旧版本到达时是库里那条，未改）
     */
    InvTargetRef upsert(Caller caller, String type, String ref, String name, String parentRef,
                        String status, long sourceVersion);

    /** @param type 空 = 全部类型；@param parentRef 空 = 不按上级过滤 */
    List<InvTargetRef> list(String ownerId, String type, String parentRef);
}
