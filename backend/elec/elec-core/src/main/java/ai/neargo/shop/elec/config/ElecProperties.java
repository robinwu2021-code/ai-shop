package ai.neargo.shop.elec.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 元器件域的配置。生产走环境变量（{@code SHOP_ELEC_ENABLED} / {@code SHOP_ELEC_DATASOURCE_URL} …），
 * 与进销存同一个形状。
 */
@ConfigurationProperties(prefix = "shop.elec")
public class ElecProperties {

    /** 默认关：打开要先建 ai_shop_elec 库，这是一次配置改动，不是一次发版 */
    private boolean enabled = false;

    private boolean flywayEnabled = true;

    private String flywayLocations = "classpath:db/elec";

    private Datasource datasource = new Datasource();

    /** 库存多少天没重新上传或点「仍有货」就不再计入买家看到的库存 */
    private int stockTtlDays = 30;

    /** 一次上传最多多少行（不含表头） */
    private int uploadMaxRows = 20000;

    /** 库存表上传：暂存、原件、限次、护栏（TDD-元器件-库存上传二期） */
    private Upload upload = new Upload();

    /** 大模型认列（cdw 上的 qwen，OpenAI 兼容）。默认关：开是一次配置改动 */
    private Ai ai = new Ai();

    /** 买家看到的参考价 = 供应商最低价 × (1 + markupBp/10000)，至少加 markupMinE6 */
    private int markupBp = 800;

    private long markupMinE6 = 500;

    /** 不含税的报价换算成含税参考价用的税率（万分比）。增值税 13% */
    private int vatBp = 1300;

    /**
     * 外币换人民币的汇率（万分比：10000 = 1.0）。**手工值，不接实时汇率** ——
     * 参考价本来就只是个量级，接实时汇率会让同一个料号的显示价一天变好几次，
     * 而买家会拿它来质问报价。真实价以平台报价为准。
     */
    private int usdToCnyBp = 71000;

    private int hkdToCnyBp = 9100;

    public int getUsdToCnyBp() {
        return usdToCnyBp;
    }

    public void setUsdToCnyBp(int usdToCnyBp) {
        this.usdToCnyBp = usdToCnyBp;
    }

    public int getHkdToCnyBp() {
        return hkdToCnyBp;
    }

    public void setHkdToCnyBp(int hkdToCnyBp) {
        this.hkdToCnyBp = hkdToCnyBp;
    }

    /** 一张询价单最多几行 */
    private int rfqMaxLines = 50;

    /**
     * 每行最多派给几家供应商。
     *
     * <p><b>派太多的后果不是吵，是响应率整体塌掉</b>：一条求购派给二十家，
     * 十九家白填一遍报价，下次就没人填了。
     */
    private int dispatchMaxPerLine = 5;

    public int getDispatchMaxPerLine() {
        return dispatchMaxPerLine;
    }

    public void setDispatchMaxPerLine(int dispatchMaxPerLine) {
        this.dispatchMaxPerLine = dispatchMaxPerLine;
    }

    /**
     * 查料号的限流（每分钟）。料号库存是同行最想爬的数据：游客按 IP 计，登录用户按人计。
     * 正常人工查询一分钟十来次，边打字边提示会多一些，所以给得宽
     */
    private int searchPerMinuteAnon = 60;

    private int searchPerMinuteUser = 120;

    public int getSearchPerMinuteAnon() {
        return searchPerMinuteAnon;
    }

    public void setSearchPerMinuteAnon(int searchPerMinuteAnon) {
        this.searchPerMinuteAnon = searchPerMinuteAnon;
    }

    public int getSearchPerMinuteUser() {
        return searchPerMinuteUser;
    }

    public void setSearchPerMinuteUser(int searchPerMinuteUser) {
        this.searchPerMinuteUser = searchPerMinuteUser;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFlywayEnabled() {
        return flywayEnabled;
    }

    public void setFlywayEnabled(boolean flywayEnabled) {
        this.flywayEnabled = flywayEnabled;
    }

    public String getFlywayLocations() {
        return flywayLocations;
    }

    public void setFlywayLocations(String flywayLocations) {
        this.flywayLocations = flywayLocations;
    }

    public Datasource getDatasource() {
        return datasource;
    }

    public void setDatasource(Datasource datasource) {
        this.datasource = datasource;
    }

    public int getStockTtlDays() {
        return stockTtlDays;
    }

    public void setStockTtlDays(int stockTtlDays) {
        this.stockTtlDays = stockTtlDays;
    }

    public int getUploadMaxRows() {
        return uploadMaxRows;
    }

    public void setUploadMaxRows(int uploadMaxRows) {
        this.uploadMaxRows = uploadMaxRows;
    }

    public Upload getUpload() {
        return upload;
    }

    public void setUpload(Upload upload) {
        this.upload = upload;
    }

    public Ai getAi() {
        return ai;
    }

    public void setAi(Ai ai) {
        this.ai = ai;
    }

    public int getMarkupBp() {
        return markupBp;
    }

    public void setMarkupBp(int markupBp) {
        this.markupBp = markupBp;
    }

    public long getMarkupMinE6() {
        return markupMinE6;
    }

    public void setMarkupMinE6(long markupMinE6) {
        this.markupMinE6 = markupMinE6;
    }

    public int getVatBp() {
        return vatBp;
    }

    public void setVatBp(int vatBp) {
        this.vatBp = vatBp;
    }

    public int getRfqMaxLines() {
        return rfqMaxLines;
    }

    public void setRfqMaxLines(int rfqMaxLines) {
        this.rfqMaxLines = rfqMaxLines;
    }

    /** 上传 */
    public static class Upload {
        /** 原件根目录，下面分 failed/（未入库）与 applied/（已入库）两区。生产 /data/cache/elec-upload */
        private String dir = System.getProperty("java.io.tmpdir") + "/elec-upload";

        /** 未入库区保留天数：每周清理删掉早于这么多天的日期目录 */
        private int failedRetentionDays = 7;

        /** 已入库区保留天数；<b>0 = 不删</b> */
        private int appliedRetentionDays = 0;

        /** 预览从上传起多久内可以确认。过了要重传 —— 预览里「将下架 N 行」是按那一刻算的，而数据只在内存里 */
        private int pendingTtlMinutes = 60;

        /** 内存里最多放几张待确认的表。一张两万行约 12MB；挤出无害，下次访问从原件重建 */
        private int cacheMaxBatches = 10;

        /** 每家每天上传次数；0 = 不限。只数上传，改映射、确认、放弃不算 */
        private int dailyMax = 20;

        /** 全量替换将下架 ÷ 在售 ≥ 此万分比时，确认必须带上此刻的下架数；0 = 凡有下架都要带 */
        private int delistConfirmBp = 3000;

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }

        public int getFailedRetentionDays() {
            return failedRetentionDays;
        }

        public void setFailedRetentionDays(int failedRetentionDays) {
            this.failedRetentionDays = failedRetentionDays;
        }

        public int getAppliedRetentionDays() {
            return appliedRetentionDays;
        }

        public void setAppliedRetentionDays(int appliedRetentionDays) {
            this.appliedRetentionDays = appliedRetentionDays;
        }

        public int getPendingTtlMinutes() {
            return pendingTtlMinutes;
        }

        public void setPendingTtlMinutes(int pendingTtlMinutes) {
            this.pendingTtlMinutes = pendingTtlMinutes;
        }

        public int getCacheMaxBatches() {
            return cacheMaxBatches;
        }

        public void setCacheMaxBatches(int cacheMaxBatches) {
            this.cacheMaxBatches = cacheMaxBatches;
        }

        public int getDailyMax() {
            return dailyMax;
        }

        public void setDailyMax(int dailyMax) {
            this.dailyMax = dailyMax;
        }

        public int getDelistConfirmBp() {
            return delistConfirmBp;
        }

        public void setDelistConfirmBp(int delistConfirmBp) {
            this.delistConfirmBp = delistConfirmBp;
        }
    }

    /** 大模型认列 */
    public static class Ai {
        /** 开关 */
        private boolean enabled = false;

        /** OpenAI 兼容地址，到 /v1 为止。如 http://cdw.near3.ai:8003/v1 */
        private String baseUrl = "";

        /** served name */
        private String model = "qwen3.6";

        /** 超时。超时与失败都走手工指定列，不让上传失败 */
        private int timeoutSeconds = 8;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public static class Datasource {
        private String url;
        private String username;
        private String password;
        private int maxPoolSize = 8;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }
    }
}
