# TDD · 运营端 mock 报错双语化

把 mock 层 254 处规则报错从「一律中文」改成双语，并加守卫防回退。

## 一、要解决的问题

页面文案早就中英双语了，但**错误提示一直是中文**：

```
界面：EN        错误提示：处置结论不能为空 —— 没有结论的「已处理」等于没处理
```

而错误提示恰恰是用户最需要看懂的那句话 —— 页面上的列名看不懂还能猜，
一条「为什么不让我保存」看不懂就只能放弃。

## 二、方案：`fail(zh, en)`，在抛出的那一刻定稿

```ts
// lib/biz-error.ts
export function fail(zh: string, en: string): never {
  throw new ApiError(400, useLocaleStore.getState().locale === "en" ? en : zh);
}
export function notFound(zhEntity: string, enEntity: string, no: string): never { … }
```

**为什么不是 i18n key**：这些句子都是一次性的、带上下文的具体说明
（「渠道有、平台无的差异必须选择处置方式：补单或退款」），不是可复用的短语。
给每条起一个 key，只会得到 250 个只被引用一次的 key，以及一个必须来回跳的目录。

**为什么在抛出时定稿而不是渲染时翻译**：这与真实后端同构 ——
`http-client.ts` 发 `Accept-Language`，后端按语言返回本地化的 `msg`，前端原样显示。
mock 在这里做的是同一件事。副作用也一致：切换语言不会改写已经弹出的那条 toast。

**放在 `lib/biz-error.ts` 而不是 `lib/api/mocks/_err.ts`**：
`lib/mock/db/helpers.ts` 的状态机守卫也要用它，而 db 层不该反过来依赖 api 层。

### 状态机报错

`assertTransition` 的签名从 `(table, from, to, entity)` 改成
`(table, from, to, zhEntity, enEntity)`，18 处调用点各补一个英文实体名。
不改的话，状态机那类报错会变成「半中文」——句式英文、实体名中文。

## 三、守卫（lib/mock-error.test.ts）

三条断言，都验证过**确实会失败**（故意注入违规，三条各自报错，再还原）：

1. `lib/api/mocks/*.ts` 里不许再出现 `throw new Error(` —— 一律走 `fail` / `notFound`
2. `fail()` 的**第二个参数里不许有汉字** —— 复制粘贴漏译最常见的样子。
   实现上要按顶层逗号切参数、并剔掉 `${…}` 插值（`${sku.title.zh}` 是表达式不是文案）
3. `assertTransition` 的调用必须有 5 个参数

第 1 条同时排除注释（本仓注释是中文的），用的是去注释后的代码文本。

## 四、验收

- `npm run check`：40 文件 559 用例通过（新增 42 条守卫断言）
- `npm run build`：编译通过
- 浏览器实测：
  - EN 下空结论提交 → `A resolution note is required — “handled” with no note is not handled`
  - EN 下漏选处置方式 → `A channel-only difference needs a choice: create the order or refund it`
  - EN 下人工改状态不写原因 → `Changing the state by hand needs a reason — it overrides the system's own judgement`
  - 切回中文，同一操作 → `人工改状态必须写原因 —— 这是覆盖了系统的判断`

## 五、已知取舍

- 英文不是逐字直译。中文里的破折号补充说明（「—— 没有结论的『已处理』等于没处理」）
  在英文里用了英文自己的说法，因为直译出来的英文没人这么讲话。
- 已弹出的 toast 不随语言切换改写。这与真实后端行为一致，不打算修。
- `lib/i18n/messages/*.ts` 里的**框架级**错误（网络失败、401/403/404 兜底）仍走 i18n key，
  没有并入 `fail()` —— 那几条是真正可复用的短语，key 是合适的表达。
