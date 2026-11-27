package ai.neargo.shop.spi.dict;

import java.util.List;

/**
 * 一种字典类型的数据来源，由 {@link DictionaryPort} 的唯一实现按类型汇集。
 *
 * <p>为什么有这一层：字典在设计里是基础能力库的一张 {@code sys_dict_item}，但现在三种类型的旧表分在三个模块，
 * 支付渠道那张还在支付的独立数据源上，一个实现够不着全部。每张旧表由它现在的属主出一个来源；
 * 基础能力库落地后换成一个读 {@code sys_dict_item} 的来源，旧来源删掉，{@link DictionaryPort} 的调用方不动。
 *
 * <p>只给端口实现用，业务代码不直接注入它。
 */
public interface DictionarySource {

    /** 这个来源负责的类型；同一类型只能有一个来源 */
    DictType type();

    /** 该类型全部条目（含停用），不分租户；顺序即展示顺序（经营类型按行业码、排序） */
    List<DictItem> items();
}
