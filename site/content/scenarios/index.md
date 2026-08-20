---
title: 支持哪些店
slug: /scenarios/
description: 卖货、做服务、代收货三类都支持。生鲜、便利、烘焙、餐饮、美业、维修、宠物、药房、多门店连锁各自怎么用，点进去看。
audience: 想确认自己这类店支不支持的店主
goal: 点进对应的类型页
---

## 卖货、做服务、代收货，都支持。

```yaml
type: hero
cta: [免费开店]
ctaHref: ["{{site.merchantEntry}}"]
```

系统不按行业分，按**你怎么把东西交到顾客手上**分。
下面六类里能对上一类，就跑得起来。

---

## 六类店铺

```yaml
type: cards
columns: 3
```

### 生鲜果蔬

`/scenarios/fresh/`

菜店、水果、水产。定时截单、按单备货、卖完即止，到货分拣两种视图。

### 便利 · 日用 · 烘焙

`/scenarios/convenience/`

便利店、日用、烘焙咖啡、餐饮外带、酒水。老客扫码零佣金，四类活动提复购。

### 到店与上门服务

`/scenarios/service/`

美业、维修、家政、宠物、洗衣、摄影。顾客先付款，到店核销或预约上门。

### 社区自提点

`/scenarios/pickup-point/`

小区门口的店、代收点。不卖货也能挣，按件计 {{fee.fulfillPerItem}}。

### 多门店与连锁

`/scenarios/chain/`

两家以上。账各走各的、人各管各的，几家店并排比。

### 自有品牌

`/scenarios/own-brand/`

已经有招牌。加购自己的微信小程序，顾客归你，后台共用。

---

## 需要资质的品类

```yaml
type: prose
```

药房个护这类按类目要求提交材料，**有证才能上架** ——
对你的意义是同行不会拿无证的价格把你挤下去。

其余品类**没有营业执照也能开**，用个人身份就行。

---

## 不在上述六类之内

```yaml
type: cta
tone: brand
cta: [免费开店, 联系招商]
ctaHref: ["{{site.merchantEntry}}", "mailto:{{site.email}}"]
```

只要是「到店自提、送货上门、快递、到店核销、预约上门」其中一种交付方式，就能用。
不确定的话直接问我们。
