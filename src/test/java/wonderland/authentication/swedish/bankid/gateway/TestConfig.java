package wonderland.authentication.swedish.bankid.gateway;

import com.google.api.gax.core.CredentialsProvider;
import com.google.api.gax.core.NoCredentialsProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestConfig {
    @Bean
    @Primary
    CredentialsProvider googleCredentials() {
        return NoCredentialsProvider.create();
    }
}
