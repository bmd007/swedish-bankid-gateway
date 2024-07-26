package wonderland.authentication.swedish.bankid.gateway.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientConfig {

    private PrivateKey readPrivateKey(String key) throws NoSuchAlgorithmException, InvalidKeySpecException {
        String privateKeyPEM = key
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replaceAll(System.lineSeparator(), "")
                .replace("-----END PRIVATE KEY-----", "");

        byte[] encoded = Base64.getDecoder().decode(privateKeyPEM);

        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
        return keyFactory.generatePrivate(keySpec);
    }

    @Bean
    public ReactorClientHttpConnector defaultHttpConnector(BankIdProperties bankIdProperties) throws IOException, CertificateException, NoSuchAlgorithmException, InvalidKeySpecException {
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        X509Certificate trustStoreCertificate = (X509Certificate) certificateFactory.generateCertificate(new ByteArrayInputStream(bankIdProperties.getTrustStore().getBytes(Charset.defaultCharset())));
        X509Certificate keyStoreCertificate = (X509Certificate) certificateFactory.generateCertificate(new ByteArrayInputStream(bankIdProperties.getKeyStoreCertificate().getBytes(Charset.defaultCharset())));

        PrivateKey privateKey = readPrivateKey(bankIdProperties.getKeyStorePrivateKey());

        SslContext context = SslContextBuilder.forClient()
                .keyManager(privateKey, keyStoreCertificate)
                .trustManager(trustStoreCertificate)
                .build();

        HttpClient httpClient = HttpClient.create().secure(t -> t.sslContext(context));

        httpClient.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2_000);
        httpClient.doOnConnected(connection -> connection
                .addHandlerFirst(new ReadTimeoutHandler(2_000L, TimeUnit.MILLISECONDS))
                .addHandlerFirst(new WriteTimeoutHandler(2_000L, TimeUnit.MILLISECONDS))
        );
        return new ReactorClientHttpConnector(httpClient);
    }

    @Bean
    public WebClient bankIdWebClient(BankIdProperties bankIdProperties, ReactorClientHttpConnector defaultHttpConnector) {
        return WebClient.builder()
                .baseUrl(bankIdProperties.getBaseUrl().toString())
                .clientConnector(defaultHttpConnector)
                .codecs(codec -> codec.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();
    }

}
