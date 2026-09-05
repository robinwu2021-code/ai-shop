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
            // 跨证照的门店切换器（多证照）。与 /biz/store/list 同一个理由，
            // 只是范围从「当前这张证照」扩到「我名下全部证照」——
            // 它同样按 storeNos 口径裁剪，店员只拿到自己被授权的那几家。
            // 挂 biz:store:admin 的话，「A 店店长 + B 店店员」这种人一家都切不了
            "/biz/stores/mine",
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
        // ---- 进销存（inv_*，独立库）----
        // 三档权限，判据是「这个动作能改什么」而不是「它在哪一屏」：
        //   · biz:stock          改库存与开单 —— 店员与理货员每天的活
        //   · biz:customer       报表与导出 —— 含毛利与销量，是**经营数据**，
        //                        店员有 biz:stock 但不该看得到
        //   · biz:store:admin    建仓与改发货源 —— 门店级配置，与改库存是两拨人
        put("/biz/inventory/summary", BizPerms.STOCK);
        put("/biz/inventory/balances", BizPerms.STOCK);
        // 跨店总览。与 balances 同权：都是「看得见这家的货」，只是维度从「一个库位」换成「全部」
        put("/biz/inventory/cross-store", BizPerms.STOCK);
        put("/biz/inventory/items/{itemId}", BizPerms.STOCK);
        put("/biz/inventory/ledger", BizPerms.STOCK);
        // 开单时挑货。与 balances 同权：都是「看得见这家的货」
        put("/biz/inventory/pickable", BizPerms.STOCK);
        /*
         * 供应商档案（S2/S5）。**与进货同一个码，不新造** ——
         * 建供应商与记一笔进货是同一件事的两半，分成两个码的结果是
         * 「能记账但选不到供应商」。
         */
        put("/biz/inventory/suppliers", BizPerms.STOCK);
        put("/biz/inventory/suppliers/{no}", BizPerms.STOCK);
        put("/biz/inventory/suppliers/{no}/active", BizPerms.STOCK);
        /*
         * 承运方只读列表。**在履约域不在进销存**，但判的是进销存的码 ——
         * 它存在的唯一理由是调拨发货时选「谁在运」，而调拨本身就判 STOCK。
         * 单为它造一个码，只会多出一种「能调拨但选不到承运方」的角色。
         */
        put("/biz/fulfillment/carriers", BizPerms.STOCK);
        // 界面上的「改数」，底下是一次单件盘点 —— 便捷端点，不是第二条改余额的路
        put("/biz/inventory/adjust", BizPerms.STOCK);
        // 按条码找货：只读，与挑货同一档
        put("/biz/inventory/items/by-barcode", BizPerms.STOCK);
        // 商品页读「这件货在进销存的账」。与 balances 同权：都是「看得见这家的货」
        put("/biz/inventory/item-by-sku", BizPerms.STOCK);
        // 设安全库存：判 STOCK 而不是更高的码 —— 阈值是理货员日常要调的东西
        //（今天补了 5 袋、明天到货 50，线跟着动），与「改库存」同一档
        put("/biz/inventory/safety-stock", BizPerms.STOCK);
        put("/biz/inventory/counts", BizPerms.STOCK);
        put("/biz/inventory/counts/{no}", BizPerms.STOCK);
        put("/biz/inventory/counts/{no}/lines", BizPerms.STOCK);
        put("/biz/inventory/counts/{no}/post", BizPerms.STOCK);
        put("/biz/inventory/inbounds", BizPerms.STOCK);
        put("/biz/inventory/inbounds/{no}", BizPerms.STOCK);
        put("/biz/inventory/inbounds/{no}/post", BizPerms.STOCK);
        put("/biz/inventory/inbounds/{no}/void", BizPerms.STOCK);
        // 出库单**不接受 purpose=SALE**（销售出库只能由预留 commit 产生），
        // 这一条是 Service 里的闸门，不靠权限码兜
        put("/biz/inventory/outbounds", BizPerms.STOCK);
        put("/biz/inventory/outbounds/{no}/post", BizPerms.STOCK);
        put("/biz/inventory/outbounds/{no}/void", BizPerms.STOCK);
        put("/biz/inventory/documents", BizPerms.STOCK);
        put("/biz/inventory/transfers", BizPerms.STOCK);
        put("/biz/inventory/transfers/{no}", BizPerms.STOCK);
        put("/biz/inventory/transfers/{no}/ship", BizPerms.STOCK);
        put("/biz/inventory/transfers/{no}/receive", BizPerms.STOCK);
        put("/biz/inventory/transfers/{no}/void", BizPerms.STOCK);
        put("/biz/inventory/locations", BizPerms.STOCK);
        put("/biz/inventory/locations/{id}/source", BizPerms.STORE_ADMIN);
        put("/biz/inventory/report/monthly", BizPerms.CUSTOMER);
        put("/biz/inventory/report/ranking", BizPerms.CUSTOMER);
        put("/biz/inventory/export", BizPerms.CUSTOMER);

        // ---- 履约：三种活面对三种对象 ----
        // 证照识别：与「传资质证件」同一档 —— 能传证的人才能让系统替他读证。
        // 它不落任何东西（不写库、不进桶），但读到的是身份证与执照的完整字段，
        // 所以不能按 PUBLIC 放行
        put("/biz/qualifications/recognize", BizPerms.STORE);

        // ---- 证照（多证照）：与建店同一档敏感度，只有老板拿得到这个码 ----
        // BizPerms.assignableCodes() 显式排除了 STORE_ADMIN，自定义角色勾不到它，
        // 于是「只有老板能管证照」是结构保证的，不用另立一个新权限码
        put("/biz/entities", BizPerms.STORE_ADMIN);
        put("/biz/entity/{entityNo}", BizPerms.STORE_ADMIN);

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
        put("/biz/order/{subOrderNo}/confirm-offline-pay", BizPerms.RECEIVE);

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
        put("/biz/goods/{goodsNo}/publish", BizPerms.GOODS);
        put("/biz/goods/{goodsNo}/publish-preview", BizPerms.GOODS);
        put("/biz/goods/{goodsNo}/draft", BizPerms.GOODS);
        put("/biz/goods/{goodsNo}/draft/discard", BizPerms.GOODS);
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
        // 商品参数（V250）：与销售规格同一条权限 —— 都是建品链路的一环
        put("/biz/spec-props", BizPerms.GOODS);
        put("/biz/pickable-props", BizPerms.GOODS);
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
        // 商品编码批量导入导出（P4）：改的是商品数据，用同一个码
        put("/biz/sku-identity/export", BizPerms.GOODS);
        put("/biz/sku-identity/import/plan", BizPerms.GOODS);
        put("/biz/sku-identity/import", BizPerms.GOODS);
        // 绑条码：判 GOODS 不是 STOCK —— 它改的是**商品**上的一列（prd_sku.barcode），
        // 与批量导入同一张表同一批人。理货员扫得了码（找货判 STOCK），
        // 但把码绑到哪件商品上是商品的事
        put("/biz/sku-identity/barcode", BizPerms.GOODS);
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
        // 范围预览：只读、不写库，但读的是这家主体的经营范围与买家分布 —— 与改范围同一把钥匙
        put("/biz/store/scope-preview", BizPerms.STORE);
        // 会员（P1）：沿用客户资产那个码 —— 会员就是「我的客户」那一页的升级版
        put("/biz/members", BizPerms.CUSTOMER);
        put("/biz/members/stats", BizPerms.CUSTOMER);
        put("/biz/members/{memberNo}", BizPerms.CUSTOMER);
        // 录入与标签（P2）：同一屏、同一批人、同一个码
        put("/biz/members/tags", BizPerms.CUSTOMER);
        put("/biz/member-tags", BizPerms.CUSTOMER);
        put("/biz/member-tags/{tagNo}", BizPerms.CUSTOMER);
        put("/biz/member-tags/{tagNo}/merge", BizPerms.CUSTOMER);
        put("/biz/member-segments", BizPerms.CUSTOMER);
        put("/biz/member-segments/{segmentNo}/remove", BizPerms.CUSTOMER);
        put("/biz/member-segments/preview", BizPerms.CUSTOMER);
        // 同一路径两个方法两种权限：读是 biz:customer，改是 biz:store:admin
        // （改口径会改变全主体「新客」的含义）。表里按更严的那个记
        put("/biz/member-settings", BizPerms.STORE_ADMIN);
        // 券是花钱的东西，与看会员不是一件事：店员该能看名单，不该能发券
        put("/biz/coupons", BizPerms.CAMPAIGN);
        put("/biz/coupons/{couponNo}", BizPerms.CAMPAIGN);
        put("/biz/coupons/{couponNo}/status", BizPerms.CAMPAIGN);
        put("/biz/coupons/{couponNo}/issue", BizPerms.CAMPAIGN);
        put("/biz/coupon-issues", BizPerms.CAMPAIGN);
        // 核销的人是收银台前的店员，不是建券的老板 —— 与自提核销同一个码
        put("/biz/coupon-redeem/{code}", BizPerms.VERIFY);
        put("/biz/coupon-redeem", BizPerms.VERIFY);
        put("/biz/activities", BizPerms.CAMPAIGN);
        put("/biz/activities/{activityNo}", BizPerms.CAMPAIGN);
        put("/biz/activities/{activityNo}/status", BizPerms.CAMPAIGN);
        put("/biz/activity-conflicts", BizPerms.CAMPAIGN);
        // 试算给看会员的人（店员）；真发要 campaign —— 给几百人推消息不是店员该按的
        put("/biz/member-reach/plan", BizPerms.CUSTOMER);
        put("/biz/member-reach/send", BizPerms.CAMPAIGN);
        // 只改公告：与整份门面同一个码 —— 它写的是同一张表的同一家店
        put("/biz/store/announcement", BizPerms.STORE);
        // 删一条常用语：还是那张表那一家店，同一个码
        put("/biz/store/announcement/recent/remove", BizPerms.STORE);
        // 门店送货方式（方案 v4）：GET/PUT 同路径同进退，都归门店管理面
        put("/biz/stores/{storeNo}/fulfillment", BizPerms.STORE);
        // 预约排期与送货方式同类：都是「这家店怎么履约」的配置，所以共用 STORE
        put("/biz/stores/{storeNo}/appointment-slots", BizPerms.STORE);
        put("/biz/appointment-slots/{slotNo}/close", BizPerms.STORE);
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
        // 提现（V288 新增）：与结算同一把权限 —— 能看账的才能提钱
        put("/biz/settle/withdraw", BizPerms.FINANCE);
        put("/biz/settle/bills", BizPerms.FINANCE);
        put("/biz/settle/income", BizPerms.FINANCE);
        put("/biz/settle/bills/{settleNo}", BizPerms.FINANCE);
        put("/biz/settle/rate-card", BizPerms.FINANCE);
        // 进项票是财务的事，与结算单同一档：能看账的人才该经手开票与对账
        put("/biz/settle/invoice-pending", BizPerms.FINANCE);
        put("/biz/settle/invoice-title", BizPerms.FINANCE);
        put("/biz/settle/invoices", BizPerms.FINANCE);
        put("/biz/settle/statement", BizPerms.FINANCE);
        // 账期批次：「这一批什么时候放、卡在哪」
        put("/biz/settle/batch", BizPerms.FINANCE);
        put("/biz/merchant/payment", BizPerms.FINANCE);
        // 「能开哪些通道」与「已开的怎么样了」是同一件事的两面，同一档权限
        put("/biz/merchant/pay-channel", BizPerms.FINANCE);
        // 欠款：商家要看得到「为什么欠、扣到哪了」，与看账同一档
        put("/biz/merchant/debt", BizPerms.FINANCE);
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

    /**
     * ⚠️ <b>Patch 是 2026-08-25 补进来的</b>：此前这个正则只认 Get/Post/Put/Delete，
     * 于是任何 {@code @PatchMapping} 的 /biz 端点都会**整个绕过权限决策检查** ——
     * 表里没有它、这条用例也不报，而它在线上是真的能被调用的。
     * 加一种 HTTP 方法就要来这里加一次，否则守卫看着绿、洞开着。
     */
    private static final Pattern MAPPING = Pattern.compile(
            "@(?:Get|Post|Put|Patch|Delete)Mapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"");
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
                    /*
                     * 往上走过整个「注解 + 注释」块找 canBiz，**不是往上数固定几行**。
                     *
                     * 此前写死 3 行，于是在 @PreAuthorize 与 @PostMapping 之间夹一段
                     * 五行的注释，就会被报成「表里定了权限但代码里没有 @PreAuthorize」——
                     * 而那个端点在运行时明明是受保护的。假警报和漏报一样伤：
                     * 这条用例的价值全在于它红的时候人会当真。
                     *
                     * 停在**空行或上一个方法的结尾**：注解块不会跨过这两者。
                     */
                    boolean has = false;
                    for (int k = i - 1; k >= 0; k--) {
                        String up = lines[k].trim();
                        if (up.isEmpty() || up.equals("}") || up.startsWith("public ")
                                || up.startsWith("private ") || up.startsWith("return ")) {
                            break;
                        }
                        if (up.contains("canBiz") || up.contains("canAnyBiz")) {
                            has = true;
                            break;
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
