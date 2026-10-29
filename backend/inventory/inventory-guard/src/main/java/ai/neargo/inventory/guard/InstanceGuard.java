package ai.neargo.inventory.guard;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;

import java.util.Locale;

/**
 * 实例放行守卫：凭证所属系统不在本套放行范围内，一律拒绝（AC-28）。
 *
 * <p><b>防住什么</b>：生产两套各连各的库。ShareHub 把地址配成电商套，请求若被受理，
 * 设备数据就写进了商城的库 —— 而且不会报错。这一道在凭证解析之后、任何读写之前执行。
 */
public class InstanceGuard {

    private final InstanceProperties instance;

    public InstanceGuard(InstanceProperties instance) {
        this.instance = instance;
    }

    public void check(InventoryPrincipal principal) {
        String system = principal == null || principal.systemCode() == null
                ? "" : principal.systemCode().strip().toUpperCase(Locale.ROOT);
        if (!instance.getAllowedSystems().contains(system)) {
            throw InventoryException.of(InventoryError.SYSTEM_NOT_SERVED, system, instance.getCode());
        }
    }
}
