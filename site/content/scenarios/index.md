---
title: 支持哪些店
slug: /scenarios/
description: 销售商品、提供服务、代收货物三类均支持。生鲜、便利、到店与上门服务、社区自提点、多门店连锁、自有品牌六类各自怎么用。
audience: 想确认自己这类店支不支持的店主
goal: 点进对应的类型页
---

## 按交付方式划分，不按行业划分

```yaml
type: hero
cta: [免费开店]
ctaHref: ["{{site.merchantEntry}}"]
```

销售商品、提供服务、代收货物三类均支持。下列六类中能对应一类即可使用。

---

## 六类店铺

```yaml
type: cards
columns: 3
```

### 生鲜果蔬

`/scenarios/fresh/`

菜店、水果、水产。定时截单、售罄即止、分拣两种视图。

### 便利 · 日用 · 烘焙

`/scenarios/convenience/`

便利店、烘焙、餐饮外带、酒水。店铺码进店不计佣金，四类活动。

### 到店与上门服务

`/scenarios/service/`

美业、维修、家政、宠物、洗衣、摄影。先付款，到店核销或预约上门。

### 社区自提点

`/scenarios/pickup-point/`

小区门口的店、代收点。按件计 {{fee.fulfillPerItem}}，不销售商品亦可承接。

### 多门店与连锁

`/scenarios/chain/`

两家以上。各门店独立收款、独立库存与权限。

### 自有品牌

`/scenarios/own-brand/`

已有品牌。加购自有微信小程序，顾客名单归商家。

---

## 需要资质的品类

```yaml
type: prose
```

药房个护等品类按类目提交材料，有相应资质方可上架。其余品类可用个人身份开设。

---

## 不属于上述六类

```yaml
type: cta
tone: brand
cta: [免费开店, 联系招商]
ctaHref: ["{{site.merchantEntry}}", "mailto:{{site.email}}"]
```

交付方式属于到店自提、送货上门、快递、到店核销、预约上门其中一种，即可使用。
