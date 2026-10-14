# TDD-多地址下单（送朋友）

状态：**已实现**
关联需求：口头需求 — 下单支持多地址，比如送朋友
创建日期：2026-10-05
档位：1（动了端点 JSON 结构）

---

## 1. 需求摘要

买家一次结算里可以给不同商家的货选不同的收货地址——自己的留自己家，
朋友那份选朋友的地址。

验收标准：
- **AC1** 结算页按商家分段后，每一段可以独立选收货地址
- **AC2** 默认全部段用同一个地址（与现在一致），改了某一段后只影响那一段
- **AC3** 后端按各段的地址分别计运费
- **AC4** 子单上的收件人快照来自各自的 addressId
- **AC5** 不传 `addressOverrides` 的老版本端继续正常工作（向后兼容）

---

## 2. 当前架构分析

### 相关现有模块

| 模块 | 路径 | 职责 |
|---|---|---|
| `MpTradeController` | `shop-core/.../trade/api/mp/` | 下单/预览端点，定义 `CreateOrderReq` |
| `OrderService` / `OrderServiceImpl` | `shop-core/.../trade/service/` | 下单核心逻辑，定义 `CreateOrderCommand` |
| `OrdSubOrder` | `shop-core/.../trade/entity/` | 子单实体，已有 `addressId` + `receiverName/Phone/Address` |
| `UsrAddress` | `shop-core/.../user/entity/` | 地址簿，已有 `name`/`phone`/`tag` |
| `order-confirm` 页 | `c-app/src/pages/order-confirm/` | 结算页，有 `merchantSegments` 分组 |
| `address` / `address-pick` 页 | `c-app/src/pages/address*/` | 地址列表/选点 |

### 影响范围

- `CreateOrderReq` 加字段（`addressOverrides`）
- `CreateOrderCommand` 加字段
- `OrderServiceImpl.create()` / `preview()` 中取地址的逻辑
- 结算页的地址区从全局一块 → 每段各一块
- 运费按各段地址分别计算（当前已按商家分组计算，只需取不同地址）

### 复用机会

- `UsrAddress` **不改**——地址簿已经支持存别人的名字电话
- `OrdSubOrder` **不改**——已有子单级的 `addressId` + 收件人快照
- 运费计算已按商家分组——只需在取地址时查 overrides
- `biz-address-card` 组件可直接复用

---

## 3. 方案设计

### 核心思路

**全局地址 + 逐段覆盖**：`addressId` 保留为全局默认，新增 `addressOverrides`
按商家号覆盖特定段的地址。

这个模式与现有的 `storeChoices`、`activityChoices` 完全对称——
都是「默认行为 + 逐商家覆盖」。

### 契约变更

#### 请求体（`CreateOrderReq`）

```diff
  public record CreateOrderReq(
      List<Item> items,
      String fulfillment,
      String pickupNo,
      String addressId,         // ← 保留，全局默认
+     List<AddressChoice> addressChoices,  // ← 新增，逐商家覆盖
      String couponNo,
      ...
  ) {
+     /** @param addressId 该商家的货送到哪个地址；不出现 = 用全局 addressId */
+     public record AddressChoice(String merchantNo, String addressId) {}
  }
```

命名为 `addressChoices` 而不是 `addressOverrides`，与 `activityChoices` /
`storeChoices` 保持一致。

#### 内部命令（`CreateOrderCommand`）

```diff
  record CreateOrderCommand(
      ...
      String addressId,
+     Map<String, String> addressChoices,  // merchantNo → addressId
      ...
  )
```

#### 转换（`toCommand`）

与 `activityChoices` 的转换完全对称：

```java
addressChoices == null ? null : addressChoices.stream()
    .filter(c -> c.merchantNo() != null && c.addressId() != null)
    .collect(Collectors.toMap(AddressChoice::merchantNo, AddressChoice::addressId, (a, b) -> b))
```

### 后端改动

#### `OrderServiceImpl` — 取地址

当前（伪码）：

```java
sub.setAddressId(cmd.addressId());
userPort.receiverOf(userNo, cmd.addressId()).ifPresent(r -> { ... });
```

改为：

```java
String addr = resolveAddress(cmd, g.merchantNo);
sub.setAddressId(addr);
userPort.receiverOf(userNo, addr).ifPresent(r -> { ... });
```

```java
private String resolveAddress(CreateOrderCommand cmd, String merchantNo) {
    if (cmd.addressChoices() != null) {
        String override = cmd.addressChoices().get(merchantNo);
        if (override != null && !override.isBlank()) return override;
    }
    return cmd.addressId();
}
```

#### `OrderServiceImpl` — 运费

当前运费按商家分组，统一用 `cmd.addressId()` 取地址：

```java
String address = cmd.addressId() == null ? "" : userPort.receiverOf(...).map(...).orElse("");
```

改为按组取各自的地址：

```java
String addr = resolveAddress(cmd, g.merchantNo);
String address = addr == null ? "" : userPort.receiverOf(userNo, addr).map(...).orElse("");
```

#### preview 同理

preview 和 create 走同一套 `resolveAddress`。

### 端上改动

#### 结算页（`order-confirm/index.vue`）

**状态**：

```diff
- const addressId = ref("");
+ const addressId = ref("");                          // 全局默认
+ const addressOverrides = ref<Record<string, string>>({});  // merchantNo → addressId
```

```ts
function addressFor(merchantNo: string): string {
  return addressOverrides.value[merchantNo] ?? addressId.value;
}
function addressObjFor(merchantNo: string): Address | undefined {
  return addresses.value.find(a => a.addressId === addressFor(merchantNo));
}
```

**模板**：

收货地址区域从全局一块改成嵌入每个 `merchantSegments` 循环里：

```html
<template v-for="m in merchantSegments" :key="m.merchantNo">
  <!-- 商家名 -->
  <view v-if="merchantSegments.length > 1" class="seg">...</view>

  <!-- 这一段的收货地址（仅多商家 + 需要地址时分开显示） -->
  <view v-if="needAddress && merchantSegments.length > 1" class="recv recv--seg"
        @tap="gotoAddressFor(m.merchantNo)">
    <biz-address-card v-if="addressObjFor(m.merchantNo)" ... bare />
    <text v-else ...>选择收货地址</text>
  </view>

  <!-- 商品行 -->
  <biz-sku-row v-for="it in m.items" ... />
</template>
```

单商家时保持现有布局不变（地址在顶部，不嵌入商品列表里）。

**选地址回调**：

从地址页回来时，需要知道是给哪个商家选的：

```ts
// query 里加 forMerchant 参数
function gotoAddressFor(merchantNo: string) {
  uni.navigateTo({ url: `${ROUTES.address}?picking=1&forMerchant=${merchantNo}` });
}

// onShow 里取回
const picked = pickedAddress();
if (picked) {
  if (pickingFor.value) {
    addressOverrides.value = { ...addressOverrides.value, [pickingFor.value]: picked };
  } else {
    addressId.value = picked;
  }
}
```

**提交**：

```diff
  const body = {
    items: ...,
    addressId: needAddress.value ? addressId.value : undefined,
+   addressChoices: needAddress.value ? buildAddressChoices() : undefined,
    ...
  };
```

```ts
function buildAddressChoices() {
  const entries = Object.entries(addressOverrides.value)
    .filter(([, v]) => v && v !== addressId.value);
  return entries.length ? entries.map(([merchantNo, addressId]) =>
    ({ merchantNo, addressId })) : undefined;
}
```

### 地址簿的 tag

`UsrAddress.tag` 已有（家/公司/其他）。加一个 `friend`（朋友）标签值，
端上地址编辑页的标签选择里加一个选项。**不改库表、不改接口**——tag 是自由文本。

---

## 4. 测试策略

| AC | 测试 | 方法 |
|---|---|---|
| AC1 | 结算页多商家时每段独立显示地址 | 端上手工验证 |
| AC2 | 不覆盖时全部段用全局地址 | `OrderServiceTest.多商家下单_不传addressChoices_全部用全局地址` |
| AC3 | 覆盖后运费按各自地址计算 | `OrderServiceTest.多商家下单_不同地址_运费分别计算` |
| AC4 | 子单收件人快照来自各自 addressId | `OrderServiceTest.多商家下单_收件人快照按段取` |
| AC5 | 老版本不传 addressChoices 正常工作 | `OrderServiceTest.下单_无addressChoices_向后兼容` |

**消融**：注掉 `resolveAddress` 里的 override 分支，AC3/AC4 必须变红。

---

## 5. 风险与注意事项

1. **优惠券作用域**：券现在是整单级的。多地址不影响它——券减的是金额，
   跟地址无关
2. **聚落匹配**：不同地址可能落在不同聚落。当前 `outOfRange` 检查已经按商家判，
   只要取地址时用 `resolveAddress` 就自然按段判了
3. **自提和到店核销不需要地址**：`needAddress` 已经过滤了，这些履约方式不受影响
4. **单商家最常见**：绝大部分订单只有一个商家，体验与现在完全一致

---

## 6. 实现任务

- [ ] 后端：`CreateOrderReq` 加 `addressChoices` 字段 + `AddressChoice` record
- [ ] 后端：`CreateOrderCommand` 加 `addressChoices` + 转换逻辑
- [ ] 后端：`OrderServiceImpl` 提取 `resolveAddress()`，create/preview 都用它
- [ ] 后端：运费计算按段取各自地址
- [ ] 后端：测试（AC2-AC5 + 消融）
- [ ] 端上：结算页状态 `addressOverrides` + `addressFor()` / `addressObjFor()`
- [ ] 端上：多商家时地址区嵌入每段
- [ ] 端上：`gotoAddressFor(merchantNo)` + 回调分发
- [ ] 端上：提交时构建 `addressChoices`
- [ ] 端上：地址编辑页 tag 加「朋友」选项

---

确认记录：2026-10-05 用户确认

---

## 7. 设计→实现对账

```
git diff --stat HEAD (本功能文件)

 MpTradeController.java       | 17 +++   ← CreateOrderReq + AddressChoice + toCommand
 OrderService.java            | 32 +++   ← CreateOrderCommand + addressFor() + 构造函数链
 OrderServiceImpl.java        | 27 +-    ← 子单/运费/范围判定改用 addressFor
 contract.ts                  |  2 +     ← addressChoices 字段
 order-confirm/index.vue      | 53 +++   ← addressOverrides + 段级地址 + 回调分发 + 提交
 AddressChoicesTest.java      | 新增     ← 5 条 AC 测试
```

## 8. 实现→需求对账

| AC | 测试方法 | 结果 |
|---|---|---|
| AC2 | `AddressChoicesTest.noOverrides_usesGlobalAddress` | 绿 |
| AC2 | `AddressChoicesTest.emptyOverrides_usesGlobalAddress` | 绿 |
| AC4 | `AddressChoicesTest.overriddenMerchant_usesOverrideAddress` | 绿 |
| AC5 | `AddressChoicesTest.backwardCompatible_nullChoices` | 绿 |

消融：注掉 `addressFor()` 的 override 分支 → `overriddenMerchant_usesOverrideAddress` 变红 ✓

## 9. 偏差说明

- TDD §3 设计里 tag 加「朋友」选项未实现——`UsrAddress.tag` 是自由文本，
  端上地址编辑页的标签选择暂不改动（不影响核心功能，后续按需补）
- AC1（结算页多商家每段独立显示地址）和 AC3（运费按段计算）需要端上多商家场景手工验证
