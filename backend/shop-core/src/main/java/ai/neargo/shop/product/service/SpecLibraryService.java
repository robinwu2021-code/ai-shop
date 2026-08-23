package ai.neargo.shop.product.service;

import ai.neargo.shop.product.dto.CategorySpecVO;
import ai.neargo.shop.product.dto.SpecTemplateVO;

import java.util.List;
import java.util.Map;

/**
 * 规格库（V195）：规格项 / 规格值 / 类目绑定 / 商家覆盖四层的读侧。
 *
 * <p>单独一个服务而不是塞进 {@code MerchantGoodsServiceImpl}：那个类已经背着商品、SKU、
 * 库存、门店价五件事，再挂四张表的组装会让「改规格库」与「改商品保存」变成同一处风险。
 *
 * <p><b>对外形状不变。</b>商家侧仍旧拿到 {@code SpecTemplateVO[]}（name + options），
 * b-app 一个字都不用改 —— 换掉的是它背后的数据来源与「谁是主维度」的判据。
 */
public interface SpecLibraryService {

    /**
     * 某个类目该给商家看哪些规格。
     *
     * <p>只给 <b>SALE</b>（销售规格）维度：材质、产地这类 PROP 属性不进 SKU 矩阵，
     * 下发了商家就会把它们建成规格维度 —— 而那正是这一版要避免的笛卡尔积爆炸。
     *
     * @param merchantNo 用于带上这家店在平台维度下自建的值（scope=MERCHANT）
     * @return 主维度排第一（{@code is_primary}），其余按 sort。类目没绑任何维度时返回空表
     */
    List<SpecTemplateVO> templatesForCategory(String merchantNo, String categoryNo);

    /**
     * 把商家提交的规格选项<b>反查成值编号</b>，供 SKU 快照使用。
     *
     * @param dimNo  规格组对应的维度（端上原样回传的 templateNo）；空 = 这一组不是模板来的
     * @param labels 该维度上商家实际用的选项文案
     * @return 文案 → valueNo。查不到的不入表 —— 手打的规格没有值编号，这是事实，不该造一个
     */
    Map<String, String> resolveValueNos(String merchantNo, String dimNo, List<String> labels);

    /** 运营端：所有在售类目 × 它支持的规格（含未绑定的类目，那正是要看的） */
    List<CategorySpecVO> catalog();

    // ---------------------------------------------------------------- 运营端维护

    /**
     * 规格库列表。
     *
     * @param universal {@code true} 只看通用、{@code false} 只看专用、{@code null} 全部。
     *                  <b>两个分区是运营端的主入口</b>：通用维度改一条全站生效，
     *                  专用维度只影响一个类目 —— 混在一张表里，改的人不知道自己动了多大范围
     */
    List<SpecDimVO> listDims(Boolean universal, String keyword, boolean includeArchived);

    /** 新建或改一个维度（{@code dimNo} 空 = 新建）。名字要过命名规范 */
    SpecDimVO saveDim(SaveDimCommand cmd);

    /** 归档 / 恢复一个维度。<b>不物理删</b> —— 历史商品还要靠它解释自己的 code */
    SpecDimVO archiveDim(String dimNo, boolean archived);

    /** 新建或改一个值（{@code valueNo} 空 = 新建）。QUANT 维度下必须给归一量 */
    SpecValueVO saveValue(SaveValueCommand cmd);

    SpecValueVO archiveValue(String valueNo, boolean archived);

    /**
     * 把商家自建的值<b>提升为平台值</b>：改 scope，编号不变，已经建好的商品不用重建。
     * 用得多的自有值就该进公共值池 —— 而这一步是运营的判断，不是自动发生的。
     */
    SpecValueVO promoteValue(String valueNo);

    /**
     * 合并重复值：把 {@code fromValueNos} 收敛到 {@code intoValueNo}。
     *
     * <p><b>三件事一起做</b>，少一件这次合并就只完成了一半：
     * <ol>
     *   <li>被合并的值置 {@code MERGED} 并记下 {@code merged_into} —— 不删，
     *       历史商品的 SKU 快照里存着它的编号</li>
     *   <li>它们的文案与别名<b>并进保留值的别名</b> —— 下次有人再输「750克」，
     *       撞车检测认得出来，不会又造一条</li>
     *   <li><b>改写 SKU 快照</b>里的 {@code option_value_nos} —— 不改的话，
     *       那批商品仍指向一个已退役的编号，聚合时它们与保留值那一堆各算各的</li>
     * </ol>
     *
     * @return 改写了多少条 SKU 快照
     */
    int mergeValues(String intoValueNo, List<String> fromValueNos);

    /** 类目绑定：整份替换这一个类目的维度列表（顺序即 sort，主维度只能有一个） */
    void saveCategoryBindings(String categoryNo, List<BindingCommand> bindings);

    // ---------------------------------------------------------------- 商家侧自定义

    /**
     * 商家在<b>平台维度下</b>加一个自己的值。
     *
     * <p>最常见的那种情形：「我这袋是 750g，平台没这一档」。它挂在平台的「重量」上，
     * 所以与平台值天然同轴 —— 复制一整套模板做不到这件事。
     *
     * <p><b>撞车不当错处理</b>：与平台值的文案、别名或归一量撞上时，
     * 不新建，直接返回那个平台值 —— 自定义不该变成制造重复值的机器。
     */
    SpecValueVO addMerchantValue(String merchantNo, String dimNo, String label,
                                 java.math.BigDecimal numericValue);

    /**
     * 商家自建一个维度（平台没有的，如「辣度」）。
     *
     * <p><b>只在这家店可见，且不参与跨店聚合</b> —— 这是它的定义，不是缺陷。
     * 界面上要把这句话说出来，否则商家会以为自己建的规格能跟别家比价。
     */
    SpecDimVO addMerchantDim(String merchantNo, String name, List<String> labels);

    /** 商家自建配额：维度上限 */
    int MERCHANT_DIM_LIMIT = 10;
    /** 商家自建配额：同一维度下的自有值上限 */
    int MERCHANT_VALUE_LIMIT = 20;

    record SaveDimCommand(String dimNo, String code, String name, String valueType, String unit,
                          String usageType, boolean universal, Integer sort) {
    }

    record SaveValueCommand(String valueNo, String dimNo, String code, String label,
                            java.math.BigDecimal numericValue, String numericUnit,
                            List<String> aliases, Integer sort) {
    }

    /**
     * @param valueNos 这一类目在该维度下开放的取值；空 = 不裁剪（该维度全部值可选）
     * @param labels   {@code valueNo → 类目内换名}；500g 在蔬菜下叫「约1斤」
     */
    record BindingCommand(String dimNo, String usageType, boolean primary, boolean required,
                          List<String> valueNos, java.util.Map<String, String> labels) {
    }

    /** @param inUse 有几个类目绑了它 —— 归档前要知道自己在动多大范围 */
    record SpecDimVO(String dimNo, String code, String name, String valueType, String unit,
                     String usageType, boolean universal, String scope, String entityNo,
                     int sort, String status, int valueCount, int inUse,
                     List<SpecValueVO> values) {
    }

    /** @param merchantCount 有几家店在用（自有值提升为平台值的判据） */
    record SpecValueVO(String valueNo, String dimNo, String code, String label,
                       java.math.BigDecimal numericValue, String numericUnit,
                       List<String> aliases, String scope, String entityNo,
                       int sort, String status, int merchantCount) {
    }
}
