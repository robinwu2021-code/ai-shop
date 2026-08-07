"use client";

// 配置表单的编辑状态。
//
// **为什么需要它**：7 个配置页（逾期规则 / 极速退 / 评分参数 / 费率 / 归因规则 / 频控 / 外观）
// 都在重复同一段：
//
//   const [form, setForm] = useState<F | null>(null);
//   const editing = form ?? (data ? toForm(data) : null);
//   ... onChange={(e) => setForm({ ...editing, x: e.target.value })}
//
// 最后那行是**已确诊的 bug**：连续改两个字段时，第二次读到的 `editing` 还是上一次渲染的值
// （`form` 尚未提交），第一次的变更被覆盖。输入框场景不易触发（每次按键都重渲染），
// 复选框/开关连点必然触发 —— growth 页实机踩到过，当时另外 4 个页面还带着同样的写法。
//
// 这个 hook 把「派生初值 + 函数式更新」封在里面：`patch` 只接受更新函数，
// 调用方**想写错都写不出来**。
import * as React from "react";

export interface EditableConfig<F> {
  /** 当前表单值；数据未到达时为 null */
  form: F | null;
  /** 局部更新。内部是函数式更新，不会读到过期的闭包值 */
  patch: (fn: (prev: F) => F) => void;
  /** 单字段更新的快捷方式 */
  set: <K extends keyof F>(key: K, value: F[K]) => void;
  /** 丢弃本地修改，回到服务端值（保存成功后调用） */
  reset: () => void;
  /** 是否有未保存的修改 */
  dirty: boolean;
}

/**
 * @param data   服务端数据（`useQuery` 的 data）
 * @param toForm 把服务端数据映射成表单形状（通常是把数字转成字符串）
 */
export function useEditableConfig<D, F>(
  data: D | undefined,
  toForm: (d: D) => F,
): EditableConfig<F> {
  const [local, setLocal] = React.useState<F | null>(null);

  // toForm 通常是就地写的箭头函数（每次渲染都是新引用），所以不放进依赖 ——
  // 放进去会导致每次渲染都重算派生值，编辑中的内容被服务端值覆盖。
  const derived = data ? toForm(data) : null;
  const form = local ?? derived;

  const patch = React.useCallback((fn: (prev: F) => F) => {
    // ⚠️ 关键：用 setState 的函数形式读取「最新的」值，而不是闭包里的 form。
    // prev 为 null 说明还没本地修改过，此时以派生值为基准。
    setLocal((prev) => fn(prev ?? (derivedRef.current as F)));
  }, []);

  // derived 每次渲染都变，用 ref 让 patch 保持稳定引用的同时能读到最新派生值
  const derivedRef = React.useRef(derived);
  derivedRef.current = derived;

  const set = React.useCallback(
    <K extends keyof F>(key: K, value: F[K]) => patch((p) => ({ ...p, [key]: value })),
    [patch],
  );

  const reset = React.useCallback(() => setLocal(null), []);

  return { form, patch, set, reset, dirty: local !== null };
}
