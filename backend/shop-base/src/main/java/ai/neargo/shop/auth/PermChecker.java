package ai.neargo.shop.auth;

import org.springframework.stereotype.Component;

/**
 * 权限判定门面，供 {@code @PreAuthorize} 使用。<b>两端两个方法，刻意不合并</b>：
 *
 * <ul>
 *   <li>{@link #can(String)} —— 运营端（{@code /ops}）。看会话里的权限码快照</li>
 *   <li>{@link #canBiz(String)} —— B 端（{@code /biz}）。看<b>当前门店</b>上的角色</li>
 * </ul>
 *
 * <p>C 端没有 RBAC（只有属主鉴权），不该调用这里。
 *
 * <p>为什么不合成一个 {@code can}：两端的判定<b>数据源不同</b> ——
 * 运营端读 token 里的权限码，B 端读 {@code BizContext} 里按门店解析的角色。
 * 合成一个方法就得在里面按 realm 分岔，而那意味着「传错了码」只会静默返回 false，
 * 表现为按钮神秘消失。分成两个，传错了是编译期或一眼可见的错误。
 */
@Component("perm")
public class PermChecker {

    /** 超管通配：一个码顶所有。生产要谨慎发放。 */
    private static final String WILDCARD = "*";

    /** 运营端权限码（{@link Perms}）。 */
    public boolean can(String code) {
        LoginUser user = SecurityUtils.currentUser().orElse(null);
        if (user == null || user.realm() != Realm.OPERATOR) {
            // 非运营会话一律拒绝：C 端 token 不该因为「碰巧没有这个权限码」而落到这里
            return false;
        }
        var perms = user.perms();
        if (perms == null || perms.isEmpty()) {
            return false;
        }
        return perms.contains(WILDCARD) || perms.contains(code);
    }

    /**
     * B 端权限码（{@link BizPerms}），按<b>当前门店</b>上持有的角色判定，取并集。
     *
     * <p>与运营端的关键差别：角色跟着门店走 —— 同一个人可能在 A 店是店长、
     * B 店是店员，所以判定必须带上 {@code X-Store-No} 解析出来的上下文，
     * 不能只看「这个人是谁」。
     *
     * <p><b>数据范围（storeNos）是另一个维度，两个都要过</b>：
     * 这里只回答「他能不能做这个动作」，「他能碰哪家店的数据」由各 Service
     * 用 {@code allowedStoresOrAll()} 裁。只判一个都是越权 ——
     * 只判角色，A 店店长能改 B 店的货；只判门店，店员能在自己店里改价。
     */
    public boolean canBiz(String code) {
        BizContext ctx = BizContext.current();
        /*
         * **先判身份，再判角色** —— 两者要给不同的码：
         *   不是商家     → 10403「没有操作权限」，他该去入驻
         *   是商家但角色不够 → 70006，他该找店主
         * 合成一个码的话，一个消费者误点进 B 端会看到「找店主开通」，
         * 而他根本没有店主。
         */
        if (ctx.merchantNo() == null || ctx.merchantNo().isBlank()) {
            return false;   // 交给 Spring 转成通用 403
        }
        if (ctx.can(code)) {
            return true;
        }
        /*
         * **直接抛专用码，而不是返回 false**。
         *
         * 返回 false 会被 Spring Security 转成通用的 403「没有操作权限」——
         * 而 B 端还有另一类 403 来自作用域（这家店没有自提点、这单不属于本店）。
         * 两者撞同一个码时，「权限没配对」与「数据不在范围里」在日志里长得一模一样，
         * 排查只能靠猜。给用户的话也不一样：通用提示会让店员去找店主要权限，
         * 而那个开关**设计上就不存在**。
         */
        throw ai.neargo.shop.common.BizException.of(
                ai.neargo.shop.common.ErrorCode.BIZ_ROLE_FORBIDDEN);
    }
}
