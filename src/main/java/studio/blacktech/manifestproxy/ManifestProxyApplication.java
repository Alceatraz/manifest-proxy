package studio.blacktech.manifestproxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import studio.blacktech.manifestproxy.filter.ManifestProxy;

@SpringBootApplication
public class ManifestProxyApplication {

  public static void main(String[] args) {
    SpringApplication.run(ManifestProxyApplication.class, args);
  }

  @Bean
  public FilterRegistrationBean<ManifestProxy> manifestProxyFilterRegistrationBean(ManifestProxy manifestProxy) {
    FilterRegistrationBean<ManifestProxy> registrationBean = new FilterRegistrationBean<>();
    registrationBean.setFilter(manifestProxy);
    registrationBean.addUrlPatterns("/*");
    return registrationBean;
  }

}
