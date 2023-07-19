package wonderland.authentication.swedish.bankid.gateway.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "bankid")
@Getter
@Builder
public class BankIdProperties {
    @NotNull
    URI baseUrl;
    @NotBlank
    String trustStore;
    @NotBlank
    String keyStorePrivateKey;
    @NotBlank
    String keyStoreCertificate;
    @NotNull
    Duration nationalIdCacheTTL;
}
