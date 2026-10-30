package io.github.glint.examples;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spring Boot 3.2 demo running on Glint instead of Tomcat.
 *
 * <p>Exclude {@code spring-boot-starter-tomcat} and depend on
 * {@code glint-spring-boot-starter}. Run with:
 *
 * <pre>{@code
 * mvn -pl glint-examples -am -Pspring-demo spring-boot:run
 * # or .\scripts\run-spring.ps1
 * }</pre>
 */
@SpringBootApplication
public class SpringBootDemo {

    public static void main(String[] args) {
        SpringApplication.run(SpringBootDemo.class, args);
    }

    @RestController
    static class HelloController {

        @GetMapping("/hello")
        String hello() {
            return "Hello from Spring Boot on Glint!";
        }

        @GetMapping("/api/users/{id}")
        String user(@PathVariable("id") String id) {
            return "{\"id\":\"" + id + "\",\"via\":\"spring-mvc\"}";
        }
    }
}
