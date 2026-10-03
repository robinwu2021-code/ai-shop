package ai.neargo.shop.fulfillment.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.ExpressCompanies;
import ai.neargo.shop.fulfillment.dto.FreightTemplateVO;
import ai.neargo.shop.fulfillment.entity.FulFreightTemplate;
import ai.neargo.shop.fulfillment.service.FreightDraftService;
import ai.neargo.shop.spi.fulfillment.ExpressPickupPort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FreightDraftServiceImpl implements FreightDraftService {

    /**
     * 31 个省级行政区：简称（写进模板的地区名，按收货地址**开头**匹配）→ 查价用的目的地（省会）。
     * 简称要能做收货地址的前缀：「内蒙古」匹配「内蒙古自治区…」，「新疆」匹配「新疆维吾尔自治区…」。
     */
    static final Map<String, String> PROVINCES = provinces();

    private static Map<String, String> provinces() {
        Map<String, String> m = new LinkedHashMap<>();
        String[][] rows = {
                {"北京", "北京市北京市东城区"}, {"天津", "天津市天津市和平区"}, {"河北", "河北省石家庄市长安区"},
                {"山西", "山西省太原市小店区"}, {"内蒙古", "内蒙古自治区呼和浩特市新城区"}, {"辽宁", "辽宁省沈阳市和平区"},
                {"吉林", "吉林省长春市朝阳区"}, {"黑龙江", "黑龙江省哈尔滨市南岗区"}, {"上海", "上海市上海市黄浦区"},
                {"江苏", "江苏省南京市玄武区"}, {"浙江", "浙江省杭州市西湖区"}, {"安徽", "安徽省合肥市庐阳区"},
                {"福建", "福建省福州市鼓楼区"}, {"江西", "江西省南昌市东湖区"}, {"山东", "山东省济南市历下区"},
                {"河南", "河南省郑州市金水区"}, {"湖北", "湖北省武汉市武昌区"}, {"湖南", "湖南省长沙市芙蓉区"},
                {"广东", "广东省广州市天河区"}, {"广西", "广西壮族自治区南宁市青秀区"}, {"海南", "海南省海口市美兰区"},
                {"重庆", "重庆市重庆市渝中区"}, {"四川", "四川省成都市锦江区"}, {"贵州", "贵州省贵阳市南明区"},
                {"云南", "云南省昆明市五华区"}, {"西藏", "西藏自治区拉萨市城关区"}, {"陕西", "陕西省西安市雁塔区"},
                {"甘肃", "甘肃省兰州市城关区"}, {"青海", "青海省西宁市城中区"}, {"宁夏", "宁夏回族自治区银川市兴庆区"},
                {"新疆", "新疆维吾尔自治区乌鲁木齐市天山区"},
        };
        for (String[] r : rows) {
            m.put(r[0], r[1]);
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    private final ExpressPickupPort port;

    public FreightDraftServiceImpl(ExpressPickupPort port) {
        this.port = port;
    }

    @Override
    public Draft draft(String origin, String carrier, int firstWeightGram, int addWeightGram) {
        if (origin == null || origin.isBlank() || carrier == null || firstWeightGram < 100 || addWeightGram <= 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (!port.enabled()) {
            throw BizException.of(ErrorCode.EXPRESS_CHANNEL_OFF);
        }
        /*
         * 31 省 × 2 个重量 = 62 次查价，并发（虚拟线程）。**走正式环境**：模板是给买家收钱用的，
         * 测试环境的价是假的 —— 不管快递测试模式开没开都一样。
         */
        List<ProvincePrice> rows;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, CompletableFuture<ProvincePrice>> fs = new LinkedHashMap<>();
            PROVINCES.forEach((region, dest) -> fs.put(region, CompletableFuture.supplyAsync(() -> {
                Optional<ExpressPickupPort.Quote> q1 = port.quote(carrier, origin, dest, firstWeightGram, false);
                Optional<ExpressPickupPort.Quote> q2 = port.quote(carrier, origin, dest,
                        firstWeightGram + addWeightGram, false);
                Long first = q1.map(ExpressPickupPort.Quote::priceMinor).orElse(null);
                Long add = first == null || q2.isEmpty() ? null : Math.max(0L, q2.get().priceMinor() - first);
                return new ProvincePrice(region, first, add);
            }, pool)));
            rows = fs.values().stream().map(CompletableFuture::join).toList();
        }
        return build(origin, carrier, firstWeightGram, addWeightGram, rows);
    }

    /**
     * 由各省报价推出模板（纯函数，单测直接喂报价）。
     *
     * <p>首重 / 续重取**多数省份的那一档**（众数，同票取低）：快递的价目本来就是「大部分地区一个价、
     * 偏远几档加价」。比它贵的省份写成地区加收 = 首重差价 + 一个续重单位的差价 ——
     * 模板只能表达「固定加收」，按一个续重单位近似，写在草稿里让运营核对。
     * 查不到价的省份标「不配送」：宁可不收这单，也不要收一个编出来的运费。
     */
    static Draft build(String origin, String carrier, int firstWeightGram, int addWeightGram,
                       List<ProvincePrice> rows) {
        List<ProvincePrice> quoted = rows.stream().filter(r -> r.firstFee() != null).toList();
        if (quoted.isEmpty()) {
            throw BizException.of(ErrorCode.EXPRESS_PROVIDER_REJECTED, "31 个省一个价都没查到");
        }
        long baseFirst = mode(quoted.stream().map(ProvincePrice::firstFee).toList());
        long baseAdd = mode(quoted.stream().map(r -> r.addFee() == null ? 0L : r.addFee()).toList());
        List<FreightTemplateVO.OutOfRangeVO> rules = new ArrayList<>();
        for (ProvincePrice r : rows) {
            if (r.firstFee() == null) {
                rules.add(new FreightTemplateVO.OutOfRangeVO(r.region(), FulFreightTemplate.ACTION_REJECT, 0L));
                continue;
            }
            long extra = Math.max(0L, r.firstFee() - baseFirst)
                    + Math.max(0L, (r.addFee() == null ? 0L : r.addFee()) - baseAdd);
            if (extra > 0) {
                rules.add(new FreightTemplateVO.OutOfRangeVO(r.region(), FulFreightTemplate.ACTION_SURCHARGE, extra));
            }
        }
        String city = origin.replaceAll("^(.*?(省|自治区))", "").replaceAll("(市).*$", "$1");
        String name = (city.isBlank() ? origin : city) + "发 · " + ExpressCompanies.nameOf(carrier);
        return new Draft(name, firstWeightGram, baseFirst, addWeightGram, baseAdd, rules, rows,
                (int) rows.stream().filter(r -> r.firstFee() == null).count());
    }

    private static long mode(List<Long> values) {
        Map<Long, Long> counts = values.stream().filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        return counts.entrySet().stream()
                .max(Comparator.<Map.Entry<Long, Long>>comparingLong(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey).orElse(0L);
    }
}
