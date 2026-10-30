package io.github.glint.examples;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spring Boot 3.2+ demo running on Glint instead of Tomcat.
 *
 * <p>The {@code glint-spring-boot-starter} dependency replaces the servlet
 * container. Exclude {@code spring-boot-starter-tomcat} if you pull in
 * {@code spring-boot-starter-web} transitively from elsewhere.
 */
@SpringBootApplication
public class SpringBootDemo {

    public static void main(String[] args) {
        SpringApplication.run(SpringBootDemo.class, args);
    }

    @RestController
    static class HelloController {

        @GetMapping("/hello")
        public String hello() {
            return "Hello from Spring Boot on Glint!";
        }

        @GetMapping("/users/{id}")
        public String user(@PathVariable("id") String id) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            sb.append('"').append("id").append('"').append(':').append('"').append(id).append('"').append(',');
            sb.append('"').append("via").append('"').append(':').append('"').append("spring-mvc").append('"');
            sb.append('}');
            return sb.toString();
        }
    }
}
