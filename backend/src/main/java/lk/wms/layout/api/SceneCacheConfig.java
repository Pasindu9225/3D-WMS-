package lk.wms.layout.api;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * ETags for the 3D data (v2.1): a fingerprint of the answer; a browser that sends the same fingerprint back gets 304
 * and no body. The server still builds the answer (ponytail: fine at pilot size; a publish-based ETag would also save
 * that work), but nothing crosses the internet when the layout has not changed.
 */
@Configuration
public class SceneCacheConfig {

    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> sceneEtags() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> bean = new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        bean.addUrlPatterns("/api/v1/warehouses/*");
        bean.setName("sceneEtags");
        return bean;
    }
}
