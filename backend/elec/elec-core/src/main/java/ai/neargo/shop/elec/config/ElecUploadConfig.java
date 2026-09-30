package ai.neargo.shop.elec.config;

import ai.neargo.shop.elec.support.UploadFileStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** 上传原件的存放。启动即自检：目录写不了就让服务起不来，而不是等第一个供应商上传时 500 */
@ConditionalOnElec
@Configuration(proxyBeanMethods = false)
public class ElecUploadConfig {

    @Bean
    public UploadFileStore uploadFileStore(ElecProperties props) {
        UploadFileStore store = new UploadFileStore(Path.of(props.getUpload().getDir()));
        store.selfCheck();
        return store;
    }
}
