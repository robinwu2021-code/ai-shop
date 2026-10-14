package ai.neargo.shop.payclient;

import java.util.List;

/**
 * 平台端 · 银行流水导入（TDD-供应商结算与双轨资金 §10）。
 *
 * <p>它补的是出款对账的 <b>B 侧数据源</b>。在它之前，{@code PayoutReconAxis}
 * 的 B 侧比对是一块看起来在工作的死代码：一条流水都没有，
 * 于是每一笔已付款都算 {@code deferred}、差异恒为 0、闸门全绿。
 *
 * <p>一期人工上传网银导出的 CSV，不接银企直连 —— 直连要银行侧开通与联调，
 * 而没有它这条轴就一直是半条。先用人工的把闭环走通，
 * <b>直连接上时换的是取数那一段，解析与比对一行不动</b>。
 */
public interface OpsBankFlowAppService {

    /**
     * 导入一份流水。
     *
     * <p><b>逐行判，不是一行坏就整份拒绝</b>：网银导出常带页脚合计行、空行和
     * 一两条格式异样的记录，整份拒绝会把财务推进「删一行传一次」的循环，
     * 而他们手里是一份不该被编辑的原始凭据。
     *
     * <p><b>重复上传是常态，不是错误</b>：同一份流水覆盖一整周、每天传一次是
     * 合理操作。已存在的流水号计入 {@code skipped} 并照常返回 200。
     *
     * @param fileName 原始文件名，只用于审计留痕
     * @param csv      文件内容（已解码成文本）
     */
    ImportResultVO importCsv(String fileName, String csv);

    /**
     * @param total    文件里认出来的行数（= imported + skipped + failed）
     * @param imported 真正入库的
     * @param skipped  流水号已存在、跳过的
     * @param failures 没解析成功的行；{@code line} 是原始文件里的行号，
     *                 财务要对着原文件看，所以不能给「第几条记录」
     */
    record ImportResultVO(int total, int imported, int skipped, int failed,
                          List<FailureVO> failures) {
    }

    record FailureVO(int line, String reason) {
    }
}
