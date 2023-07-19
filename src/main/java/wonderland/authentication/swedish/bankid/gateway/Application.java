package wonderland.authentication.swedish.bankid.gateway;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
@SuppressWarnings("PMD.UseUtilityClass")
@OpenAPIDefinition(servers = @Server(url = "http://localhost:8080", description = "Set to localhost so that it can be used with skaffold and kubectl port-forward"))
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
