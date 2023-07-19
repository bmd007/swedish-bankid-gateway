package wonderland.authentication.swedish.bankid.gateway.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "redis")
@AllArgsConstructor
@Getter
public class RedisProperties {

    @NotBlank
    String host;
    @NotNull
    Integer port;
    @NotBlank
    String password;
    @NotNull
    Boolean sslEnabled;
    String certificate;
    @NotNull
    Duration timeout;
    @NotNull
    Duration commandTimeout;
    @NotNull
    Duration connectTimeout;
}
