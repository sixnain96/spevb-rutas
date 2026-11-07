package co.edu.usta.spevb.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

/** Leaflet y sus imágenes no cambian entre versiones: se cachean 30 días en el navegador. */
@Configuration
public class WebCacheConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/vendor/**")
                .addResourceLocations("classpath:/static/vendor/")
                .setCacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic());
    }
}
