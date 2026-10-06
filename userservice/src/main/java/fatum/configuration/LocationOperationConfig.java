package fatum.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "app.operation")
@Getter
@Setter
public class LocationOperationConfig {
    // Spring Boot mapeará automáticamente el YAML a este Map
    // Llave: País, Valor: Lista de ciudades
    private Map<String, List<String>> allowedLocations;
}