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
}
