package wonderland.authentication.swedish.bankid.gateway;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import wonderland.authentication.swedish.bankid.gateway.type.AuthenticationResponse;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@Testcontainers
@Slf4j
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureWebTestClient
class ConnectionTest {

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
