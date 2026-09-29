package ai.neargo.shop.elec.dto;

import java.time.LocalDateTime;

/** 运营端 · 基础数据：厂牌与别名。料号认厂牌全靠别名表，这里是它的增长口。 */
public final class MfrDtos {

    private MfrDtos() {
    }

    /**
     * @param status     ACTIVE / MERGED（被收购并入别家）
     * @param mergedInto 并入了哪家
     * @param aliasCnt   有几种写法指向它
     * @param partCnt    料号库里挂在它名下的料号数（不含已合并的）
     */
    public record MfrRow(String mfrCode, String nameEn, String nameCn, String status, String mergedInto,
                         int aliasCnt, int partCnt) {
    }

    /**
     * 加厂牌 / 改名。改名时 mfrCode 以路径为准、这里忽略。
     *
     * @param mfrCode 业务键，大写字母数字，2–32 位，如 TI / GD。<b>建了就不能改</b>：料号与别名都指着它
     * @param nameEn  英文名，必填
     * @param nameCn  中文名，选填
     */
    public record MfrReq(String mfrCode, String nameEn, String nameCn) {
    }

    /** @param source SEED 初始种子 / OPS 运营加的 */
    public record AliasRow(String aliasNorm, String mfrCode, String source, LocalDateTime createdAt,
                           String createdBy) {
    }

    /** @param alias 原样写就行（「Texas Instruments Inc.」），服务端规范化 */
    public record AliasReq(String alias) {
    }

    /**
     * 加别名的结果。<b>加别名会当场改认既有库存</b>，这两个数就是改认了多少。
     *
     * @param aliasNorm    规范化后的写法（大写、去空白标点、去 Inc./Co.,Ltd 一类后缀）
     * @param movedRows    从「厂牌不明」改认到这家的库存行数
     * @param touchedParts 受影响的料号数（改认前后两边都算，它们的买家面都重算过了）
     */
    public record AliasResult(String aliasNorm, String mfrCode, int movedRows, int touchedParts) {
    }

    /**
     * 认不出的厂牌写法。按规范化后的写法聚合 —— 大小写、空格、后缀不同的算同一个。
     *
     * @param sample      这种写法里出现最多的那个原文，给人看
     * @param rowCnt      挂着这种写法的在售库存行数
     * @param supplierCnt 几家供应商这么写
     * @param partCnt     涉及几个料号
     * @param suggestCode 建议的厂牌（与已有别名互为前缀里最长的那条）；没有把握为空
     */
    public record UnknownMfrRow(String aliasNorm, String sample, int rowCnt, int supplierCnt, int partCnt,
                                String suggestCode, String suggestName) {
    }
}
