package ai.neargo.shop.platform;

/**
 * 平台可调参数的读写。
 *
 * <p>只做「存住 + 留痕」，**不认识任何一组参数的语义** ——
 * 校验（三维权重之和必须为 100、退款阈值不能为负）留在各自的 Service 里。
 * 把领域校验塞进这一层，等于每加一组参数就要改一次基础设施。
 */
public interface SettingService {

    /**
     * @param defaultJson 没配过时返回它 —— 参数表少一行不该让整个页面打不开
     */
    String get(String key, String defaultJson);

    /** 写入并留痕（谁在什么时候改的）。改参数会改变历史数据的呈现，所以留痕不是可选项。 */
    void put(String key, String json, String operatorNo);
}
