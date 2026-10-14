package ai.neargo.shop.product.dto;

import java.util.List;
import java.util.Map;

/**
 * 标准品。<b>没有价、没有库存、没有履约</b> —— 商家取用后自己填那些。
 *
 * @param categoryNo  所属类目。商家取用时**不可改**（服务端覆盖）：改了形态就变了
 * @param specGroups  规格组，每个选项都带 `optionCode` —— 跨店可比靠的就是它
 * @param keywords    搜索用别名，端上可以不展示
 * @param refCount    被引用次数。只给运营排序用，不参与任何判断
 * @param barcode     商品条码。**空是常态** —— 生鲜、现做熟食、服务本来就没有条码
 * @param source      出处：`OPS` 运营手录 / `OFF` 从 Open Food Facts 导入。
 *                    运营靠它把「众包来的、还没人看过的」与「自己录的」分开审
 */
public record SpuStdVO(String stdNo,
                       String categoryNo,
                       String categoryName,
                       String title,
                       Map<String, String> titleI18n,
                       String subtitle,
                       String cover,
                       List<String> images,
                       List<GoodsVO.SpecGroupVO> specGroups,
                       String keywords,
                       String status,
                       int refCount,
                       String barcode,
                       String source) {
}
