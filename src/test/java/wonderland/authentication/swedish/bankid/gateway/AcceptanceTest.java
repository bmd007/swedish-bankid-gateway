package wonderland.authentication.swedish.bankid.gateway;

import com.github.tomakehurst.wiremock.admin.model.ServeEventQuery;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import wonderland.authentication.swedish.bankid.gateway.client.type.AuthenticationResponse;
import wonderland.authentication.swedish.bankid.gateway.config.BankIdProperties;
import wonderland.authentication.swedish.bankid.gateway.type.AuthenticationEvent;
import wonderland.authentication.swedish.bankid.gateway.type.AuthenticationStatus;
import wonderland.authentication.swedish.bankid.gateway.type.NationalIdResponse;

import java.io.IOException;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static wonderland.authentication.swedish.bankid.gateway.type.AuthenticationStatus.COMPLETE;
import static wonderland.authentication.swedish.bankid.gateway.type.AuthenticationStatus.ERROR;
import static wonderland.authentication.swedish.bankid.gateway.type.AuthenticationStatus.FAILED;

@Testcontainers
@Slf4j
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureWebTestClient
class AcceptanceTest {

    @SuppressWarnings("rawtypes")
    @Container
    static GenericContainer redis = new GenericContainer(DockerImageName.parse("redis:6-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void setRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("redis.host", () -> redis.getHost());
        registry.add("redis.port", () -> redis.getFirstMappedPort());
    }

    @Autowired
    private WebTestClient testClient;

    @Test
    void testConnection() {
        testClient.get()
                .uri("/v1/methods/swedish-bankid/client-test/auth?ip=193.111.106.161")
                .exchange()
                .expectBody(AuthenticationResponse.class)
                .value(authenticationResponse -> {
                    log.info("AuthenticationResponse: {}", authenticationResponse);
                    assertNotNull(authenticationResponse.orderRef());
                });
    }
}
