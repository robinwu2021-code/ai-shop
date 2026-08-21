package ai.neargo.shop.config;

import ai.neargo.common.data.scope.DataScopeRegistrar;
import ai.neargo.common.data.scope.DataScopeTableRegistry;
import ai.neargo.shop.auth.ScopeDim;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 数据域表注册 —— **越权防线的第 ③ 道**（TDD-backend §5.2/§5.3）。
 * 声明「哪张表在哪个维度用哪一列过滤」，由 {@code DataScopeHandler} 在 SQL 层自动追加条件，
 * <b>业务代码零 where</b>。
 *
 * <p><b>两条必须记住的性质</b>（powerbank 用事故换来的）：
 * <ol>
 *   <li><b>未注册的表 = 不过滤</b>。漏注册不报错、不告警，只会静默放行越权数据。
 *       所以「注册」是建表的一部分，不是可选项。</li>
 *   <li><b>已注册的表是 fail-closed</b>：当前会话的维度在该表锚点里找不到列时，
 *       handler 生成的是 {@code 1=0} 而不是放行。因此一张表被注册后，
 *       <b>所有可能访问它的主体维度都要登记</b> —— 漏一个，那类主体就全瞎。
 *       典型翻车：订单表登记了 MERCHANT 却漏了 SELF，C 端「我的订单」立刻空列表。</li>
 * </ol>
 *
 * <p>S0 只登记两张基础设施表之外的骨架；各域建表时在此补登记，
 * 由 {@code DataScopeCoverageTest} 校验「带归属列的表都已注册」。
 */
@Component
public class DataScopeRegistration implements DataScopeRegistrar {

    @Override
    public void register(DataScopeTableRegistry registry) {

        // —— 交易：子订单是商家视角的账本，也是 C 端「我的订单」的来源 ——
        // SELF 必须登记，理由见类注释第 2 条。
        // COMMUNITY 是运营端接入时补的（V137 的冗余列）：运营会话的维度是
        // MERCHANT/COMMUNITY/PICKUP，缺哪一个，配了那个维度的运营就整页空白。
        registry.register("ord_sub_order", Map.of(
                ScopeDim.SELF, "user_no",
                ScopeDim.MERCHANT, "entity_no",
                ScopeDim.COMMUNITY, "community_no",
                ScopeDim.PICKUP, "pickup_no"));

        // 主单跨商家（一次结算拆成多个商家的子单），没有单一 entity_no/pickup_no ——
        // 运营端不列主单，只经已授权子单按主键回捞（见 MerchantOrderServiceImpl#toOpsVO）。
        registry.register("ord_order", Map.of(
                ScopeDim.SELF, "user_no",
                ScopeDim.COMMUNITY, "community_no"));

        // —— 商品：商家只能改自己的货 ——
        registry.register("prd_goods", Map.of(
                ScopeDim.MERCHANT, "entity_no"));
        /*
         * SKU（批⑤ P2-4）。**此前是登记表里的一个口子**：`prd_sku` 未注册 = 未注册表放行，
         * 于是不带过滤条件的 `GET /ops/skus` 是全平台可见 —— 配了商家域的运营也一样。
         * `PlatformProductServiceImpl.matchingGoodsNos` 只补上了「带过滤」的那一半。
         *
         * ⚠️ 注册它的**前置**是把所有买家侧读写显式豁免（fail-closed：C 端会话的维度是
         * SELF，在 SKU 的锚点里找不到，拼出的是 1=0 而不是放行）。已经做了：
         * `GoodsServiceImpl.loadSkus/skuPrice`、`GoodsQueryPortImpl.snapshot`、
         * `StockPortImpl` 的五处原子扣减。少豁免一处的症状是
         * <b>商品显示 ¥0 / 购物车空 / 下单说库存不足</b>，且日志干净。
         */
        registry.register("prd_sku", Map.of(
                ScopeDim.MERCHANT, "entity_no"));

        /*
         * 履约任务表 `ful_pickup_task` 这里曾经登记着，而**这张表从来没有建过** ——
         * 没有迁移、没有实体、没有任何 Java 引用。登记一张不存在的表不报错
         * （没有查询会碰到它），坏处是它让人以为这块已经防住了。
         * 2026-08-14 由 `ops-data-scope.test.ts` 的 G3 点名后删除。
         * 真做这张表时，连同 SELF/PICKUP/MERCHANT 三个锚点一起加回来。
         */

        // —— 邻里自提：作用域是单个团，且发起人零报酬（ADR-005）——
        // PICKUP 是运营端接入时补的：表上本来就有 pickup_no，只是没登记 ——
        // 而没登记的后果不是「不过滤」，是配了自提点域的运营看这张表全空（fail-closed）。
        registry.register("ful_group_pickup", Map.of(
                ScopeDim.SELF, "user_no",
                ScopeDim.PICKUP, "pickup_no",
                ScopeDim.GROUP, "group_no"));

        // —— 结算：钱的可见性最敏感，只有商家自己和平台财务 ——
        registry.register("stl_bill", Map.of(
                ScopeDim.MERCHANT, "entity_no"));

        /*
         * —— 商家主体与门店（批②，2026-08-14）——
         *
         * **只登记 MERCHANT 一个维度**，COMMUNITY / PICKUP 刻意不登记。
         *
         * 一度打算给 mch_store 加一列冗余 community_no 好登记 COMMUNITY，理由是
         * 「社区运营打开门店档案会是空白」。那个判断错了两层：
         *   ① 门店的社区是**多值**的（一家店可以在多个社区各挂一个自提点，
         *      cmt_pickup_point.owner_ref 上没有唯一键），单列表达不了 ——
         *      取其中一个的后果是「另一个社区的运营看不到这家店」，
         *      比整页空白更难发现；
         *   ② 更根本的是**那个担忧是假想的**：COMMUNITY_OPS 的 15 个权限码里
         *      一个 merchant:* 都没有，而 GET /ops/stores 要 merchant:merchant:read
         *      —— 它根本进不了这个页面。
         *
         * 教训：**加一列冗余数据之前，先确认那个角色进不进得来。**
         *
         * 若将来给社区运营开了门店档案，回到
         * TDD-运营端数据域接入 §6.1 的三个选项里重选，不要直接加列。
         */
        registry.register("mch_entity", Map.of(
                ScopeDim.MERCHANT, "entity_no"));

        registry.register("mch_store", Map.of(
                ScopeDim.MERCHANT, "entity_no"));

        /*
         * 门店货架。登记 MERCHANT 是给运营端看的（「这家店摆了哪几类」）；
         * B 端自己读写走 executeWithoutScope —— B 端会话是 SELF 维度，
         * 接上就是 1=0，商家自己的货架当场全空。归属由 requireMerchantNo + storeNos 保证。
         */
        registry.register("mch_store_category", Map.of(
                ScopeDim.MERCHANT, "entity_no"));

        /*
         * 门店价。登记 MERCHANT 是给运营端看的；取价链路全程 executeWithoutScope
         * （调用方是 C 端会话，SELF 维度）。
         *
         * **万一哪条路径忘了豁免，后果是回退主体价而不是 0** —— 与库存那张表相反，
         * 那边漏豁免会把货变成「没货」，这边最多是「没享受到本店价」。
         * 这个方向差别正是 prd_store_price 与 prd_store_stock 回退语义相反的延伸。
         */
        registry.register("prd_store_price", Map.of(
                ScopeDim.MERCHANT, "entity_no"));

        /*
         * —— 图片资产记账 sys_media_asset：**刻意不登记**（TDD-图片存储与空间回收）——
         *
         * 它带 entity_no 与 store_no 两个归属列，看着该登记。一开始也确实登记了
         * MERCHANT → entity_no，然后被 MediaUploadFlowTest 当场按住：
         *
         *     UPDATE sys_media_asset SET status=? WHERE id=? AND 1 = 0
         *
         * 上传的第三步（PENDING → ACTIVE）影响 0 行，记账行永远停在 PENDING。
         * 这正是类注释第 2 条说的 fail-closed —— 而它的表现恰好是最难查的那种：
         * 上传返回 200、文件也确实落盘了，只有那一列状态不对。
         *
         * 不登记的理由不是「绕开麻烦」，是**它和 sys_outbox / sys_idempotent 同类**：
         * `sys_` 前缀的横切基础设施表，由系统自己写、平台自己读，
         * 没有任何 B 端端点把它暴露给商家。而运营端那个页面（platform:media:read，
         * 只发平台角色）要的恰恰是**全平台视图** —— 过滤才是错的。
         *
         * ⚠️ **触发重新登记的那一天**：B 端出现「我的存储占用」这类页面时。
         * 那时要连同「这个会话到底带哪几个维度」一起验，别照抄 prd_goods 的写法 ——
         * 上面这次 1=0 就是照抄来的。
         */
    }
}
