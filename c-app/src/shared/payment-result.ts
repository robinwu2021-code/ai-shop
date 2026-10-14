import type { OrderStatus } from "@shared/types";

export function paymentPhase(status: OrderStatus | undefined, awaiting = false) {
  switch (status) {
    case "WAIT_PAY": return awaiting ? "confirming" : "waiting";
    case "WAIT_OFFLINE_PAY": return "offline";
    case "PAID": case "FULFILLING": case "COMPLETED": return "paid";
    case "CANCELLED": return "cancelled";
    case "REFUNDED": return "refunded";
    default: return "unknown";
  }
}

/** 每个任务独立代数；离开页面后，等待和迟到响应都失效。 */
export class PaymentResultPoller {
  private generation = 0;
  private timer?: ReturnType<typeof setTimeout>;
  private release?: (active: boolean) => void;

  cancel() {
    this.generation++;
    clearTimeout(this.timer);
    this.release?.(false);
    this.release = undefined;
  }

  async run<T>(fetch: () => Promise<T>, accept: (value: T) => boolean, once = false) {
    this.cancel();
    const generation = this.generation;
    for (const delay of once ? [0] : [1000, 1000, 2000, 3000, 5000]) {
      if (delay && !await new Promise<boolean>((resolve) => {
        this.release = resolve;
        this.timer = setTimeout(() => { this.release = undefined; resolve(true); }, delay);
      })) return;
      if (generation !== this.generation) return;
      let value: T;
      try { value = await fetch(); } catch (error) {
        if (generation !== this.generation) return;
        throw error;
      }
      if (generation !== this.generation) return;
      if (accept(value)) return;
    }
  }
}
