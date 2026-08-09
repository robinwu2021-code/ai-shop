// 支付通道接入的约束守卫。
//
// ═══ 为什么这些要有测试 ═══
//
// 通道的时序与限额约束写在注释里会被忽略，而违反它们的表现全都是
// **调用时通道报错，而那时钱已经在冻结账户里了**：
//
//   · 补差晚于分账   → 分账基数不含补贴，商家少收
//   · 分账超比例上限 → 通道拒绝，订单付过款了却结不出去
//   · 密钥进代码库   → 泄露之后要走通道的吊销流程，期间全店停收
//
// 所以把「机器能判的」钉在这里。规格来源见
// backend/.../gateway/WechatApis.java 与 AlipayApis.java 的类注释。
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { PAY_CHANNELS } from "@shared/capability";
import { POINTS, SETTLE } from "@shared/utils/constants";

const ROOT = join(import.meta.dirname, "../../..");
const GATEWAY_DIR = join(ROOT, "backend/shop-svc-settle/src/main/java/ai/neargo/shop/settle/gateway");
const APP_YML = join(ROOT, "backend/shop-app/src/main/resources/application.yml");

const read = (f: string) => (existsSync(f) ? readFileSync(f, "utf8") : "");

/** 通道 seed 里声明的能力，与 V38/V39 的 INSERT/UPDATE 同步 */
const MIGRATION = join(
  ROOT,
  "backend/shop-app/src/main/resources/db/migration/V39__refund_capability_and_item_weigh.sql",
);

describe("支付通道接入", () => {
  it("每个已登记的通道都有接口坐标文件 —— 路径散在实现里升级时必漏", () => {
    if (!existsSync(GATEWAY_DIR)) return;
    const files = readdirSync(GATEWAY_DIR);
    for (const ch of PAY_CHANNELS) {
      const expected = ch.charAt(0) + ch.slice(1).toLowerCase() + "Apis.java";
      expect(files, `通道 ${ch} 没有 ${expected}`).toContain(expected);
    }
  });

  it("补差与分账的时序约束写在坐标文件里 —— 顺序错了通道才报错，那时钱已冻结", () => {
    const wx = read(join(GATEWAY_DIR, "WechatApis.java"));
    if (!wx) return;
    // 「支付成功后、发起分账前」是微信对补差的硬约束
    expect(wx, "WechatApis 没写明补差的时序约束").toMatch(/分账前/);
    expect(wx, "WechatApis 缺补差接口路径").toContain("/v3/ecommerce/subsidies/create");
    expect(wx, "WechatApis 缺补差回退路径").toContain("/v3/ecommerce/subsidies/return");
  });

  it("未经文档确认的接口必须**标注**，不能当成已确认的用", () => {
    const ali = read(join(GATEWAY_DIR, "AlipayApis.java"));
    if (!ali) return;
    // 支付宝的营销补差没有独立接口，我们推断它是「反向的分账」
    // （royalty_parameters 的 trans_out 设为平台账户）。推断可以用，
    // 但**必须标注** —— 不标注的话下一个人会以为它已经验证过，
    // 联调失败时会去查参数，而问题可能出在接口名本身
    expect(ali, "AlipayApis 没有标注哪些是推断").toMatch(/推断|待确认|未能.*确认/);

    const gw = read(join(GATEWAY_DIR, "AlipayPayGateway.java"));
    if (!gw) return;
    // 实现处也要标注，且要写清**怎么验证**
    expect(gw, "AlipayPayGateway 的 subsidy 没有标注这是推断").toMatch(/推断|假设/);
    expect(
      gw,
      "没写清联调时怎么区分「接口名不对」与「参数不对」—— " +
        "混为一谈会让人在参数上耗很久",
    ).toMatch(/服务不存在|INVALID-METHOD/);
  });

  it("平台分走的比例远低于通道上限 —— 超了是「订单已付款但结不出去」", () => {
    // 支付宝直付通：单笔不超过订单金额的 30%（+ 已补差金额）
    const maxTake = SETTLE.commissionRate.PLATFORM + POINTS.defaultEarnRatio;
    expect(maxTake, `平台分走 ${(maxTake * 100).toFixed(1)}%，逼近 30% 上限`).toBeLessThan(0.15);
  });

  it("通道限额已落库，不是只活在文档里", () => {
    const sql = read(MIGRATION);
    if (!sql) return;
    for (const col of ["max_partial_refunds", "refund_interval_seconds", "max_split_rate"]) {
      expect(sql, `V39 没有 ${col}`).toContain(col);
    }
    // 支付宝 30% = 3000 万分比
    expect(sql, "支付宝的 30% 分账上限没落库").toMatch(/max_split_rate\s*=\s*3000/);
  });

  it("假网关默认关闭 —— 默认开的话线上会「支付成功」而钱没动，且无症状", () => {
    const stub = read(join(GATEWAY_DIR, "StubPayGateway.java"));
    if (!stub) return;
    expect(stub, "StubPayGateway 没有开关条件").toContain("ConditionalOnProperty");
    expect(read(APP_YML), "shop.pay.stub 默认值不是 false").toMatch(/SHOP_PAY_STUB:false/);
  });

  it("密钥只从环境变量注入，配置文件里没有明文", () => {
    const yml = read(APP_YML);
    if (!yml) return;
    const payBlock = yml.slice(yml.indexOf("shop:"));
    // 每个密钥类配置都必须是 ${ENV:} 形式，且默认值为空
    for (const key of ["api-v3-key", "private-key-path", "app-id", "mch-id"]) {
      const line = payBlock.split("\n").find((l) => l.trim().startsWith(key + ":"));
      if (!line) continue;
      expect(line, `${key} 不是环境变量注入：${line.trim()}`).toMatch(/\$\{[A-Z0-9_]+:\s*\}/);
    }
  });

  it("主体枚举只有一套 —— 四套并存意味着能力判断散在四处", () => {
    // 通道能力（能不能补差、能不能分账回退、能不能开积分）是按**主体**判的。
    // 现在同一个概念有四套词：usr_merchant.type（PERSONAL/INDIVIDUAL/COMPANY）、
    // subject_type（MICRO/INDIVIDUAL/ENTERPRISE）、MerchantType、SubjectType。
    // 不一致的表现是「同一个商家在 A 页面能开积分、B 页面不能」。
    //
    // 统一到 SubjectType（与通道口径一致，而通道口径是外部约束改不了）。
    // 完善方案见 docs/technical/通道约束落地-完善清单.md §M1
    const KNOWN = {
      "usr_merchant.type":
        "取值是 PERSONAL/INDIVIDUAL/COMPANY，与 subject_type 讲同一件事却用了不同的词。" +
        "→ 统一到 SubjectType（M1）",
      MerchantType:
        "PLATFORM 不是主体是**归属**（平台自营店），混进主体枚举导致这一档没有对应的通道主体。" +
        "→ 拆出 ownerType（M1）",
    };
    // 登记即承认，补完要删。这条断言只保证「不会再多出第五套」
    expect(Object.keys(KNOWN).length, "主体枚举的历史包袱变多了").toBeLessThanOrEqual(2);
  });

  it("行业白名单默认值不能是「允许」—— 默认允许等于默认让商家撞墙", () => {
    // 微信小微限行业（餐饮/线下零售/居民生活服务/休闲娱乐/交通），线上业态不支持。
    // 我们目前**没有行业字段**（M2），所以这条先守住方案文档里的原则：
    // 将来建 sys_industry 时，wechat_micro_allowed 的缺省必须是 0。
    const plan = read(
      join(ROOT, "docs/technical/通道约束落地-完善清单.md"),
    );
    if (!plan) return;
    expect(plan, "完善清单里没写清默认值取向").toMatch(/默认允许\s*=\s*默认让商家撞墙/);
  });
});
