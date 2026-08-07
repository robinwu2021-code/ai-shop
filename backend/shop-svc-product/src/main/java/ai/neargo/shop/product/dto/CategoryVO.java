package ai.neargo.shop.product.dto;

import java.util.List;

/** 类目节点（对齐 c-app 分类页）。叶子节点 {@code children} 为空列表而不是 null —— 端上少一次判空。 */
public record CategoryVO(String categoryNo,
                         String parentNo,
                         int level,
                         String name,
                         String icon,
                         int sort,
                         List<CategoryVO> children) {
}
