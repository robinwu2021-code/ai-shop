package ai.neargo.shop.arch;

import ai.neargo.shop.auth.BizPerms;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每个 {@code /biz} 端点都必须**明确**它要什么权限。
 *
 * <p>这条守卫的用法是**先让它红**：把每个端点该给谁列成下面这张表，
 * 没在表里的端点直接报错。这样「哪些还没定权限」是可见的 ——
 * 而不是靠人去逐个数几十个端点，数漏一个就是一个越权口子。
 *
 * <p><b>刻意不写端点总数</b>：写死的数字每加一个端点就过期一次，
 * 而没有任何东西会提醒你改它。当前分布见
 * {@code docs/technical/reference/B端功能点-权限码-页面.md} 的统计行。
 *
 * <p>将来加端点时它同样会红，逼加的人回答一句「这个该给谁」。
 * <b>这正是这类守卫存在的理由</b>：不是防止今天写错，是防止明天忘记。
 */
class BizEndpointPermTest {

    /** 不需要授权的端点：登录相关，以及「还不是商家的人」也要能用的那几个 */
    private static final Set<String> PUBLIC = Set.of(
            "/biz/regions/search",
            "/biz/regions/path",
            "/biz/geo/reverse",
            "/biz/geo/geocode",
            "/biz/geo/tips",
            // 小区缓存：与 tips 同性质的主数据 —— 一片地方有哪些小区，不含任何一家店的数据。
            // 写入那条也放这里：它写的是**地图返回的公共事实**，不是商家自己的东西，
            // 而还没建店的申请人正需要它来挑经营范围
            "/biz/geo/estates", "/biz/geo/estates/counts",
            "/biz/auth/login", "/biz/auth/otp/send", "/biz/auth/staff-login",
            // 设/查自己的登录密码：作用对象是**调用者本人**（SecurityUtils.currentUserNo），
            // 拿不到别人的。挂 biz 权限码反而错了 —— 店员也该能给自己设密码，
            // 而他一个 biz:* 都可能没有
            "/biz/auth/password",
            // 入驻链路：申请人此刻还没有 merchantNo，一律 403 的话被驳回的人
            // 就永远看不到驳回原因，闭环在这里断掉
            "/biz/merchant/apply", "/biz/merchant/profile",
            // 无证照快速开店：**这条路存在的意义就是给「还没有任何主体的人」用的** ——
            // 那时 BizContext.merchantNo 是空的，挂任何 biz:* 码都等于永远 403。
            // 作用对象是调用者本人（SecurityUtils.currentUserNo），建出来的主体归他自己
            "/biz/merchant/quick-start",
            // 自查作用域：端上据此决定展示哪些入口，本身不含业务数据
            "/biz/context",
            // 「我能进哪几家店」——门店切换器要用，结果按 storeNos 裁剪。
            // 要 biz:store 的话店员一家都切不了，而多门店授权正是为他准备的
            "/biz/store/list",
            // 主数据与上传：登录后人人可用，不含任何一家店的数据
            // 行政区划与 /biz/communities 同性质：主数据，不含任何一家店的数据。
            // 要 biz:store 的话，还没建店的申请人就挑不了经营范围
            "/biz/category/tree", "/biz/communities", "/biz/regions",
            // 村级词典（提报村时的名称联想）：与 /biz/regions 同性质的主数据，
            // 入驻申请人挑经营范围前就可能用到
            "/biz/regions/villages",
            "/biz/upload/image",
            // 消息收件箱：按当前 userNo 隔离，别人的本来就查不到。
            // 要 biz 权限的话，收到「新订单」通知的店员反而打不开消息中心
            "/biz/message", "/biz/message/unread-count",
            "/biz/message/{messageNo}/read", "/biz/message/read-all",
            // 设备绑定：绑的是当前登录者自己的设备。要权限的话，
            // 收「新订单」提醒的店员反而绑不上（ADR-018）
            "/biz/push-token", "/biz/push-token/unregister");

    /**
     * 端点 → 需要的权限码。
     *
     * <p>分界线画在「出错的后果」上，判断依据见
     * {@code docs/requirements/三端角色权限功能对齐清单.md} §4。
     */
    private static final Map<String, String> REQUIRED = new LinkedHashMap<>() {{
        // ---- 履约：三种活面对三种对象 ----
        put("/biz/pickup/arrived", BizPerms.RECEIVE);
        put("/biz/pickup/picking", BizPerms.RECEIVE);
        put("/biz/pickup/{orderNo}/report", BizPerms.RECEIVE);
        put("/biz/pickup/verify", BizPerms.VERIFY);
        put("/biz/pickup/verify/batch", BizPerms.VERIFY);
        put("/biz/pickup/verify/search", BizPerms.VERIFY);
        put("/biz/pickup/orders", BizPerms.VERIFY);
        put("/biz/pickup/overview", BizPerms.VERIFY);
        put("/biz/order/{subOrderNo}/ship", BizPerms.SHIP);
        put("/biz/order/{subOrderNo}/delivered", BizPerms.SHIP);

        // ---- 订单与经营数据 ----
        put("/biz/order", BizPerms.ORDER_VIEW);
        put("/biz/order/{subOrderNo}", BizPerms.ORDER_VIEW);
        put("/biz/dashboard/stats", BizPerms.CUSTOMER);
        put("/biz/customers", BizPerms.CUSTOMER);
        // 跨店总览与对比（B-11.12.5/6）：同样是经营数据，与 /biz/dashboard/stats 同一档。
        // **不新造码** —— 「跨店」是数据的范围，不是一类新的东西；
        // 造一个 biz:cross-store 会让老板必须给店长两个码才能看同一批数字。
        // 能不能看是这个码管的，**买没买是能力位管的**，两道门正交（见控制器注释）
        put("/biz/cross-store/overview", BizPerms.CUSTOMER);
        put("/biz/cross-store/compare", BizPerms.CUSTOMER);

        // ---- 商品：改库存与改价是两件事 ----
        put("/biz/goods", BizPerms.STOCK);
        put("/biz/goods/{goodsNo}", BizPerms.STOCK);
        put("/biz/goods/{goodsNo}/stock", BizPerms.STOCK);
        put("/biz/goods/{goodsNo}/store-stock", BizPerms.STOCK);
        // 改价是定价权，与补货不是一回事 —— 挂 biz:goods 而不是 biz:stock
        put("/biz/goods/{goodsNo}/store-price", BizPerms.GOODS);
        // 提交审核与改截单都是「商品」这一档：能建品的人才谈得上提交与改截单
        put("/biz/goods/{goodsNo}/submit", BizPerms.GOODS);
        put("/biz/goods/{goodsNo}/presale", BizPerms.GOODS);
        put("/biz/goods/save", BizPerms.GOODS);
        put("/biz/goods/{goodsNo}/toggle", BizPerms.GOODS);
        put("/biz/goods/recognize", BizPerms.GOODS);
        // 自动生成图文详情：写的是这家店的商品文案，与建品同一档权限。
        // 店员（只有 biz:stock）不该能改商品文案
        put("/biz/goods/describe", BizPerms.GOODS);
        /*
         * 规格模板：**从 PUBLIC 移过来的**（2026-08-21）。
         *
         * 它此前与类目树、行政区划放在一起，理由是「主数据，不含任何一家店的数据」——
         * 那句话只对 GET 成立。POST 写的是 `scope=MERCHANT` + `entity_no=当前商家` 的行，
         * 是这家店的数据；而这张表按路径判权，两个方法只能同进同退。
         *
         * 同进同退选 GOODS 而不是留在 PUBLIC：规格模板是建品链路的一环，
         * 只能改库存的角色（配送员、客服）本来就建不了品，读不到模板没有损失；
         * 反过来留在 PUBLIC，任何持有 B 端会话的子账号都能给这家店塞模板。
         */
        put("/biz/spec-templates", BizPerms.GOODS);
        // 自定义规格（V195 的商家覆盖层）：与建品同一个码 —— 能建商品就能给它加一档规格，
        // 单独开一个码只会让「建品」这件事需要两个授权
        // 我的资质：与门店设置同一个码 —— 传证是店主的事，而 biz:store 正是那条线
        put("/biz/qualifications", BizPerms.STORE);
        put("/biz/qualifications/save", BizPerms.STORE);
        put("/biz/spec-values", BizPerms.GOODS);
        put("/biz/spec-dims", BizPerms.GOODS);
        // 「我的规格」：看自己建的维度、改名、停用。都是商品域的事，同一档权限
        put("/biz/my-spec-dims", BizPerms.GOODS);
        put("/biz/store-spec-dims", BizPerms.GOODS);
        put("/biz/spec-override/{categoryNo}", BizPerms.GOODS);
        put("/biz/spec-dims/{dimNo}/values", BizPerms.GOODS);
        put("/biz/my-spec-dims/{dimNo}/rename", BizPerms.GOODS);
        put("/biz/my-spec-dims/{dimNo}/archive", BizPerms.GOODS);
        // 标准品搜索（TDD-标准品库）：建品链路的一环，与规格模板同一档 ——
        // 只能改库存的角色（配送员、客服）建不了品，也就用不上标准品
        put("/biz/spu-std", BizPerms.GOODS);

        // ---- 营销与报价：都是对钱的承诺 ----
        put("/biz/campaign", BizPerms.CAMPAIGN);
        put("/biz/campaign/{campaignNo}/toggle", BizPerms.CAMPAIGN);
        put("/biz/groups", BizPerms.CAMPAIGN);
        put("/biz/group-request/pool", BizPerms.CAMPAIGN);
        put("/biz/group-request/{requestNo}/quote", BizPerms.CAMPAIGN);
        put("/biz/quote/{quoteNo}/revise", BizPerms.CAMPAIGN);

        // ---- 评价与售后：对着顾客说话 ----
        put("/biz/review", BizPerms.REVIEW);
        put("/biz/review/{reviewNo}/reply", BizPerms.REVIEW);
        put("/biz/review/{reviewNo}/appeal", BizPerms.REVIEW);
        put("/biz/after-sale", BizPerms.AFTERSALE);
        put("/biz/after-sale/{afterSaleNo}/approve", BizPerms.AFTERSALE);
        put("/biz/after-sale/{afterSaleNo}/reject", BizPerms.AFTERSALE);
        put("/biz/after-sale/{afterSaleNo}/receive", BizPerms.AFTERSALE);

        // ---- 门店经营面 ----
        put("/biz/store", BizPerms.STORE);
        // 会员（P1）：沿用客户资产那个码 —— 会员就是「我的客户」那一页的升级版
        put("/biz/members", BizPerms.CUSTOMER);
        put("/biz/members/stats", BizPerms.CUSTOMER);
        put("/biz/members/{memberNo}", BizPerms.CUSTOMER);
        // 只改公告：与整份门面同一个码 —— 它写的是同一张表的同一家店
        put("/biz/store/announcement", BizPerms.STORE);
        // 删一条常用语：还是那张表那一家店，同一个码
        put("/biz/store/announcement/recent/remove", BizPerms.STORE);
        // 门店送货方式（方案 v4）：GET/PUT 同路径同进退，都归门店管理面
        put("/biz/stores/{storeNo}/fulfillment", BizPerms.STORE);
        put("/biz/pickup-points/candidates", BizPerms.STORE);
        put("/biz/pickup-points", BizPerms.STORE);
        // 提报新社区与设经营范围是同一件事的两半：能决定「我做哪儿」的人，
        // 才该能提「这儿还没开」
        put("/biz/communities/apply", BizPerms.STORE);
        put("/biz/communities/applies", BizPerms.STORE);
        // 地图上点中的小区直接开通：它写的是主数据（建一条聚落），
        // 但触发它的动作就是「设经营范围」—— 与提报同一半，权限也该同一个
        put("/biz/communities/from-map", BizPerms.STORE);
        put("/biz/store/qrcode", BizPerms.STORE);
        put("/biz/store/share-kit", BizPerms.STORE);
        // 分享海报：与 share-kit 是同一件事的两种载体（一句文案 / 一张图），权限同档
        put("/biz/store/poster", BizPerms.STORE);
        put("/biz/delivery/rule", BizPerms.STORE);

        // ---- 门店结构：改的是主体 ----
        put("/biz/store/create", BizPerms.STORE_ADMIN);
        put("/biz/store/{storeNo}/rename", BizPerms.STORE_ADMIN);
        put("/biz/store/{storeNo}/status", BizPerms.STORE_ADMIN);
        put("/biz/store/{storeNo}/default", BizPerms.STORE_ADMIN);
        put("/biz/store/{storeNo}/payment", BizPerms.STORE_ADMIN);
        // 货架读：店长要看得见本店卖哪几类；改货架是店铺配置，收紧到 STORE_ADMIN
        put("/biz/store/{storeNo}/categories", BizPerms.STORE);
        put("/biz/staff", BizPerms.STORE_ADMIN);
        put("/biz/staff/{mchAccountNo}/status", BizPerms.STORE_ADMIN);
        put("/biz/staff/{mchAccountNo}/store", BizPerms.STORE_ADMIN);
        // 「谁给谁加了什么权限」本身就是权限信息：能看它的人不该比能改它的人多
        put("/biz/staff/logs", BizPerms.STORE_ADMIN);
        // 角色定义（V71 自定义角色）。与员工管理同一档 ——
        // **能改角色 = 能一次性改掉所有持有者的能力**，比给某个人授权影响更大
        put("/biz/roles", BizPerms.STORE_ADMIN);
        put("/biz/role-perms", BizPerms.STORE_ADMIN);
        put("/biz/role/{roleCode}", BizPerms.STORE_ADMIN);
        put("/biz/role/{roleCode}/delete", BizPerms.STORE_ADMIN);

        // ---- 钱 ----
        put("/biz/settle/bills", BizPerms.FINANCE);
        put("/biz/settle/bills/{settleNo}", BizPerms.FINANCE);
        put("/biz/settle/rate-card", BizPerms.FINANCE);
        // 进项票是财务的事，与结算单同一档：能看账的人才该经手开票与对账
        put("/biz/settle/invoice-title", BizPerms.FINANCE);
        put("/biz/settle/invoices", BizPerms.FINANCE);
        put("/biz/settle/statement", BizPerms.FINANCE);
        put("/biz/merchant/payment", BizPerms.FINANCE);
        put("/biz/merchant/payment/{payChannel}/refresh", BizPerms.FINANCE);
        put("/biz/merchant/payment/store/{storeNo}", BizPerms.FINANCE);
        put("/biz/points/account", BizPerms.FINANCE);
        put("/biz/points/records", BizPerms.FINANCE);
        put("/biz/points/toggle", BizPerms.FINANCE);
        // 保证金与额度是钱的事，与结算同权限；不放宽到「登录即可」——
        // 余额和流水会暴露平台对这家店的风险判断
        put("/biz/deposit", BizPerms.FINANCE);
        put("/biz/deposit/txns", BizPerms.FINANCE);
        /*
         * 增值包（B-11.13）。挂 STORE_ADMIN 而不是更宽的码：这两条答的是
         * 「主体买了什么」，与建店、停用、挂收款号同属主体结构面 ——
         * 而那个码**只在老板手里**（BizPerms 刻意不让它进自定义角色）。
         * 店长看不到套餐是对的：他不决定要不要升档，而额度数字只会让他去催老板。
         * 试用更是如此 —— 它是一次「开通」，与建店同一个量级。
         */
        put("/biz/plan", BizPerms.STORE_ADMIN);
        put("/biz/plan/trial", BizPerms.STORE_ADMIN);
    }};

    /**
     * 汇总型端点：一次返回好几件互不相干的事，<b>任一权限即可进</b>，
     * 粒度由端上按 {@code perms} 裁。
     *
     * <p>单独列一张表而不是丢进 {@link #PUBLIC}：它们仍然要求「在这家店有角色」，
     * 空角色的人一样进不来。混进 PUBLIC 会让人以为这类端点谁都能调。
     */
    private static final Set<String> ANY_OF = Set.of("/biz/dashboard/todo");

    private static final Pattern MAPPING = Pattern.compile(
            "@(?:Get|Post|Put|Delete)Mapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"");
    private static final Pattern CLASS_BASE = Pattern.compile(
            "@RequestMapping\\(\\s*\"([^\"]+)\"\\s*\\)");

    @Test
    @DisplayName("★★ 每个 /biz 端点都必须明确它要什么权限 —— 没定的直接报出来")
    void everyBizEndpointHasADecision() throws IOException {
        Set<String> endpoints = scanBizEndpoints();
        assertThat(endpoints).as("一个端点都没扫到，正则或路径变了？").isNotEmpty();

        Set<String> undecided = new TreeSet<>(endpoints);
        undecided.removeAll(REQUIRED.keySet());
        undecided.removeAll(ANY_OF);
        undecided.removeAll(PUBLIC);
        assertThat(undecided)
                .as("这些 /biz 端点还没决定权限 —— 每一个都是潜在的越权口子。\n"
                        + "把它们加进 REQUIRED（要授权）或 PUBLIC（登录即可）：")
                .isEmpty();
    }

    @Test
    @DisplayName("★ 表里不能有已经不存在的端点 —— 名单本身也会过期")
    void noStaleEntries() throws IOException {
        Set<String> endpoints = scanBizEndpoints();
        Set<String> stale = new TreeSet<>(REQUIRED.keySet());
        stale.removeAll(endpoints);
        assertThat(stale)
                .as("这些端点已经不存在了，从 REQUIRED 删掉 —— "
                        + "留着会让人以为某个功能受保护，而它压根没了")
                .isEmpty();
    }

    @Test
    @DisplayName("★ 映射到的权限码必须真的存在于 BizPerms —— 手滑写错等于永远拒绝")
    void allCodesExist() {
        Set<String> known = knownCodes();
        List<String> bad = REQUIRED.values().stream().distinct()
                .filter(c -> !known.contains(c)).toList();
        assertThat(bad)
                .as("BizPerms 里没有这些码。写错一个字母，那个端点会对所有人 403，"
                        + "而表现只是「按钮点了没反应」")
                .isEmpty();
    }

    @Test
    @DisplayName("★★★ 表里写了要授权的，代码里必须真的有 @PreAuthorize —— 否则这张表只是许愿")
    void decisionsAreActuallyEnforced() throws IOException {
        Set<String> guarded = scanGuardedEndpoints();
        Set<String> missing = new TreeSet<>(REQUIRED.keySet());
        missing.addAll(ANY_OF);   // 「任一」也是决定，同样必须落到注解上
        missing.removeAll(guarded);
        assertThat(missing)
                .as("这些端点在表里定了权限，但代码里没有 @PreAuthorize —— "
                        + "**表是许愿，注解才是执行**。\n"
                        + "一张全绿但没落地的权限表，比没有表更危险："
                        + "它会让人以为已经防住了：")
                .isEmpty();
    }

    /** 扫出真的带 @PreAuthorize("@perm.canBiz(...)" / "canAnyBiz(...)") 的端点 */
    private static Set<String> scanGuardedEndpoints() throws IOException {
        Path root = Path.of("..").toRealPath();
        Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.toString().contains("/test/")).toList()) {
                String src = Files.readString(p);
                if (!src.contains("canBiz") && !src.contains("canAnyBiz")) {
                    continue;
                }
                Matcher base = CLASS_BASE.matcher(src);
                String prefix = base.find() ? base.group(1) : "";
                String[] lines = src.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    Matcher m = MAPPING.matcher(lines[i]);
                    if (!m.find()) {
                        continue;
                    }
                    // 注解块：往上找 3 行内有没有 canBiz
                    boolean has = false;
                    for (int k = Math.max(0, i - 3); k < i; k++) {
                        if (lines[k].contains("canBiz") || lines[k].contains("canAnyBiz")) {
                            has = true;
                        }
                    }
                    if (!has) {
                        continue;
                    }
                    String path = m.group(1);
                    String full = path.startsWith("/biz/") ? path
                            : (prefix.startsWith("/biz") ? prefix + path : null);
                    if (full != null) {
                        out.add(full);
                    }
                }
            }
        }
        return out;
    }

    /** 从 BizPerms 源码里抽常量值 —— 与运营端那条守卫同一手法 */
    private static Set<String> knownCodes() {
        Set<String> out = new TreeSet<>();
        for (var f : BizPerms.class.getDeclaredFields()) {
            if (f.getType() == String.class && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                try {
                    Object v = f.get(null);
                    if (v instanceof String s && s.startsWith("biz:")) {
                        out.add(s);
                    }
                } catch (IllegalAccessException ignored) {
                    // 私有常量读不到就跳过 —— 它们不会被 REQUIRED 引用
                }
            }
        }
        return out;
    }

    private static Set<String> scanBizEndpoints() throws IOException {
        Path root = Path.of("..").toRealPath();
        Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.toString().contains("/test/")).toList()) {
                String src = Files.readString(p);
                if (!src.contains("/biz/")) {
                    continue;
                }
                Matcher base = CLASS_BASE.matcher(src);
                String prefix = base.find() ? base.group(1) : "";
                Matcher m = MAPPING.matcher(src);
                while (m.find()) {
                    String path = m.group(1);
                    String full = path.startsWith("/biz/") ? path
                            : (prefix.startsWith("/biz") ? prefix + path : null);
                    if (full != null && full.startsWith("/biz/")) {
                        out.add(full);
                    }
                }
            }
        }
        return out;
    }
}
