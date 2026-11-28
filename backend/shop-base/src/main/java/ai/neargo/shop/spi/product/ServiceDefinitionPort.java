package ai.neargo.shop.spi.product;

import java.time.Instant;
import java.util.List;

/**
 * 服务定义：服务做这一档要多久、前后缓冲、步骤、注意事项、需要哪些角色与资源（TDD-美业域迁移新库 §2.8，
 * 《商品域-多业态商品方案》§3.5）。服务 = 商品 + SKU + 服务定义，定义由商品域存、行业经这个端口写。
 *
 * <p><b>版本</b>：{@code definitionVersion} 按商品自增，一个版本是一组行 —— 商品默认行（{@code skuNo=""}）
 * 加若干 SKU 覆盖行，连同同版本的服务要求。发布某一行就生成整组的下一个版本，其余行原样带过去。
 * 与商品修订号无关：发布不让商品的修订号或乐观锁版本加一。
 *
 * <p><b>时长同一事实一处</b>：有服务定义的商品，时长只由这里发布；默认行的时长镜像到商品行，商家保存商品时不写时长。
 */
public interface ServiceDefinitionPort {
    /** 默认行的 SKU 号。 */
    String DEFAULT_SKU = "";
    /** 定义行状态：生效。 */
    String ACTIVE = "ACTIVE";
    /** 定义行状态：停用。 */
    String INACTIVE = "INACTIVE";

    /**
     * 发布一行定义，生成该商品的下一个版本。
     *
     * <p>{@code expectedVersion} 必须等于商品当前的定义版本（还没有定义时为 0），否则冲突；
     * {@code sourceRef} 相同的重放返回当初的那个版本，不再写。SKU 覆盖行要求商品已有默认行。
     */
    Definition publish(Publish command);

    /** 这个 SKU 现在生效的定义（覆盖行已与默认行合并）；没有定义时为 {@code null}。 */
    Definition current(String entityNo, String storeNo, String skuNo);

    /** 这个 SKU 在指定版本下的定义（覆盖行已与默认行合并）；没有这一版时为 {@code null}。 */
    Definition byVersion(String entityNo, String storeNo, String skuNo, int definitionVersion);

    /**
     * 门店下各服务商品在 {@code at} 时刻生效的那一版：每个商品的默认行与覆盖行各一条（覆盖行已合并），
     * 按商品号、SKU 号排序。
     */
    List<Definition> list(String entityNo, String storeNo, Instant at);

    /**
     * 发布命令。
     *
     * @param skuNo 定位服务商品的 SKU（必须是这个主体、这家门店的服务商品）
     * @param skuOverride {@code false} 发布商品默认行；{@code true} 发布这个 SKU 的覆盖行
     * @param steps 步骤；覆盖行为 {@code null} 表示沿用默认行
     * @param notes 注意事项；覆盖行为 {@code null} 表示沿用默认行
     * @param requirements 这一行的服务要求（完整列表）；覆盖行为 {@code null} 表示沿用默认行
     * @param sourceRef 发布方的操作号，用于重放；可空（不防重）
     */
    record Publish(String entityNo, String storeNo, String skuNo, boolean skuOverride, int expectedVersion,
            Timing timing, List<Step> steps, List<String> notes, List<Requirement> requirements,
            String sourceRef, String actor) { }

    /** 时长与前后缓冲（分钟）：缓冲占资源、不计服务时长。 */
    record Timing(int durationMin, int bufferBeforeMin, int bufferAfterMin) { }

    /** 服务步骤。 */
    record Step(String stepCode, int sequenceNo) { }

    /**
     * 服务要求：需要的执行角色或资源。{@code roleCode} 有值是角色要求，否则是资源要求。
     */
    record Requirement(String roleCode, String resourceType, String skillGoodsNo, int qty, Integer sequenceNo,
            Integer offsetMin, Integer durationMin, String selectionRule) { }

    /**
     * 一个 SKU（或商品默认行）在某版本下的定义。{@code skuNo} 为 {@link #DEFAULT_SKU} 表示商品默认行；
     * {@code override} 表示这个 SKU 有自己的覆盖行。
     */
    record Definition(String entityNo, String storeNo, String goodsNo, String skuNo, int definitionVersion,
            boolean override, Timing timing, List<Step> steps, List<String> notes, List<Requirement> requirements,
            String status, String snapshotHash, Instant publishedAt) { }
}
