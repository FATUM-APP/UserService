package fatum;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Collections;

/**
 * Entry point of the Fatum user microservice.
 * Designed to run locally or as a containerized service on Google Cloud Run.
 */
@SpringBootApplication
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(UserServiceApplication.class);
        app.setDefaultProperties(Collections.singletonMap("server.port", getPort()));
        app.run(args);
    }

    private static int getPort() {
        String port = System.getenv("PORT");
        return port == null ? 8080 : Integer.parseInt(port);
    }
}
