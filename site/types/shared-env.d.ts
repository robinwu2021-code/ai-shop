/**
 * `packages/shared` 是给 uni-app（Vite）写的，里面读 `import.meta.env.VITE_*`。
 * 官网走 Next，没有 Vite 的 `vite/client` 类型，直接 import 那个模块 `tsc` 会红 ——
 * 但代码本身是对的：`import.meta.env?.` 带可选链，Next 下取到 undefined 后走默认值。
 *
 * 所以这里只补类型，不补运行时。声明合并，键与 shared 里用到的一一对应；
 * shared 新读一个 VITE_ 变量而这里没跟上，官网 typecheck 会红 —— 那正是我们想要的信号。
 */
interface ImportMetaEnv {
  readonly VITE_APP_NS?: string;
  readonly VITE_USE_MOCK?: string;
}
