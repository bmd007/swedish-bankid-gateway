package wonderland.authentication.swedish.bankid.gateway.repository;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;


@Testcontainers
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Slf4j
@ActiveProfiles("local-test")
class CompletedAuthenticationRepositoryTest {

    @Autowired
    private CompletedAuthenticationRepository completedAuthenticationRepository;

    private static final int REDIS_PORT = 6379;
    @SuppressWarnings("rawtypes")
    @Container
    static GenericContainer redis = new GenericContainer(DockerImageName.parse("redis:6-alpine")).withExposedPorts(REDIS_PORT);

    @DynamicPropertySource
    static void setRedisProperties(DynamicPropertyRegistry registry) {
        String address = redis.getHost();
        Integer port = redis.getFirstMappedPort();
        registry.add("redis.host", () -> address);
        registry.add("redis.port", () -> port);
    }

    @Test
    void saveAndGetCompletedAuthentication() {
        String orderRef = "orderRef1";
        String nationalId = "nat-reg-no";
        assertThat(completedAuthenticationRepository.getNationalId(orderRef).block()).isNull();
        completedAuthenticationRepository.save(orderRef, nationalId).block();
        String storedNationalId = completedAuthenticationRepository.getNationalId(orderRef).block();
        assertThat(storedNationalId).isEqualTo(nationalId);
        assertThat(completedAuthenticationRepository.getNationalId(orderRef).block()).isNull();
    }
}
