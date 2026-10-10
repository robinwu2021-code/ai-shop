// 覆盖范围：定时任务（P-17.1）。七个口，读三写四。
import type { JobLogRow, JobRow } from "@/lib/types";
import { client } from "../http-client";
import type { JobApi } from "../contracts/job";

export const jobHttp: JobApi = {
  listJobs: () => client.get<JobRow[]>("/ops/jobs"),
  getJob: (name) => client.get<JobRow>(`/ops/jobs/${encodeURIComponent(name)}`),
  listJobLogs: (q) =>
    client.get<JobLogRow[]>(`/ops/jobs/${encodeURIComponent(q.name)}/logs`, {
      page: q.page ?? 1, size: q.size ?? 50,
    }),
  enableJob: (name) => client.post<JobRow>(`/ops/jobs/${encodeURIComponent(name)}/enable`, {}),
  disableJob: (name) => client.post<JobRow>(`/ops/jobs/${encodeURIComponent(name)}/disable`, {}),
  updateJobCron: (name, cron) =>
    client.put<JobRow>(`/ops/jobs/${encodeURIComponent(name)}/cron`, { cron }),
  triggerJob: (name) => client.post<JobRow>(`/ops/jobs/${encodeURIComponent(name)}/trigger`, {}),
};
