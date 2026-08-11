package ai.neargo.shop.config;

import ai.neargo.shop.product.entity.PrdCategory;
import ai.neargo.shop.product.entity.PrdCommunityPool;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.CategoryMapper;
import ai.neargo.shop.product.mapper.ProductMappers.CommunityPoolMapper;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.community.entity.CmtCommunity;
import ai.neargo.shop.community.entity.CmtPickupPoint;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.platform.entity.SysOpsStaff;
import ai.neargo.shop.platform.mapper.PlatformMappers.StaffMapper;
import ai.neargo.shop.community.mapper.CommunityMappers.PickupPointMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 演示种子数据，**默认关闭**（{@code shop.seed.enabled=true} 才灌）。
 *
 * <p>powerbank 那边 5 个 Seeder 一度没有任何门禁，任何空库启动都会灌进假数据 ——
 * 生产环境出现「12 个假代理商」就是这么来的。这里从第一天就上开关，且幂等（有数据即跳过）。
 *
 * <p>灌的是**能把 c-app 首页跑起来的最小集**：2 社区 / 2 自提点 / 2 商家 / 4 商品，
 * 覆盖标品与生鲜两条计价线。
 */
@Configuration
@ConditionalOnProperty(name = "shop.seed.enabled", havingValue = "true")
public class DevSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevSeeder.class);

    @Bean
    ApplicationRunner seedRunner(CommunityMapper communityMapper, PickupPointMapper pickupMapper,
                                 MchEntityMapper merchantMapper, GoodsMapper goodsMapper,
                                 SkuMapper skuMapper, CommunityPoolMapper poolMapper,
                                 CategoryMapper categoryMapper, StaffMapper staffMapper,
                                 ai.neargo.shop.merchant.mapper.MerchantMappers.SysAuthCodeMapper authCodeMapper,
                                 ai.neargo.shop.platform.mapper.PlatformMappers.SettingMapper settingMapper,
                                 ai.neargo.shop.platform.mapper.PlatformMappers.IndustryMapper industryMapper,
                                 // 平台员工是 staffMapper，商家子账号是 merchantStaffMapper —— 两套人，别混
                                 ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper merchantStaffMapper,
                                 ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper) {
        return args -> {
            if (communityMapper.selectCount(Wrappers.emptyWrapper()) > 0) {
                return;   // 幂等：重启不重复灌
            }
            /*
             * ⚠️ 这道总闸只看社区表，**兜不住「灌到一半失败」**：
             * 中途抛异常后库里留着前半截，下次启动社区仍是空的 → 守卫放行 → 前半截主键冲突。
             * 报出来的是一个跟真实原因毫无关系的错（上一次是「E2E 种子重放失败」）。
             * 所以下面每个 seedXxx 各自再判一次存在性 —— 总闸只是省掉正常路径的那几次查询。
             */
            log.warn("[DEV-ONLY] seeding demo data (shop.seed.enabled=true) —— 生产环境不应看到这条日志");

            // 运营账号：**按岗位分角色**，没有「运营」这种什么都能干的大角色 ——
            // 一个能审商家又能改价又能退款的角色，出事时无法定位是谁的职责
            seedStaff(staffMapper, "admin", "超级管理员", "[\"SUPER_ADMIN\"]", "admin123");
            seedStaff(staffMapper, "bd", "商家运营", "[\"BD\"]", "bd123");
            seedStaff(staffMapper, "goods", "商品运营", "[\"GOODS_OPS\"]", "goods123");
            seedStaff(staffMapper, "support", "客服", "[\"SUPPORT\"]", "support123");

            /*
             * 一期的行业收敛（V22）。
             *
             * **为什么这里要重来一遍**：行业的种子行来自 V2，而 H2 侧那批种子是
             * schema-test.sql 带进来的（由 gen-test-schema.py 从迁移重放生成）——
             * 那个生成器把 UPDATE 一律当成「作用于生产存量数据」跳过，
             * 于是 V22 的停用在 H2 上没有发生：真库只剩两个行业可选，H2 还是七个全开。
             * 不补的话，「入驻只能选执照覆盖的行业」这条用例在测试里永远失败，
             * 而失败的样子看起来像校验没写对。
             */
            disableIndustry(industryMapper, "CATERING",
                    "一期停用：平台执照无餐饮服务与热食制售（自营模式下平台是销售者）。拿到相应许可后可放开");
            disableIndustry(industryMapper, "ENTERTAINMENT", "一期停用：平台执照无相关经营项");
            disableIndustry(industryMapper, "TRANSPORT", "一期停用：平台执照无相关经营项");
            disableIndustry(industryMapper, "ONLINE",
                    "一期停用：不上虚拟商品与卡券（iOS 小程序虚拟支付受限）");
            disableIndustry(industryMapper, "OTHER",
                    "一期停用：自营模式下「其他」等于平台不清楚自己在销售什么，无法对应执照经营范围");

            /*
             * 类目树。**编号必须与 V4__category_tree.sql 和 ops-web 的 mock 完全一致** ——
             * 这里曾经是第三套编号（CAT-ROOT-1 / CAT001…），于是真库一棵树、H2 一棵树、
             * 前端 mock 又一棵树。症状是「mock 上跑得通，连真库就找不到类目」，
             * 而三处都各自自洽，谁也不报错。
             *
             * 真库由 V4 灌，这里只服务于**不跑 Flyway 的 H2 测试**；
             * 下面的 count 守卫保证真库上不会重复插。
             */
            seedCategory(categoryMapper, "CAT100", null, 1, "食品生鲜", "FRESH", null, 10, ACTIVE);
            seedCategory(categoryMapper, "CAT110", "CAT100", 2, "蔬菜", "FRESH", null, 10, ACTIVE);
            seedCategory(categoryMapper, "CAT111", "CAT110", 3, "叶菜", "FRESH", "FRESH_VEG", 10, ACTIVE);
            seedCategory(categoryMapper, "CAT112", "CAT110", 3, "根茎菜", "FRESH", "FRESH_VEG", 20, ACTIVE);
            seedCategory(categoryMapper, "CAT120", "CAT100", 2, "水果", "FRESH", null, 20, ACTIVE);
            seedCategory(categoryMapper, "CAT121", "CAT120", 3, "浆果", "FRESH", "FRESH_FRUIT", 10, ACTIVE);
            seedCategory(categoryMapper, "CAT122", "CAT120", 3, "常温水果", "FRESH", "FRESH_FRUIT", 20, ACTIVE);
            seedCategory(categoryMapper, "CAT130", "CAT100", 2, "预包装食品", "STANDARD", null, 30, ACTIVE);
            seedCategory(categoryMapper, "CAT131", "CAT130", 3, "粮油调味", "STANDARD", "PACKAGED_FOOD", 10, ACTIVE);
            seedCategory(categoryMapper, "CAT132", "CAT130", 3, "休闲零食", "STANDARD", "PACKAGED_FOOD", 20, ACTIVE);
            seedCategory(categoryMapper, "CAT133", "CAT130", 3, "茶叶", "STANDARD", "PACKAGED_FOOD", 30, ACTIVE);
            seedCategory(categoryMapper, "CAT200", null, 1, "日用百货", "STANDARD", null, 20, ACTIVE);
            seedCategory(categoryMapper, "CAT210", "CAT200", 2, "纸品清洁", "STANDARD", null, 10, ACTIVE);
            seedCategory(categoryMapper, "CAT220", "CAT200", 2, "家居用品", "STANDARD", null, 20, ACTIVE);
            seedCategory(categoryMapper, "CAT230", "CAT200", 2, "个护化妆", "STANDARD", null, 30, ACTIVE);
            // 一级不挂 required_code（V22 修的 D2）—— 挂在这里的话，家政会被维修资质卡住
            seedCategory(categoryMapper, "CAT300", null, 1, "生活服务", "SERVICE", null, 30, ACTIVE);
            seedCategory(categoryMapper, "CAT310", "CAT300", 2, "家政保洁", "SERVICE", "HOUSEKEEPING", 10, ACTIVE);
            // 一期停用：执照无预付卡相关项
            seedCategory(categoryMapper, "CAT400", null, 1, "卡券", "VOUCHER", null, 40, ARCHIVED);

            /*
             * 类目授权码。真库由 V5 + V22 灌，这里只服务于**不跑 Flyway 的 H2 测试**。
             * 少了它，挂了 required_code 的类目在测试里永远无法被授权 ——
             * 而准入用例会「通过」，因为它测的正是拒绝那一半。
             *
             * 停用的三个（乳制品/熟食/维修）也要灌：一期收敛后它们仍在库里，
             * 只是 enabled=0。不灌的话「停用码不出现在可授权列表」这条测不出来。
             */
            seedAuthCode(authCodeMapper, "FRESH_VEG", "蔬菜", 10, true);
            seedAuthCode(authCodeMapper, "FRESH_FRUIT", "水果", 20, true);
            seedAuthCode(authCodeMapper, "PACKAGED_FOOD", "预包装食品", 25, true);
            seedAuthCode(authCodeMapper, "FRESH_DAIRY", "乳制品", 30, false);
            seedAuthCode(authCodeMapper, "FOOD", "熟食加工", 40, false);
            seedAuthCode(authCodeMapper, "DAILY", "日用百货", 50, true);
            seedAuthCode(authCodeMapper, "SERVICE_REPAIR", "维修服务", 60, false);
            seedAuthCode(authCodeMapper, "HOUSEKEEPING", "家政服务", 65, true);

            /*
             * 平台可调参数。真库由各自的迁移灌，这里只服务于**不跑 Flyway 的 H2 测试**。
             * 少了它，机审词表为空 = 全放行，而「命中转人审」那组用例会绿着通过 ——
             * 测试绿而功能没生效，是最坏的一种绿。
             */
            seedSetting(settingMapper, "store.sensitive-words",
                    "[\"最低价\",\"全网第一\",\"国家级\",\"绝对\",\"包治\",\"微信\",\"加V\",\"私聊\"]");
            seedSetting(settingMapper, "review.score-config",
                    "{\"weightProduct\":50,\"weightFulfill\":30,\"weightService\":20,"
                            + "\"newMerchantProtectDays\":30,\"decayHalfLifeDays\":180}");
            /*
             * 一期开放的经营范围（V22）。不灌的话 MasterDataService 会走「没配过 = 全开」
             * 的兜底，于是「选 PLATFORM 被拒」那条用例在 H2 上永远失败 ——
             * 而失败的原因看起来会是校验没生效，其实是种子少了一行。
             */
            seedSetting(settingMapper, "merchant.service-scope-enabled", "[\"COMMUNITY\",\"CITY\"]");

            communityMapper.insert(community("C0001", "阳光花园", "杭州市西湖区文一西路 100 号", 30280000, 120100000));
            communityMapper.insert(community("C0002", "翡翠城", "杭州市西湖区文二西路 200 号", 30285000, 120105000));

            merchantMapper.insert(merchant("M0001", "老张粮油店", "U-DEMO-1", true,
                    "开了 12 年的街边粮油店，米面油调料齐全", "[\"街坊老店\",\"当日达\"]"));
            merchantMapper.insert(merchant("M0002", "鲜果直供", "U-DEMO-2", false,
                    "产地直发，当日采摘次日到", "[\"产地直发\"]"));

            /*
             * 演示商家也要有**成员行 + 默认门店** —— V44 起 B 端身份来自
             * mch_account，而不是 mch_entity.owner_user_no。
             * 漏掉的话：库里有商家、C 端搜得到，唯独他自己登录 B 端时作用域是空的，
             * 所有 /biz/** 都 403，而看到的只是「打不开」。
             */
            for (var seed : java.util.List.of(
                    new String[]{"M0001", "U-DEMO-1", "老张粮油店"},
                    new String[]{"M0002", "U-DEMO-2", "鲜果直供"})) {
                merchantStaffMapper.insert(ownerStaff(seed[0], seed[1]));
                storeMapper.insert(defaultStore(seed[0], seed[2]));
            }

            pickupMapper.insert(pickup("PP0001", "C0001", "老张粮油店（自提点）",
                    "阳光花园东门旁", "ST-M0001", "08:00-21:00", "每晚 7 点前到货"));
            pickupMapper.insert(pickup("PP0002", "C0002", "翡翠城便利店",
                    "翡翠城 3 号楼底商", "ST-M0001", "07:00-22:00", "每晚 8 点前到货"));

            seedGoods(goodsMapper, skuMapper, poolMapper,
                    "G0001", "M0001", "NORMAL", "🍚", "五常大米 10斤装", "东北五常，当季新米",
                    List.of(new SkuSeed("SK0001", "10斤装", 4980L, 5980L, 120),
                            new SkuSeed("SK0002", "20斤装", 9580L, 11800L, 60)));
            seedGoods(goodsMapper, skuMapper, poolMapper,
                    "G0002", "M0001", "NORMAL", "🛢️", "金龙鱼调和油 5L", "家庭装，煎炒烹炸",
                    List.of(new SkuSeed("SK0003", "5L", 6980L, 7980L, 80)));
            seedGoods(goodsMapper, skuMapper, poolMapper,
                    "G0003", "M0002", "FRESH", "🍑", "阳山水蜜桃 4枚礼盒", "次日到货，坏果包赔",
                    List.of(new SkuSeed("SK0004", "4枚装", 5800L, 6800L, 40)));
            seedGoods(goodsMapper, skuMapper, poolMapper,
                    "G0004", "M0002", "FRESH", "🫐", "云南蓝莓 125g×4盒", "当季头茬",
                    List.of(new SkuSeed("SK0005", "4盒装", 3980L, null, 30)));
        };
    }

    private record SkuSeed(String skuNo, String spec, long price, Long originPrice, int stock) {
    }

    private void seedStaff(StaffMapper mapper, String username, String realName,
                           String rolesJson, String rawPassword) {
        var s = new SysOpsStaff();
        s.setStaffNo("ST-" + username.toUpperCase());
        s.setUsername(username);
        s.setPassword(ai.neargo.shop.platform.impl.OpsServiceImpl.hash(rawPassword));
        s.setRealName(realName);
        s.setRoles(rolesJson);
        s.setStatus("ACTIVE");
        if (mapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<SysOpsStaff>lambdaQuery().eq(SysOpsStaff::getUsername, username)) == 0) {
            mapper.insert(s);
        }
    }

    private void seedSetting(ai.neargo.shop.platform.mapper.PlatformMappers.SettingMapper mapper,
                             String key, String value) {
        if (mapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ai.neargo.shop.platform.entity.SysSetting>lambdaQuery()
                .eq(ai.neargo.shop.platform.entity.SysSetting::getSettingKey, key)) > 0) {
            return;
        }
        var row = new ai.neargo.shop.platform.entity.SysSetting();
        row.setSettingKey(key);
        row.setSettingValue(value);
        mapper.insert(row);
    }

    private void seedAuthCode(ai.neargo.shop.merchant.mapper.MerchantMappers.SysAuthCodeMapper mapper,
                              String code, String name, int sort, boolean enabled) {
        var c = new ai.neargo.shop.merchant.entity.SysAuthCode();
        c.setCode(code);
        c.setName(name);
        c.setRequiredQualification(AUTH_QUALIFICATION.get(code));
        c.setSort(sort);
        c.setEnabled(enabled);
        // 同上：真库由 V5 灌，这里只补 H2
        if (mapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ai.neargo.shop.merchant.entity.SysAuthCode>lambdaQuery()
                .eq(ai.neargo.shop.merchant.entity.SysAuthCode::getCode, code)) == 0) {
            mapper.insert(c);
        }
    }

    private static final String ACTIVE = "ACTIVE";
    private static final String ARCHIVED = "ARCHIVED";

    /**
     * 停用一个行业并写下理由。**停用不是删除**：行留在库里、仍对运营可见，
     * 存量商家不受影响（停用只影响新入驻的可选项）。拿到 EDI 切平台模式时
     * 在运营端逐条打开即可，不用改代码也不用再来一次迁移。
     */
    private void disableIndustry(ai.neargo.shop.platform.mapper.PlatformMappers.IndustryMapper mapper,
                                 String industry, String remark) {
        var row = mapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ai.neargo.shop.platform.entity.SysIndustry>lambdaQuery()
                .eq(ai.neargo.shop.platform.entity.SysIndustry::getIndustry, industry)
                .last("limit 1"));
        if (row == null) {
            return;   // 真库由 V22 处理；这里只补 H2，查不到就是没轮到它
        }
        row.setEnabled(false);
        // remark 是给运营看的「为什么这个行业是这个准入结论」——
        // 三个月后要放开时，看这一行就知道当初卡在哪
        row.setRemark(remark);
        mapper.updateById(row);
    }

    /**
     * 授权码 → 所需资质文案。**这是 H2 侧唯一的一份**，类目的 {@code qualification_required}
     * 由它派生。
     *
     * <p>此前 {@code seedCategory} 把「食品经营许可证」直接写死在方法体里，对所有挂码的类目
     * 一律用同一句。一期把果蔬改成初级农产品口径（V22）之后，真库是「营业执照（食用农产品）」
     * 而 H2 还是旧文案 —— 同一个类目在两个环境显示两张不同的证，而两边各自自洽、都不报错。
     *
     * <p>不在这张表里的码 = 无证件要求（{@code get} 返回 null），不是「漏填」。
     */
    private static final java.util.Map<String, String> AUTH_QUALIFICATION = java.util.Map.of(
            "FRESH_VEG", "营业执照（食用农产品）",
            "FRESH_FRUIT", "营业执照（食用农产品）",
            "PACKAGED_FOOD", "仅销售预包装食品备案",
            "FRESH_DAIRY", "食品经营许可证",
            "FOOD", "食品经营许可证",
            "SERVICE_REPAIR", "家电维修资质");

    private void seedCategory(CategoryMapper mapper, String no, String parentNo, int level,
                              String name, String template, String requiredCode, int sort,
                              String status) {
        var c = new PrdCategory();
        c.setCategoryNo(no);
        c.setParentNo(parentNo);
        c.setLevel(level);
        c.setName(name);
        c.setIcon("");
        c.setSort(sort);
        c.setTemplate(template);
        // 空 = 无门槛。挂了码的类目，商家没拿到对应授权就上不了架
        c.setRequiredCode(requiredCode);
        String qualification = requiredCode == null ? null : AUTH_QUALIFICATION.get(requiredCode);
        c.setQualificationRequired(qualification == null ? null : "[\"" + qualification + "\"]");
        c.setStatus(status);
        /*
         * **幂等**：真库由 V4 灌同一批类目，这里只补 H2。
         * 无脑 insert 的结果是真库上主键冲突 —— 而它发生在种子重放阶段，
         * 报出来的是「E2E 种子重放失败」，看不出跟类目有任何关系。
         */
        if (mapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<PrdCategory>lambdaQuery().eq(PrdCategory::getCategoryNo, no)) == 0) {
            mapper.insert(c);
        }
    }

    private CmtCommunity community(String no, String name, String address, int latE6, int lngE6) {
        var c = new CmtCommunity();
        c.setCommunityNo(no);
        c.setName(name);
        c.setAddress(address);
        c.setLatE6(latE6);
        c.setLngE6(lngE6);
        c.setStatus("OPEN");
        return c;
    }

    private CmtPickupPoint pickup(String no, String communityNo, String name, String address,
                                  String storeNo, String openHours, String arrivalDesc) {
        var p = new CmtPickupPoint();
        p.setPickupNo(no);
        p.setCommunityNo(communityNo);
        p.setName(name);
        p.setAddress(address);
        p.setType("STORE");
        p.setScope("PERMANENT");
        // STORE 类型的 owner_ref 存的是**门店号**（V16 起）
        p.setOwnerRef(storeNo);
        p.setOpenHours(openHours);
        p.setArrivalDesc(arrivalDesc);
        p.setServiceFeeRate(0);
        p.setStatus("ACTIVE");
        return p;
    }

    private ai.neargo.shop.merchant.entity.MchAccount ownerStaff(String merchantNo, String userNo) {
        var st = new ai.neargo.shop.merchant.entity.MchAccount();
        st.setMchAccountNo("SF-" + merchantNo);
        st.setEntityNo(merchantNo);
        st.setUserNo(userNo);
        st.setIsOwner(true);
        st.setIsPrimary(true);
        st.setStatus(ai.neargo.shop.merchant.entity.MchAccount.ACTIVE);
        return st;
    }

    private ai.neargo.shop.merchant.entity.MchStore defaultStore(String merchantNo, String name) {
        var s = new ai.neargo.shop.merchant.entity.MchStore();
        s.setStoreNo("ST-" + merchantNo);
        s.setEntityNo(merchantNo);
        s.setName(name);
        s.setIsDefault(true);
        s.setStatus(ai.neargo.shop.merchant.entity.MchStore.ACTIVE);
        s.setFeatured("[]");
        return s;
    }

    private MchEntity merchant(String no, String name, String ownerUserNo, boolean verified,
                                 String desc, String tags) {
        var m = new MchEntity();
        m.setEntityNo(no);
        m.setName(name);
        m.setLogo("");
        m.setLegalForm("INDIVIDUAL");
        m.setDescription(desc);
        m.setOwnerUserNo(ownerUserNo);
        m.setRating(48);
        m.setRatingCount(126);
        m.setSalesCount(1893);
        m.setGoodsCount(2);
        m.setScoreGoods(49);
        m.setScoreService(47);
        m.setScoreSpeed(48);
        m.setVerified(verified);
        m.setBreachCount(0);
        m.setTags(tags);
        m.setStoreCode(no.equals("M0001") ? "ZHANG01" : "XIANGUO");
        m.setJoinedAt(System.currentTimeMillis());
        m.setStatus("ACTIVE");
        return m;
    }

    private void seedGoods(GoodsMapper goodsMapper, SkuMapper skuMapper, CommunityPoolMapper poolMapper,
                           String goodsNo, String merchantNo, String type, String cover,
                           String title, String subtitle,
                           List<SkuSeed> skus) {
        var g = new PrdGoods();
        g.setGoodsNo(goodsNo);
        g.setEntityNo(merchantNo);
        g.setTitle(title);
        g.setSubtitle(subtitle);
        // 演示封面用 emoji：真实环境这里是图片 URL，但本地联调要的是"一眼能分辨"，
        // 而不是四条一模一样的占位。空字符串在端上会走 GOODS_COVER_FALLBACK（🛒），
        // 满屏同一个购物车比没有图还难看
        g.setCover(cover);
        g.setImages("[]");
        g.setType(type);
        g.setCategoryNo("CAT210");   // 纸品清洁：无资质门槛，演示商品不该卡在准入上
        g.setFulfillments("[\"STORE_PICKUP\"]");
        g.setSpecGroups("[{\"name\":\"规格\",\"options\":" + optionsJson(skus) + "}]");
        g.setRating(48);
        g.setRatingCount(32);
        g.setSales(240);
        g.setLimitPerUser(0);
        g.setOnSale(true);
        g.setAuditStatus("APPROVED");
        goodsMapper.insert(g);

        for (SkuSeed s : skus) {
            var sku = new PrdSku();
            sku.setSkuNo(s.skuNo());
            sku.setGoodsNo(goodsNo);
            sku.setEntityNo(merchantNo);
            sku.setMarket("CN");
            sku.setOptionValues("[\"" + s.spec() + "\"]");
            sku.setSpec(s.spec());
            sku.setPrice(s.price());
            sku.setOriginPrice(s.originPrice());
            sku.setStock(s.stock());
            sku.setLockedStock(0);
            skuMapper.insert(sku);
        }

        for (String communityNo : List.of("C0001", "C0002")) {
            var pool = new PrdCommunityPool();
            pool.setCommunityNo(communityNo);
            pool.setGoodsNo(goodsNo);
            pool.setEntityNo(merchantNo);
            pool.setSortWeight(0);
            poolMapper.insert(pool);
        }
    }

    private String optionsJson(List<SkuSeed> skus) {
        return skus.stream().map(s -> "\"" + s.spec() + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
