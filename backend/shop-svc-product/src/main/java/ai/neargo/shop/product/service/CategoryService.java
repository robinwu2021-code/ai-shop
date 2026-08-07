package ai.neargo.shop.product.service;

import ai.neargo.shop.product.dto.CategoryVO;

import java.util.List;

/** 类目树（[API 清单 §2.3]）。游客可访问。 */
public interface CategoryService {

    /** 三级树，一次返回全量 —— 类目数量有限且极少变动，分层拉取只会让分类页多两次请求。 */
    List<CategoryVO> tree();
}
