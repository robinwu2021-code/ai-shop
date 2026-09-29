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

    /** 上传预览多久内可以确认上架；过了要重传 —— 预览里的「将下架 N 行」是按那一刻的库存算的 */
    private int batchTtlHours = 24;

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

    public int getBatchTtlHours() {
        return batchTtlHours;
    }

    public void setBatchTtlHours(int batchTtlHours) {
        this.batchTtlHours = batchTtlHours;
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
