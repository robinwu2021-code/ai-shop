package ai.neargo.shop.product.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.SpuStdVO;

import java.util.List;

/**
 * 标准品库（TDD-标准品库）。平台维护的标准商品，商家引用建品。
 *
 * <p>与 {@link CategoryService} 同类：<b>跨商家共享的主数据</b>，不挂任何商家，
 * 因此不注册数据域 —— 按商家维度过滤它只会让所有人都搜不到。
 */
public interface SpuStdService {

    /**
     * 商家侧搜索（{@code GET /biz/spu-std}）。按标题与别名模糊匹配，只返回启用中的。
     *
     * <p><b>搜不到必须能顺畅地转去自建品</b>：标准库对「张姐家的酱菜」永远无效，
     * 而那类货正是这个平台的一部分主力。不能因为多了标准品，
     * 自建品反而变成一个要先失败一次才能走到的分支。
     */
    List<SpuStdVO> search(String keyword, String categoryNo, int limit);

    /** 运营端列表。{@code showArchived=false} 时不返回已归档的。 */
    PageData<SpuStdVO> list(String keyword, String categoryNo, boolean showArchived,
                            long page, long size);

    /** 按编号取；查无此项返回 {@code null}（调用方决定是拒还是忽略）。 */
    SpuStdVO find(String stdNo);

    /**
     * 新建或更新。{@code stdNo} 为空即新建。
     *
     * <p><b>每个规格选项必须带 code</b> —— 与平台规格模板同一条校验。
     * 不带 code 的标准品与商家手输没有区别，它唯一的作用是让人以为规格统一了。
     */
    SpuStdVO save(SaveCommand cmd);

    /** 归档。<b>不影响已引用的商品</b> —— std_no 是溯源不是外键。 */
    SpuStdVO archive(String stdNo);

    /** 取消归档。 */
    SpuStdVO unarchive(String stdNo);

    /**
     * @param categoryNo 必填：形态由它派生，商家取用时不可改
     * @param keywords   别名/品牌/俗称，空格分隔。一期按名称搜索，不做条码
     */
    record SaveCommand(String stdNo, String categoryNo, String title,
                       java.util.Map<String, String> titleI18n, String subtitle,
                       String cover, List<String> images,
                       List<MerchantGoodsService.SpecGroup> specGroups, String keywords) {
    }
}
