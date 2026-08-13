package ai.neargo.shop.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 权限判定门面，供 {@code @PreAuthorize} 使用。<b>两端两个方法，刻意不合并</b>：
 *
 * <ul>
 *   <li>{@link #can(String)} —— 运营端（{@code /ops}）。按会话里的角色**现算**权限码</li>
 *   <li>{@link #canBiz(String)} —— B 端（{@code /biz}）。看<b>当前门店</b>上的角色</li>
 * </ul>
 *
 * <p>C 端没有 RBAC（只有属主鉴权），不该调用这里。
 *
 * <p>为什么不合成一个 {@code can}：两端的判定<b>数据源不同</b> ——
 * 运营端读 {@link LivePermResolver}（库里的角色→功能点），B 端读 {@code BizContext} 里按门店解析的角色。
 * 合成一个方法就得在里面按 realm 分岔，而那意味着「传错了码」只会静默返回 false，
 * 表现为按钮神秘消失。分成两个，传错了是编译期或一眼可见的错误。
 */
@Component("perm")
public class PermChecker {

    private final ObjectProvider<LivePermResolver> liveResolver;

    public PermChecker(ObjectProvider<LivePermResolver> liveResolver) {
        this.liveResolver = liveResolver;
    }

    /**
     * 运营端权限码（{@link Perms}）。
     *
     * <p><b>权限码现算，不读会话快照</b> —— 改了角色配置，下一个请求就是新权限，
     * 不必等这个人重新登录。理由与回落策略见 {@link LivePermResolver}。
     */
    public boolean can(String code) {
        LoginUser user = SecurityUtils.currentUser().orElse(null);
        if (user == null || user.realm() != Realm.OPERATOR) {
            // 非运营会话一律拒绝：C 端 token 不该因为「碰巧没有这个权限码」而落到这里
            return false;
        }
        /*
         * 通配判定委托 neargo 的 Permissions.matches —— 它认 `*` 也认 `merchant:*`。
         *
         * 此前这里是手写的 `contains("*") || contains(code)`：**只认精确的 `*`**。
         * 而 ops-web 的 can() 一直支持模块通配，两端语义不一致 ——
         * 哪天有人给角色配一个 `merchant:*`，表现会是「前端显示入口、后端 403」。
         *
         * <p><b>B 端（{@link #canBiz}）刻意走另一条路：不支持模块通配。</b>
         * 那边 13 个码，且给商家角色配通配等于把以后新增的码也一并授出去。
         * 两端在这一点上不一致是决定，不是遗漏 —— 理由写在 {@code BizContext.can}。
         */
        var perms = livePerms(user);
        return perms != null && !perms.isEmpty()
                && ai.neargo.common.security.rbac.Permissions.matches(perms, code);
    }

    /**
     * 现算权限码；解析不出就用会话里的快照。
     *
     * <p>回落而不是拒绝：解析器没装上（单测、裁剪部署）时应当退回旧行为，
     * 而不是让所有运营的后台一起变空 —— 后者看起来像「系统坏了」，
     * 且**没有任何报错**能指向真正的原因。
     */
    private java.util.List<String> livePerms(LoginUser user) {
        var roles = user.roles();
        if (roles != null && !roles.isEmpty()) {
            var live = liveResolver.getIfAvailable(() -> LivePermResolver.NONE).resolve(roles);
            if (live != null) {
                return live;
            }
        }
        return user.perms();
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

    /**
     * 任一权限即可（{@link #canBiz} 的或版本）。
     *
     * <p>给<b>汇总型端点</b>用，它们一次返回好几件互不相干的事。
     * 工作台待办就是这样：7 个数字分属 5 个权限（待分拣→{@code receive}、
     * 待核销→{@code verify}、待发货→{@code ship}…）。用其中任意一个当门禁都是错的 ——
     * 挂 {@code order:view} 的后果是理货员看不到「待分拣 3」，
     * 而那正是他今天要干的活。
     *
     * <p><b>粒度控制交给展示层</b>：端上按自己的 {@code perms} 只画有权的格子。
     * 这里放行的代价是理货员在响应体里能看到「待售后 2」这个计数 ——
     * 一个不含金额、不含顾客、不含订单号的数。真正的数据仍锁在各自的端点上，
     * 他点进售后页照样 70006。**安全边界没有下移，只是汇总口不再一刀切。**
     */
    public boolean canAnyBiz(String... codes) {
        BizContext ctx = BizContext.current();
        if (ctx.merchantNo() == null || ctx.merchantNo().isBlank()) {
            return false;
        }
        for (String code : codes) {
            if (ctx.can(code)) {
                return true;
            }
        }
        // 一个都没有 = 这家店没给他任何角色，与 canBiz 同一个码
        throw ai.neargo.shop.common.BizException.of(
                ai.neargo.shop.common.ErrorCode.BIZ_ROLE_FORBIDDEN);
    }
}
