package ai.neargo.shop.inventory.service;

/**
 * 防腐层：平台的键 ↔ 本域的键。
 *
 * <p><b>这是唯一知道外部存在的一层。</b>{@code entityNo} / {@code storeNo} / {@code skuNo}
 * 只允许出现在这里；领域服务一律只认 {@code ownerId} / {@code locationId} / {@code itemId}。
 * 少了这条约束，「可独立交付」就是一句愿景 —— 客户那边没有 {@code entityNo}。
 *
 * <p><b>投影是单向的</b>（平台 → 本域），唯一的反向是成本价，且平台侧没有写入口。
 * 反向多一条，就有了两个真相源，而两个真相源的冲突是静默的。
 */
public interface InventoryAclService {

    /** 平台主体 → 业主。**1:1**（已定）：多执照商家仍是一个业主 —— 货是同一批货。 */
    String ownerIdOf(String entityNo);

    /**
     * 平台门店 → 库位。
     *
     * <p>{@code storeNo} 为空时落到**默认库位** —— 那是存量「主体级库存」的落点，
     * 也是单库位商家的全部。主体级不是第二种表达，就是一个库位。
     */
    String locationIdOf(String entityNo, String storeNo);

    /** 平台 SKU → 物料。不存在时按需投影一条（保存即投影，已定）。 */
    String itemIdOf(String entityNo, String skuNo);

    /**
     * 投影一件商品。**幂等**：按 {@code (owner, AISHOP, skuNo)} 找，找到就更新展示字段。
     *
     * @param saleUnit 平台的 {@code sale_unit} → 本域的 {@code base_uom}。
     *                 <b>已有流水后不再改</b>：从「件」改成「斤」，历史数字一个不变而含义全变
     */
    String upsertItem(String entityNo, String skuNo, String name, String specText,
                      String barcode, String merchantSkuCode, String saleUnit);

    /**
     * 记下来源商品还在不在架上。<b>与 {@link #upsertItem} 分开是有意的</b>：
     * 上架状态是商品状态的投影，而名字规格是商品内容的投影 ——
     * 商品下架时名字规格一个字都不变，两者本来就不是同一件事。
     *
     * <p>为什么要记：线上量到 13 组同名同规格的货，挑货弹层里几行完全一样
     *（同库位、库存也一样），商家挑哪一行都不知道挑的是什么。那几条对应的是
     * 几个**真实存在**的同名商品，其中只有一个在架。
     *
     * <p>投影不过来的（还没 upsert 过）**什么也不做**，不建空壳 ——
     * 一件没有名字的物料出现在清单里，比它不出现更难解释。
     *
     * @param onSale 在架传 {@code true}；下架传 {@code false}。
     *               <b>不接受 null</b>：调用方不知道的时候就别调，
     *               让那一列保持「还没同步过」，而不是写一个假的「在架」进去
     */
    void markItemOnSale(String entityNo, String skuNo, boolean onSale);

    /**
     * 来源 SKU <b>已经不存在了</b>（不是下架，是没了）—— 把物料归档，让它从挑货列表里消失。
     *
     * <h2>为什么「下架不能滤、消失可以滤」不是同一件事</h2>
     * 下架的货仍然是货：商家还要盘点、报损、调拨它，滤掉之后那些货就再也动不了了
     *（见 {@code markItemOnSale} 与 {@code InventoryOnSaleConsumer} 的注释）。
     * 而来源没了 <b>且库存为 0</b> 的物料，既不是货、也开不出任何单 —— 它只是噪声。
     *
     * <h2>有库存的一律不归档</h2>
     * 那是真实存在的货，只是它的来源档案没了。归档掉等于让商家再也盘不着它，
     * 账就永远平不了。这一类要留着，并由健康度扫描点名 ——
     * <b>看得见的坏账比看不见的干净更值钱</b>。
     *
     * @param apply {@code false} = 只判不写。<b>试跑必须走这一个方法</b>，
     *              而不是在调用方另写一份判据 —— 两份判据迟早分岔，
     *              而分岔的症状是「试跑说会动 3 件、真跑动了 5 件」
     * @return 归档了（或 {@code apply=false} 时「会归档」）才是 {@code true}。
     *         物料不存在、已归档、或还有库存都返回 {@code false} ——
     *         调用方靠它区分「处理了」与「有意跳过」
     */
    boolean retireItemIfEmpty(String entityNo, String skuNo, boolean apply);

    /**
     * 来源 SKU <b>退休了</b>（改规格时旧编号被逻辑删）—— 处置它那件物料。
     *
     * <h2>与 {@link #retireItemIfEmpty} 的分工</h2>
     * 那一个是<b>跑批</b>：每天扫一遍，把早就没人管的空壳收掉。
     * 这一个是<b>当场</b>：退休发生的那一刻就处置，不用等到第二天。
     * <b>两条路对同一个输入必须给出同一个答案</b>（零库存 → 归档），
     * 这条在测试里有一条专门的反向用例钉着。
     *
     * <h2>有库存的不归档</h2>
     * 归档了商家就再也盘不着那几件，账永远平不了。那一类留在 ACTIVE 上、
     * 记下 {@code succeededBy}，由健康度点名、由店主决定并到哪儿或报损掉 ——
     * <b>系统判不出「是不是同一件货」，店主一眼就能分</b>。
     *
     * @param succeededBy 接位的 skuNo；判不出就传 {@code null}。
     *                    <b>它只被记下来，不触发任何库存变动</b>
     */
    void retireItem(String entityNo, String skuNo, String succeededBy);

    /**
     * 按平台 SKU 反查业主 —— <b>交易域手里只有 skuNo，没有主体号</b>。
     *
     * <p>走 {@code inv_item_ref}（{@code system=AISHOP}）反查：SKU 在平台内全局唯一，
     * 所以这一条是确定的。找不到说明这个 SKU 还没投影过来。
     *
     * @return 业主号；投影过来之前返回 {@code null}
     */
    String ownerOfSku(String skuNo);

    /** 按平台 SKU 反查物料。同 {@link #ownerOfSku} 走外部引用表。 */
    String itemIdOfSku(String skuNo);

    /**
     * 按业主 + 平台门店号取库位。{@code storeNo} 为空时给默认库位 ——
     * 那是存量「主体级库存」的落点。
     */
    String locationOfStore(String ownerId, String storeNo);

    // ─────────────────────────── 记不记库存（TDD-商品纳入进销存开关 §3）

    /**
     * 停用 / 恢复一件物料。<b>只动状态，不碰余额与流水</b> —— 停用的物料在清单、挑货、
     * 进货盘点报损里都不出现，而它的余额和流水原样留着、只读；恢复即原样回来。
     *
     * <p>与 {@link #retireItemIfEmpty} 的区别：那一条只收零库存的空壳；
     * 这一条是店主确认过「不记库存」之后的动作，<b>有库存也停</b>。
     *
     * @param active {@code true} 恢复（物料不存在就按传入字段建一件）；{@code false} 停用（不存在就什么也不做）
     */
    void setItemActive(String entityNo, String skuNo, boolean active, String name, String specText,
                       String barcode, String merchantSkuCode, String saleUnit);

    /**
     * 这件货在进销存里还压着的东西。改为「不记库存」之前要看：
     * 有在途单据 → 拒绝（货到了没处入、单出不了库）；只有库存 → 让店主确认。
     *
     * @param kind 在途单据的种类：{@code INBOUND} 未收货的进货单 / {@code OUTBOUND} 未过账的出库单 /
     *             {@code TRANSFER} 已发出未收货的调拨 / {@code RESERVATION} 线上订单占着待出库 /
     *             {@code COUNT} 正在盘的盘点单
     */
    record Blocker(String kind, String docNo) {
    }

    record ItemState(String skuNo, int onHand, int reserved, java.util.List<Blocker> blockers) {
    }

    /** 查不到物料的 SKU 不在结果里（它在进销存里什么都没有）。只读，不建业主不建物料 */
    java.util.Map<String, ItemState> stateOf(String entityNo, java.util.Collection<String> skuNos);

    /**
     * 批量把 skuNo 换成物料号（只认 {@code AISHOP} 引用）。查不到的 sku 不出现在结果里。
     *
     * <p>有 {@link #itemIdOf} 还要它：按行逐个查在列表页是几百次往返，
     * 而「这一屏的货分别是哪个物料」本来就是一次能查完的事。
     */
    java.util.Map<String, String> itemIdsOf(String entityNo, java.util.Collection<String> skuNos);

    // ─────────────────────────── 写回商城（TDD-商品纳入进销存开关 §18，只读）

    /** 一张已过账单据动到的一行：哪个主体的哪件货、在哪个库位 */
    record PostedLine(String entityNo, String skuNo, String locationId) {
    }

    /**
     * 按单号取它动到的（主体, SKU, 库位），去重。取自流水 —— 每次过账每行都写一条，
     * 进货、出库（含线上订单的销售出库）、盘点、调拨两头都在里面。
     * 物料没有平台 SKU 引用的（纯进销存自建物料）不返回：商城里没有它可写。
     */
    java.util.List<PostedLine> postedLines(String docNo);

    /** 这家店实际出货的库位（门店库位，或它的发货源仓）。业主不存在返回 {@code null}，不建 */
    String stockLocationOf(String entityNo, String storeNo);

    /** 某店某 SKU 在出货库位上的数。安全库存取库位覆盖，没有取物料默认 */
    record StockAt(int onHand, int reserved, int safety) {
        /** 可用 = 实存 − 占用 − 安全库存，不小于 0 */
        public int available() {
            return Math.max(0, onHand - reserved - safety);
        }
    }

    /** 查不到业主或物料返回 {@code null}（进销存里没有这件货）。没有余额行按 0 */
    StockAt stockAt(String entityNo, String storeNo, String skuNo);
}
