// mock 公共延迟：各域口径一致（列表 200ms / 保存 350ms / 动作 400ms），
// 让加载态与竞态在无后端时也能被看见 —— 零延迟的 mock 会掩盖 loading 分支根本没写。
export const wait = <T>(v: T, ms = 200): Promise<T> => new Promise((r) => setTimeout(() => r(v), ms));
