/** Print module endpoints; generated from the reviewed API catalog. */
export const PRINT_ENDPOINTS = {
  "P01": {
    "method": "GET",
    "path": "/biz/print/adapters"
  },
  "P02": {
    "method": "GET",
    "path": "/biz/print/printers"
  },
  "P03": {
    "method": "POST",
    "path": "/biz/print/printers"
  },
  "P04": {
    "method": "GET",
    "path": "/biz/print/printers/{printerNo}"
  },
  "P05": {
    "method": "PUT",
    "path": "/biz/print/printers/{printerNo}"
  },
  "P06": {
    "method": "POST",
    "path": "/biz/print/printers/{printerNo}/connection-changes"
  },
  "P07": {
    "method": "POST",
    "path": "/biz/print/printers/{printerNo}/status-changes"
  },
  "P08": {
    "method": "POST",
    "path": "/biz/print/printers/{printerNo}/credential-rotations"
  },
  "P09": {
    "method": "POST",
    "path": "/biz/print/printers/{printerNo}/probes"
  },
  "P10": {
    "method": "POST",
    "path": "/biz/print/printers/{printerNo}/test-jobs"
  },
  "A01": {
    "method": "GET",
    "path": "/biz/print/agents"
  },
  "A02": {
    "method": "POST",
    "path": "/biz/print/agents"
  },
  "A03": {
    "method": "GET",
    "path": "/biz/print/agents/{agentNo}"
  },
  "A04": {
    "method": "POST",
    "path": "/biz/print/agents/{agentNo}/enrollments"
  },
  "A05": {
    "method": "POST",
    "path": "/biz/print/agents/{agentNo}/revocations"
  },
  "A06": {
    "method": "POST",
    "path": "/biz/print/agents/{agentNo}/credential-rotations"
  },
  "C01": {
    "method": "GET",
    "path": "/biz/print/scenes"
  },
  "C02": {
    "method": "GET",
    "path": "/biz/print/scene-policies"
  },
  "C03": {
    "method": "PUT",
    "path": "/biz/print/scene-policies/{scene}"
  },
  "C04": {
    "method": "GET",
    "path": "/biz/print/scene-policies/{scene}/versions"
  },
  "C05": {
    "method": "GET",
    "path": "/biz/print/templates"
  },
  "C06": {
    "method": "POST",
    "path": "/biz/print/templates"
  },
  "C07": {
    "method": "POST",
    "path": "/biz/print/templates/{templateNo}/versions"
  },
  "C08": {
    "method": "PUT",
    "path": "/biz/print/template-versions/{versionNo}"
  },
  "C09": {
    "method": "POST",
    "path": "/biz/print/template-versions/{versionNo}/preview"
  },
  "C10": {
    "method": "POST",
    "path": "/biz/print/template-versions/{versionNo}/publish"
  },
  "C11": {
    "method": "GET",
    "path": "/biz/print/templates/{templateNo}/versions"
  },
  "C12": {
    "method": "GET",
    "path": "/biz/print/routes"
  },
  "C13": {
    "method": "POST",
    "path": "/biz/print/routes"
  },
  "C14": {
    "method": "POST",
    "path": "/biz/print/routes/{routeNo}/versions"
  },
  "C15": {
    "method": "POST",
    "path": "/biz/print/route-versions/{versionNo}/dry-run"
  },
  "C16": {
    "method": "POST",
    "path": "/biz/print/route-versions/{versionNo}/publish"
  },
  "C17": {
    "method": "GET",
    "path": "/biz/print/routes/{routeNo}/versions"
  },
  "C18": {
    "method": "POST",
    "path": "/biz/print/template-resolutions/preview"
  },
  "C19": {
    "method": "GET",
    "path": "/biz/print/scenes/{scene}/fields"
  },
  "C20": {
    "method": "GET",
    "path": "/biz/print/scenes/{scene}/examples"
  },
  "J01": {
    "method": "GET",
    "path": "/biz/print/requests"
  },
  "J02": {
    "method": "GET",
    "path": "/biz/print/requests/{requestNo}"
  },
  "J03": {
    "method": "POST",
    "path": "/biz/print/requests"
  },
  "J04": {
    "method": "POST",
    "path": "/biz/print/requests/{requestNo}/generation-retries"
  },
  "J05": {
    "method": "GET",
    "path": "/biz/print/batches/{batchNo}"
  },
  "J06": {
    "method": "GET",
    "path": "/biz/print/jobs"
  },
  "J07": {
    "method": "GET",
    "path": "/biz/print/jobs/{jobNo}"
  },
  "J08": {
    "method": "GET",
    "path": "/biz/print/jobs/{jobNo}/attempts"
  },
  "J09": {
    "method": "GET",
    "path": "/biz/print/jobs/{jobNo}/receipts"
  },
  "J10": {
    "method": "GET",
    "path": "/biz/print/jobs/{jobNo}/document"
  },
  "J11": {
    "method": "GET",
    "path": "/biz/print/orders/{orderNo}/summary"
  },
  "J12": {
    "method": "GET",
    "path": "/biz/print/operations/{operationNo}"
  },
  "J13": {
    "method": "POST",
    "path": "/biz/print/jobs/{jobNo}/reprints"
  },
  "J14": {
    "method": "POST",
    "path": "/biz/print/reprints"
  },
  "J15": {
    "method": "POST",
    "path": "/biz/print/jobs/{jobNo}/extra-copies"
  },
  "J16": {
    "method": "POST",
    "path": "/biz/print/jobs/{jobNo}/retries"
  },
  "J17": {
    "method": "POST",
    "path": "/biz/print/jobs/{jobNo}/cancellations"
  },
  "J18": {
    "method": "POST",
    "path": "/biz/print/jobs/{jobNo}/result-queries"
  },
  "J19": {
    "method": "POST",
    "path": "/biz/print/jobs/{jobNo}/manual-confirmations"
  },
  "J20": {
    "method": "POST",
    "path": "/biz/print/jobs/{jobNo}/stale-releases"
  },
  "J21": {
    "method": "POST",
    "path": "/biz/print/printers/{printerNo}/uncertainty-resolutions"
  },
  "M01": {
    "method": "GET",
    "path": "/biz/print/health"
  },
  "M02": {
    "method": "GET",
    "path": "/biz/print/incidents"
  },
  "M03": {
    "method": "POST",
    "path": "/biz/print/incidents/{incidentNo}/acknowledgements"
  },
  "M04": {
    "method": "GET",
    "path": "/biz/print/action-logs"
  },
  "M05": {
    "method": "GET",
    "path": "/biz/print/changes"
  },
  "S01": {
    "method": "POST",
    "path": "/biz/print/simulations"
  },
  "S02": {
    "method": "GET",
    "path": "/biz/print/simulations"
  },
  "S03": {
    "method": "GET",
    "path": "/biz/print/simulations/{simulationNo}"
  },
  "E01": {
    "method": "POST",
    "path": "/biz/print/exports"
  },
  "E02": {
    "method": "GET",
    "path": "/biz/print/exports"
  },
  "E03": {
    "method": "GET",
    "path": "/biz/print/exports/{exportNo}"
  },
  "E04": {
    "method": "POST",
    "path": "/biz/print/exports/{exportNo}/download-grants"
  }
} as const;
export type PrintEndpointId = keyof typeof PRINT_ENDPOINTS;
