# 待办：整仓迁移到 pnpm

> 2026-09-24 建立。**本文是给「在另一个会话里执行」用的工单**，自包含：
> 背景、动哪些文件、怎么改、怎么验。不需要读前面的对话。
>
> 背景：powerbank 与 soukmind 的 ops-web 已于 2026-09-24 迁到 pnpm，
> 约定三个 ops-web 统一用 pnpm（`ai-boss/ops-web` 明确不迁）。
> 本仓当时**没能执行**，原因见下。

## 为什么本仓不能只迁 ops-web

本仓是 **npm workspaces 单体仓库**，根 `package.json` 里：

```
workspaces: ["packages/*", "c-app", "b-app", "ops-web", "site"]
```

依赖全部提升到**根** `node_modules`（1.2G），`ops-web/node_modules` 实际只有 8K
（仅 `.vite` / `@types`）。所以「只把 ops-web 迁到 pnpm」在结构上不成立 ——
要迁就是**整仓一起迁**，波及 `c-app` `b-app` `site` 与 `packages/*`。

## 最大的风险：c-app / b-app 是 uni-app

| workspace | 栈 | 构建 |
|---|---|---|
| `c-app` | uni-app | `uni build` |
| `b-app` | uni-app | `uni build` |
| `ops-web` | Next.js | `next build` |
| `site` | Next.js | `next build` |

**uni-app 在 pnpm 默认的隔离/符号链接布局下极易构建失败** —— 它依赖扁平提升、
按目录向上找 `node_modules` 定位插件。Next.js 两个没这个问题。

**对策**：根 `.npmrc` 写 `node-linker=hoisted`，保留扁平布局。
注意：hoisted 只放弃「严格性」（允许幽灵依赖），**磁盘去重仍然保留** ——
包体仍从全局 store 克隆，省盘效果基本不变。先用 hoisted 跑通，
之后若想收严再逐个 workspace 试默认布局。

## 执行步骤

1. 根目录建 `pnpm-workspace.yaml`：

   ```yaml
   packages:
     - "packages/*"
     - "c-app"
     - "b-app"
     - "ops-web"
     - "site"
   ```

2. 根目录建 `.npmrc`，写 `node-linker=hoisted`（理由见上）。
3. `pnpm import` —— **必须用它**，从 `package-lock.json` 转换会保留 npm 已解析
   的版本，把版本漂移压到最低；不要直接 `pnpm install` 重新解析。
4. `rm -rf node_modules */node_modules && pnpm install`
5. 处理 `ERR_PNPM_IGNORED_BUILDS`：pnpm 默认不跑依赖的 postinstall。
   会生成 `pnpm-workspace.yaml` 占位符 `sharp: set this to true or false` ——
   **那既不是 true 也不是 false，必须显式定**。两个 Next 若是
   `output:"export"` + `images.unoptimized` 则 sharp 用不到，填 `false`。
6. 跑通后删 `package-lock.json`。

## 验收（必须真跑，全绿才算完）

- [ ] `c-app`：`uni build` 成功，产物非空 ← **最可能失败的一步**
- [ ] `b-app`：`uni build` 成功
- [ ] `ops-web`：`next build` 成功
- [ ] `site`：`next build` 成功
- [ ] `.vue` 的类型检查走 **`vue-tsc` 不是 `tsc`**（见 CLAUDE.md §「改了 `.vue`」）
- [ ] 全仓测试按既有命令跑一遍

## 不需要担心的

本仓**没有任何部署脚本引用 `npm ci` / `npm install`**（已 grep 全仓 `*.sh`
`Dockerfile*` `*.yml` 确认），所以不存在「改了本地、线上构建断掉」的问题。
这点与 soukmind 不同 —— 那边有两处在远程服务器上 `npm ci`。

## 量化节省时别用 `du`

macOS APFS 上 pnpm 默认用 **clone（写时复制）** 而非硬链接，克隆文件
**链接数 = 1、`du` 按全尺寸计**，所以 `du` 看起来「完全没省」是假象。
实测：第三份 462M 的依赖真实只占 **10MB**。要量就用 `df` 前后差。
