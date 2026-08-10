package ai.neargo.shop.product.service;

import ai.neargo.shop.product.dto.CategoryVO;
import ai.neargo.shop.product.dto.OpsCategoryVO;

import java.util.List;

/** 类目树（[API 清单 §2.3]）。{@link #tree()} 游客可访问，其余是平台端维护接口。 */
public interface CategoryService {

    /** 三级树，一次返回全量 —— 类目数量有限且极少变动，分层拉取只会让分类页多两次请求。 */
    List<CategoryVO> tree();

    /**
     * 经营该类目所需的经营类目编码。**空/null = 无门槛**。
     *
     * <p>只返回这一个字段而不是整个类目：调用方（上架校验）只关心门槛，
     * 返回实体会让它顺手用上别的字段，下次改类目结构就得跟着改上架逻辑。
     */
    String requiredCodeOf(String categoryNo);

    /** 平台端平铺列表。{@code showArchived=false} 时不返回已归档的。 */
    List<OpsCategoryVO> list(String keyword, String template, boolean showArchived);

    /**
     * 新建或更新。{@code categoryNo} 为空即新建。
     *
     * <p>{@code level} 由 {@code parentNo} 推出，**不采信端上传的值** ——
     * 端上算错一次，这个类目就永远挂在错误的层级上，而树渲染出来看不出异常。
     */
    OpsCategoryVO save(SaveCategoryCommand cmd);

    /** 归档（软删除）。下面还挂着商品或未归档子类目时拒绝。 */
    OpsCategoryVO archive(String categoryNo);

    /** 取消归档。父类目仍处于归档状态时拒绝 —— 否则会冒出一个挂在已删父节点下的孤儿。 */
    OpsCategoryVO unarchive(String categoryNo);

    /**
     * @param categoryNo    空 = 新建
     * @param qualifications 人读的资质名称
     * @param requiredCode   校验依据，空 = 无门槛
     */
    record SaveCategoryCommand(String categoryNo, String name, String nameEn, String parentNo,
                               String template, List<String> qualifications, String requiredCode,
                               String icon, Integer sort) {
    }
}
