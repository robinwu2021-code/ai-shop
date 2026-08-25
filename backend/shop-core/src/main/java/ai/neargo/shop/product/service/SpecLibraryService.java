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
     * 这一类的**商品参数**（产地、保质期、材质…）—— {@code usage_type = PROP} 的那批。
     *
     * <p>与 {@link #templatesForCategory} 是同一条装配链路，只换 usage 判据：
     * 类目绑定 → 本店覆盖（停用/改名/排序/加减档位）→ 他自己加进来的。
     *
     * <p><b>参数不参与 SKU</b>：买家不用挑，商家填一个值就行 ——
     * 所以它不进 {@code spec_groups}，也不影响价格与库存。
     * 混进销售规格的后果是「不锈钢 × 24cm × 黑色」变成一个要单独定价备货的行，
     * 而他其实只想说「这口锅是不锈钢的」。
     */
    List<SpecTemplateVO> propsForCategory(String merchantNo, String categoryNo);

    /**
     * 这家店<b>能用的全部规格维度</b>，给「加一个规格组」那个入口挑。
     *
     * <p>与 {@link #templatesForCategory} 的差别是**范围**：那个只给本类目配好的几条
     * （选完类目自动预填用它），这个还要把<b>平台通用维度</b>（颜色、尺码、口味……）
     * 和这家店已经建过的一并给出。
     *
     * <p>为什么需要它：此前商家点「自定义规格」是<b>盲输</b> —— 界面上从没出现过
     * 平台有哪些维度，他只能凭记忆敲一个名字。后端确实有「与平台重名就用平台那个」
     * 的兜底，但那要他<b>恰好敲对字</b>：敲「味道」而平台叫「口味」就撞不上，
     * 于是库里多一个只有他一家能用的维度，他的货从此掉出跨店聚合。
     * 先看后挑，比敲对字可靠得多。
     *
     * <p>同样只给 SALE：PROP（材质、产地、保质期）不进 SKU 矩阵。
     *
     * @param categoryNo 当前类目，可空。给了就把这一类目已配的排在最前并标出来
     * @return 顺序即建议顺序：本类目已配的 → 平台通用 → 这家店自建的
     */
    List<SpecTemplateVO> pickableDims(String merchantNo, String categoryNo);

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

    /**
     * 这家店<b>自己建的</b>规格维度，给「我的规格」那一页用。
     *
     * <p>与 {@link #pickableDims} 的差别：那个是「建品时能挑什么」（平台的也在里面），
     * 这个是「我拥有什么、能改什么」。此前商家<b>只能建、不能管</b> ——
     * 建品页里输一个名字就落进规格库，之后没有任何地方看得到它，
     * 建错了（打错字、想改叫法）只能一直留着，还占着配额。
     *
     * @return 带用量（用在几件商品上）与配额，按创建顺序
     */
    List<MerchantDimVO> myDims(String merchantNo);

    /**
     * 这家店<b>按货架类目</b>能用到的规格。
     *
     * <p>与 {@link #pickableDims} 的差别是**范围来自哪里**：那个按「当前正在建的商品
     * 属于哪个类目」给，这个按「这家店摆了哪几类」给 —— 「我的规格」那一页不在
     * 任何一个类目下，它要回答的是整店的问题。
     *
     * <p>为什么不直接给平台的 13 个通用维度：一家只卖蔬菜和肉的店，
     * 看到「尺码」「口径」「时长」是纯噪音，而噪音会让他觉得这一页与自己无关。
     * 按他真正摆出来的类目给，每一行他都认得。
     *
     * @return 按货架顺序；没配规格的类目**也在列表里**（dims 为空）——
     *         那是运营侧的缺口，商家看得见才问得出来
     */
    List<StoreCategorySpecVO> dimsByStore(String merchantNo, String storeNo);

    /**
     * 某个维度下<b>平台有的全部档位</b>（含这家店在该维度下自建的）。
     *
     * <p>给「＋」那个弹框做候选：类目通常只裁了其中几档（蔬菜的重量只给 4 档），
     * 而商家要加的往往正是没裁进来的那一档（「平台重量有 750g，只是这一类没配」）。
     * 不给候选的话他只剩手输一条路 —— 手输的值没有编码，跨店聚合就断了。
     */
    List<SpecTemplateVO.Option> valuesOfDim(String merchantNo, String dimNo);

    /**
     * 保存这家店对某个类目规格的覆盖：**用哪几个、什么顺序、叫什么**。
     *
     * <p>整份替换该类目的覆盖行 —— 覆盖是一组有序的偏好，逐条 diff 没有收益。
     *
     * <p><b>改名只改展示</b>：`dim_no` 一个字不变，所以跨店聚合照常成立。
     * 与「我的类目」的 display_name 同一个模式 —— 那里已经证明过这条边界站得住。
     *
     * <p><b>只写与平台不同的那些</b>：跟平台一样的不落行。这样运营给类目加了新维度，
     * 没动过手的商家自动获得它；而灌一份全量副本的话，新维度永远到不了他们那儿，
     * 且没有任何一处会提示。
     */
    void saveOverrides(String merchantNo, String categoryNo, List<OverrideCommand> dims);

    /**
     * @param dimNo   平台维度编号
     * @param enabled 本店用不用它
     * @param label   <b>本店叫法</b>；空 = 用平台的。只换展示，dimNo 不变 ——
     *                所以三家店的同一个规格照样聚得到一起
     * @param values  用哪几档，按**平台值编码**（Option.code）索引
     */
    record OverrideCommand(String dimNo, boolean enabled, String label,
                           List<ValueOverrideCommand> values) {
    }

    /** @param code 平台值编码（与端上拿到的 Option.code 同一个） */
    record ValueOverrideCommand(String code, boolean enabled) {
    }

    /** @param categoryName 店主改过名的用店主的叫法 —— 这一页是给他看的 */
    record StoreCategorySpecVO(String categoryNo, String categoryName,
                               List<SpecTemplateVO> dims) {
    }

    /**
     * 改名。**不影响已经建好的商品** —— 商品存的是规格快照，
     * 改维度名不会把历史订单里的「辣度」变成别的字。
     */
    SpecDimVO renameMerchantDim(String merchantNo, String dimNo, String name);

    /**
     * 停用 / 启用。<b>停用不是删除</b>：历史商品的规格组要靠它解释自己是什么，
     * 真删之后那些商品的规格就成了没有出处的字符串。停用后只是建品时挑不到。
     */
    SpecDimVO archiveMerchantDim(String merchantNo, String dimNo, boolean archived);

    /**
     * @param usedCount 用在几件商品上 —— 停用前要知道自己在动多大范围
     * @param dimQuota  维度配额（已用 / 上限）。**摆出来而不是等他建到第 11 个才被拒**
     */
    record MerchantDimVO(String dimNo, String name, int valueCount, int usedCount,
                         String status, int dimUsed, int dimQuota, int valueQuota,
                         List<SpecValueVO> values) {
    }

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
