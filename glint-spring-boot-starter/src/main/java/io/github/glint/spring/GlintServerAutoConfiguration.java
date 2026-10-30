package io.github.glint.spring;

import io.github.glint.http.VtServer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;

/**
 * Registers Glint as the servlet container ahead of Tomcat auto-configuration.
 *
 * <p>How it works:
 * <ol>
 *   <li>{@code glint-spring-boot-starter} depends on {@code spring-boot-starter-web}
 *       with {@code spring-boot-starter-tomcat} excluded.</li>
 *   <li>This class runs {@code before} {@link ServletWebServerFactoryAutoConfiguration}
 *       and publishes a {@link ServletWebServerFactory} bean.</li>
 *   <li>{@link GlintServletWebServerFactory} boots Spring MVC on Glint's
 *       virtual-thread {@link VtServer} via the {@code glint-servlet} adapter.</li>
 * </ol>
 *
 * <p>Listen port comes from Spring Boot's standard {@code server.port}
 * (default {@code 8080}). Glint-specific knobs live under {@code glint.*}
 * ({@link GlintProperties}). See {@code docs/spring-boot.md} for the full
 * property reference.
 */
@AutoConfiguration(before = ServletWebServerFactoryAutoConfiguration.class)
@ConditionalOnClass(VtServer.class)
@EnableConfigurationProperties(GlintProperties.class)
public class GlintServerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ServletWebServerFactory glintServletWebServerFactory(GlintProperties properties) {
        GlintServletWebServerFactory factory = new GlintServletWebServerFactory();
        factory.setDiagnostics(properties.isDiagnostics());
        return factory;
    }
}