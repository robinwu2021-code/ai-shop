package ai.neargo.shop.merchant.dto;

/**
 * 一条员工与授权的变更记录（B-11.10.3）。
 *
 * <p>回答的是同一个问题的四个部分：<b>谁</b>、<b>在什么时候</b>、
 * <b>把谁</b>、<b>改成了什么</b>。少任何一个这条记录就查不下去 ——
 * 只有「张三被提成店长」而没有操作人，追责时还得去问一圈。
 *
 * @param actor      操作人手机号（<b>脱敏</b>）。取不到当前身份时为空 ——
 *                   空就是空，不写「系统」：那会把「查不出是谁」伪装成「系统干的」
 * @param targetName 被操作员工的手机号（脱敏）。用号而不是账号号：
 *                   老板认得出「尾号 3456 那个」，认不出 SF2026…
 * @param action     动作码，见 {@code MchStaffLog}
 * @param storeName  涉及门店的**名字**（不是门店号）。加人与启停为空
 * @param role       涉及的角色码。加人与启停为空
 * @param detail     人能读的一句话
 * @param at         发生时间（毫秒）
 */
public record StaffLogVO(String actor, String targetName, String action,
                         String storeName, String role, String detail, long at) {
}
