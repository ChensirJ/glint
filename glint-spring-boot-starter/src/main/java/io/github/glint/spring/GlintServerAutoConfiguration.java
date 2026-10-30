package io.github.glint.spring;

import io.github.glint.http.VtServer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;

/**
 * Registers Glint as the servlet container ahead of Tomcat auto-configuration.
 */
@AutoConfiguration(before = ServletWebServerFactoryAutoConfiguration.class)
@ConditionalOnClass(VtServer.class)
public class GlintServerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ServletWebServerFactory glintServletWebServerFactory() {
        return new GlintServletWebServerFactory();
    }
}
