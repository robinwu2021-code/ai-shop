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

    /**
     * 这个类目对应的**五品类**（{@code NORMAL/FRESH/SERVICE/VIRTUAL/CARD}）。
     * 类目为空、查无此类目、或类目的 {@code template} 认不出时返回 {@code null}。
     *
     * <p><b>品类是类目带出来的，不是商家填的。</b> 两者不是重复：类目是数据
     * （几百条，运营后台点几下就能加），品类是代码分支（恒定五条，加一个要改履约代码
     * 并发版）。但它们此前是两个输入点 —— 商家在建品页把同一件事填两遍，而且
     * <b>允许矛盾</b>：选「叶菜」类目配 NORMAL 品类，没有一处会拦，直到下单时
     * 因为履约方式不对才出问题（生鲜要截单、服务不发货）。
     *
     * <p>让它从类目派生之后，矛盾在结构上不可能发生 —— 而不是靠一句「只提示不阻断」。
     *
     * @see #TEMPLATE_TO_TYPE 两套码的对照表（历史遗留，命名收敛是单独一轮）
     */
    String categoryTypeOf(String categoryNo);

    /**
     * 这个类目还在不在架上（{@code status = ACTIVE}）。
     *
     * <p>归档的类目<b>不能被新商品选中</b>，但<b>已经在里面的商品照旧能保存</b> ——
     * 否则运营归档一个类目，会把底下所有商品一起变成改不动的死数据，
     * 商家连「把它挪到别的类目」这个自救动作都做不了。
     */
    boolean isActive(String categoryNo);

    /**
     * 类目模板 → 五品类。<b>两套码指同一件事，只是不同名</b>：
     * {@code prd_category.template} 用 STANDARD/VOUCHER，{@code prd_goods.type} 用 NORMAL/CARD。
     *
     * <p>与端上的 {@code TEMPLATE_TO_TYPE}（packages/shared）逐条对应 ——
     * 两边不一致的话，商家在端上看到的形态与库里存的不是一个东西。
     *
     * <p><b>VOUCHER → CARD 而不是 VIRTUAL</b>：卡券要到店核销（STORE_VERIFY），
     * 虚拟商品是即时发放（INSTANT），两者履约方式不同。
     */
    java.util.Map<String, String> TEMPLATE_TO_TYPE = java.util.Map.of(
            "STANDARD", "NORMAL",
            "FRESH", "FRESH",
            "SERVICE", "SERVICE",
            "VOUCHER", "CARD",
            "VIRTUAL", "VIRTUAL");

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
     * 停用前的影响面。端上拿它渲染确认框里的那句话。
     *
     * @param goodsCount    这一类下面的商品总数
     * @param onSaleCount   其中在售的
     * @param activeChildren 还开着的子类目数 —— 大于 0 时后端会拒（孤儿节点）
     */
    ArchiveImpact archiveImpact(String categoryNo);

    record ArchiveImpact(int goodsCount, int onSaleCount, int activeChildren) {
    }

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
