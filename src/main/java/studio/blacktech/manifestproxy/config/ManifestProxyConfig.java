package studio.blacktech.manifestproxy.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import studio.blacktech.manifestproxy.filter.ManifestProxy;

@Configuration
public class ManifestProxyConfig {

    @Bean
    public FilterRegistrationBean<ManifestProxy> manifestProxyFilterRegistrationBean(ManifestProxy manifestProxy) {
        FilterRegistrationBean<ManifestProxy> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(manifestProxy);
        registrationBean.addUrlPatterns("/*");
        return registrationBean;
    }
}
