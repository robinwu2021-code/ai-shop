package ai.neargo.shop.spi.user;

import java.util.List;
import java.util.Set;

/**
 * message → merchant：一条商家侧通知**该发给谁**。
 *
 * <p>只回答受众问题，不解释权限模型：调用方（消息编排）拿到的是 userNo 列表，
 * 角色→人怎么解析是 merchant 域的私事。角色码复用 B 端预置角色
 * （MANAGER/CLERK/PICKER/COURIER/CS），店主**始终包含** —— 小店最常见的形态是
 * 老板自己站柜台，没有任何子账号；一条「新订单」如果因为没配角色而没人收到，
 * 这个功能对大多数商家等于不存在。
 */
public interface MerchantStaffPort {

    /*
     * 预置角色码（与 B 端 mch_store_role 的取值一致）。放在 Port 上而不是让调用方
     * 引 merchant 域的实体常量 —— 那正是架构守卫要拦的跨域依赖。
     */
    String ROLE_MANAGER = "MANAGER";
    String ROLE_CLERK = "CLERK";
    String ROLE_CS = "CS";

    /**
     * 店主 + 在该商家任一门店持有给定角色之一的员工，去重后的 userNo 列表。
     *
     * @param roles 预置角色码集合（如 {@code Set.of("MANAGER", "CLERK")}）。
     *              空集合 = 只要店主
     * @return 商家不存在时给空列表 —— 通知的受众解析失败不该抛错拖住事件消费
     */
    List<String> staffUserNos(String entityNo, Set<String> roles);
}
