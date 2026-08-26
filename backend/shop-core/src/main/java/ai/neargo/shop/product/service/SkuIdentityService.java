package ai.neargo.shop.product.service;

import java.util.List;

/**
 * SKU 外部身份（条码 / 货号 / 单位）的批量导入导出（P4）。
 *
 * <p><b>为什么要批量：</b>这三列（V252）是接 ERP、收银秤、供应商的唯一凭据，
 * 而它们目前只能在建品页里一件一件填。五件货可以，两百件不行 ——
 * 「做完才谈得上接 ERP」说的就是这一步。
 *
 * <p><b>为什么单独一个服务：</b>{@code MerchantGoodsServiceImpl} 已经背着商品、SKU、
 * 库存、门店价四件事。批量写入的失败模式与单条编辑完全不同（一次写几百行、
 * 一行错要不要全回滚、匹配不上怎么办），混进去会让「改一件商品」跟着变危险。
 *
 * <h3>一条必须写在接口上的口径差异</h3>
 *
 * 单条编辑里 {@code null = 不改、空串 = 清空}（见 MerchantGoodsServiceImpl#blankToNull）——
 * 那是对的，因为商家确实把那个输入框清空了。
 *
 * <p><b>CSV 里的空格子不是这个意思。</b>他从 ERP 导出的表格，没填的列天生就是空的；
 * 按「空 = 清空」处理，一次导入就能把全店的条码抹平，而且每一行都「成功」。
 * 所以这里：
 *
 * <ul>
 *   <li><b>空格子 = 不改</b></li>
 *   <li><b>要清空得显式写 {@code -}</b></li>
 *   <li><b>整列不在表头里 = 这一列一个字都不碰</b>（他删掉了那一列，就是不想动它）</li>
 * </ul>
 */
public interface SkuIdentityService {

    /** 导出时的表头，也是导入时认得的列名。顺序可变、列可缺，靠名字对上 */
    String COL_SKU_NO = "skuNo";
    String COL_GOODS = "商品";
    String COL_SPEC = "规格";
    String COL_BARCODE = "条码";
    String COL_CODE = "货号";
    String COL_UNIT = "单位";

    /** 显式清空的标记。空格子是「不改」，所以「清空」需要一个说得出口的写法 */
    String CLEAR = "-";

    /**
     * 导出本店全部 SKU 的身份三列。
     *
     * <p>第一列是 {@code skuNo} —— 导入时按它认行。文件里会写一句「别删这一列」，
     * 但真删了也有退路：见 {@link #plan} 的货号回退。
     *
     * @return 带 UTF-8 BOM 的 CSV 文本（不带 BOM 的话 Excel 会把中文列名读成乱码）
     */
    String exportCsv(String merchantNo);

    /**
     * <b>试算，不写库。</b>报出这份表会改动什么、哪几行有问题。
     *
     * <p>与「给会员发消息」同一条规矩：批量动作必须先算后做。
     * 只回一个「导入成功」，商家会以为每一行都生效了 —— 而匹配不上的那些
     * 恰恰是最需要他知道的。
     */
    ImportReport plan(String merchantNo, String csv);

    /**
     * 真写。**只写没有问题的那些行**，有问题的照旧报出来。
     *
     * <p>不做「一行错全部回滚」：两百行里有三行货号写重了，
     * 让另外一百九十七行也白跑一趟，他得把整个文件重新对一遍。
     */
    ImportReport apply(String merchantNo, String csv);

    /**
     * @param total    数据行数（不含表头）
     * @param willSet  会真正写下去的行数
     * @param noChange 匹配上了但三列都没变化的行数
     * @param problems 有问题的行，逐行给出行号与原因
     * @param samples  前几行的改动预览，让他确认「改的是不是我想的那些」
     */
    record ImportReport(int total, int willSet, int noChange,
                        List<Problem> problems, List<Change> samples) {}

    /**
     * @param line 文件里的行号（从 1 起、含表头），报给商家的必须是他在 Excel 里看得到的那个数
     */
    record Problem(int line, String reason) {}

    /** 一行的前后对照。{@code null} 表示这一列这次不动 */
    record Change(String skuNo, String goods, String spec,
                  String barcodeFrom, String barcodeTo,
                  String codeFrom, String codeTo,
                  String unitFrom, String unitTo) {}
}
