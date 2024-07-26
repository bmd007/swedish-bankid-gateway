package wonderland.authentication.swedish.bankid.gateway.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.SslOptions;
import io.lettuce.core.TimeoutOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;

@Slf4j
@Configuration
public class RedisConfiguration {

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(RedisProperties redisProperties) throws IOException, CertificateException, NoSuchAlgorithmException, KeyStoreException {
        final LettuceClientConfiguration.LettuceClientConfigurationBuilder lettuceClientConfiguration = LettuceClientConfiguration.builder();

        final ClientOptions.Builder clientOptionsBuilder = ClientOptions.builder()
                .timeoutOptions(TimeoutOptions.enabled(redisProperties.getTimeout()))
                .socketOptions(SocketOptions.builder().connectTimeout(redisProperties.getConnectTimeout()).build());

        if (redisProperties.getSslEnabled()) {
            Certificate cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(redisProperties.getCertificate().getBytes(StandardCharsets.UTF_8)));
            final KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
            keystore.load(null);
            keystore.setCertificateEntry("redis-ca", cert);
            final TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("X509");
            trustManagerFactory.init(keystore);

            clientOptionsBuilder.sslOptions(
                    SslOptions.builder()
                            .jdkSslProvider()
                            .trustManager(trustManagerFactory)
                            .build());
            lettuceClientConfiguration.useSsl();
        }

        lettuceClientConfiguration.clientOptions(clientOptionsBuilder.build()).commandTimeout(redisProperties.getCommandTimeout());

        final RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisProperties.getHost(), redisProperties.getPort());
        config.setPassword(redisProperties.getPassword());
        return new LettuceConnectionFactory(config, lettuceClientConfiguration.build());
    }
}
