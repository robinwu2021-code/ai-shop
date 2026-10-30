package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.service.HostReferenceService;
import org.springframework.stereotype.Component;

/**
 * 接口层的标识翻译（TDD §2.22）：宿主可以给内部号，也可以给自己的编号（{@code itemRef} / {@code locationRef}），
 * 这里统一换成内部号再交给领域服务。两者都给时以内部号为准。
 */
@Component
public class HostRefs {

    private final HostReferenceService refs;

    public HostRefs(HostReferenceService refs) {
        this.refs = refs;
    }

    /** 必填物料 */
    public String item(String ownerId, String itemId, String itemRef) {
        String id = optionalItem(ownerId, itemId, itemRef);
        if (id == null) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "itemId");
        }
        return id;
    }

    /** 必填库位 */
    public String location(String ownerId, String locationId, String locationRef) {
        String id = optionalLocation(ownerId, locationId, locationRef);
        if (id == null) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "locationId");
        }
        return id;
    }

    /** 选填物料（查询过滤用）：都没给为空；给了编号却查不到是 404，不是「不过滤」 */
    public String optionalItem(String ownerId, String itemId, String itemRef) {
        if (itemId != null || itemRef == null) {
            return itemId;
        }
        String id = refs.itemIdOf(ownerId, itemRef);
        if (id == null) {
            throw InventoryException.of(InventoryError.NOT_FOUND, "itemRef:" + itemRef);
        }
        return id;
    }

    public String optionalLocation(String ownerId, String locationId, String locationRef) {
        if (locationId != null || locationRef == null) {
            return locationId;
        }
        String id = refs.locationIdOf(ownerId, locationRef);
        if (id == null) {
            throw InventoryException.of(InventoryError.NOT_FOUND, "locationRef:" + locationRef);
        }
        return id;
    }
}
