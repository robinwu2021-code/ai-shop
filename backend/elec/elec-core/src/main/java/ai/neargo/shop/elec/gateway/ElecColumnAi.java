package ai.neargo.shop.elec.gateway;

import java.util.List;
import java.util.Map;

/**
 * 大模型认列：给表的前几行，回「表头在哪一行、哪一列是什么字段」。
 *
 * <p>元器件域只知道「问一句、拿一个猜测」，不知道对面是什么模型 —— 实现在 elec-svc（cdw 上的 qwen）。
 * <b>它只是猜测</b>：调用方（{@code ColumnResolver}）按列内容校验，不过关的字段丢掉。
 * <b>失败一律返回 null</b>，不抛：认列失败走手工指定，不让上传跟着失败。
 */
public interface ElecColumnAi {

    /** 关着或没配地址时为 false，调用方直接跳过这一级 */
    boolean isEnabled();

    /**
     * @param head   表的前几行（原样，已截断）：第一行不一定是表头
     * @param fields 可以认的字段：码 + 一句说明
     * @return null = 没给出 / 不可用 / 超时
     */
    Guess guess(List<List<String>> head, List<FieldSpec> fields);

    /** @param desc 给模型看的说明，带一两个例子（「料号 / 型号，如 STM32F103C8T6」） */
    record FieldSpec(String code, String desc) {
    }

    /**
     * @param headerRow 表头在第几行（从 0 起）
     * @param columns   字段码 → 列序号（从 0 起）
     */
    record Guess(int headerRow, Map<String, Integer> columns) {
    }
}
