package ai.neargo.shop.common;

import java.util.Set;

/**
 * 经营范围值域（ADR-009 三档）。<b>范围管「能不能卖给你」，履约管「怎么送到你手上」</b> ——
 * 一家全市范围的家政能上门到你家，但它进不了你社区的自提点。
 *
 * <p><b>为什么要有这个类</b>：这三个值此前在 Java 侧只以字符串字面量存在
 * （{@code MerchantStoreServiceImpl} 里一个私有常量、{@code OpsServiceImpl} 里一个裸字面量），
 * 而两个写入口都是「为空给默认、非空原样存」—— 也就是<b>传什么存什么</b>。
 * 传 {@code "ABC"} 能写进库，之后按范围查商品会静默漏掉这家店：
 * 商家看到的是保存成功、商品在架、订单为零，没有任何报错。
 *
 * <p>真源是 shared 的 {@code SERVICE_SCOPE}（见 docs/requirements/项目词典.md，
 * 那一页是<b>规定</b>不是记录）。这里是 Java 侧的对照副本，改动要两边一起改。
 *
 * <p><b>值域 ≠ 启用</b>：这个类回答「是不是合法取值」，是代码的事实；
 * 「这一期开放哪几档」是运营的决定，存在 {@code sys_setting} 的
 * {@code merchant.service-scope-enabled} 里，见 {@code MasterDataService#assertServiceScopeAllowed}。
 * 合成一件事的话，运营在后台放开某一档时会顺手获得「写入任意字符串」的能力。
 */
public final class ServiceScopes {

    /** 仅指定社区 —— 楼下的菜摊、理发店。靠自提点履约，出了这几个小区就送不到 */
    public static final String COMMUNITY = "COMMUNITY";

    /** 全市 —— 家政、维修这类上门服务，或有同城配送能力的商家 */
    public static final String CITY = "CITY";

    /** 全平台 —— 无履约半径的：虚拟商品、卡券、平台自营的快递品 */
    public static final String PLATFORM = "PLATFORM";

    /** 合法取值全集。**这是值域，不是「当前可选项」** —— 可选项见启用白名单 */
    public static final Set<String> ALL = Set.of(COMMUNITY, CITY, PLATFORM);

    private ServiceScopes() {
    }
}
