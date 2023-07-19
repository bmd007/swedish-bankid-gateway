package wonderland.authentication.swedish.bankid.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
@SuppressWarnings("PMD.UseUtilityClass")
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
