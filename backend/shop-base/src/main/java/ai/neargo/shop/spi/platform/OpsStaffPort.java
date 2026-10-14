package ai.neargo.shop.spi.platform;

import java.util.List;

/**
 * message → platform：一条平台侧通知**该发给谁**。
 *
 * <p>按权限码而不是按角色名解析：角色是可配置的（运营随时能建「夜班客服」），
 * 而「谁有权处理工单」由角色→功能点映射现算 —— 用权限码提问，
 * 角色怎么改组都不会漏人。
 */
public interface OpsStaffPort {

    /**
     * 拥有给定权限码的**在职**运营 staffNo 列表（通配角色如 SUPER_ADMIN 也算有）。
     *
     * @param permCode {@code Perms} 里的功能点码（如 {@code message:ticket:handle}）
     */
    List<String> staffNosWithPerm(String permCode);
}
