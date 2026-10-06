package fatum.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The contract of this service as Swagger UI shows it.
 *
 * <p>The token is the one the API already validates: an access token of the user pool, sent as a
 * bearer token. Declaring it once here is what makes the "Authorize" button work, so no controller
 * repeats it; the two routes that are not protected by it say so on their own.</p>
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI userServiceApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Fatum User Service")
                        .version("v1")
                        .description("Accounts, addresses and the files that belong to them. "
                                + "The account is always the subject of the token: no route takes the "
                                + "identifier of somebody else as a parameter."))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token issued by the user pool.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}