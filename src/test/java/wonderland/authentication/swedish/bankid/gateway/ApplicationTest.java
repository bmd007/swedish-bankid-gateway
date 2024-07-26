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
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static wonderland.authentication.swedish.bankid.gateway.type.AuthenticationStatus.COMPLETE;
import static wonderland.authentication.swedish.bankid.gateway.type.AuthenticationStatus.ERROR;
import static wonderland.authentication.swedish.bankid.gateway.type.AuthenticationStatus.FAILED;

@Testcontainers
@Slf4j
@ActiveProfiles("local-test")
@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureWebTestClient
class ApplicationTest {

    private static final String TEST_QR_START_TOKEN = "131daac9-16c6-4618-beb0-365768f37289";
    private static final String TEST_AUTOSTART_START_TOKEN = "bc6a7029-af6b-4b05-a02e-06f7cc5162db";
    private static final String TEST_END_USER_IP = "192.168.1.1";
    private static final String TEST_END_USER_IP2 = "192.168.2.2";

    @RegisterExtension
    public static final WireMockExtension wireMockExtension;

    @SuppressWarnings("rawtypes")
    @Container
    static GenericContainer redis = new GenericContainer(DockerImageName.parse("redis:6-alpine"))
            .withExposedPorts(6379);


    @DynamicPropertySource
    static void setRedisProperties(DynamicPropertyRegistry registry) {
        String address = redis.getHost();
        Integer port = redis.getFirstMappedPort();

        registry.add("redis.host", () -> address);
        registry.add("redis.port", () -> port);

        log.info("**** Redis address: {}, port: {} ****", address, port);
    }

    static {
        try {
            wireMockExtension = WireMockExtension.newInstance()
                    .options(
                            wireMockConfig()
                                    .httpsPort(12321)
                                    .needClientAuth(true)
                                    .trustStoreType("PKCS12")
                                    .trustStorePassword("pwd")
                                    .trustStorePath(new ClassPathResource("relaying_party_certificate.p12").getFile().getAbsolutePath())
                                    .keystoreType("PKCS12")
                                    .keystorePassword("pwd")
                                    .keyManagerPassword("pwd")
                                    .keystorePath(new ClassPathResource("localhost_certificate.p12").getFile().getAbsolutePath())
                    )
                    .build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Autowired
    private WebTestClient testClient;

    @Autowired
    private ReactiveRedisOperations<String, String> redisOperations;

    @Autowired
    private BankIdProperties bankIdProperties;

    @BeforeEach
    void beforeEach() {
        StepVerifier.create(redisOperations.execute(it -> it.serverCommands().flushDb())).expectNext("OK").verifyComplete();
    }

    @Test
    void getStatusStreamAllPending() {
        String orderReference = "131daac9-16c6-4618-beb0-365768f37288";

        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/auth"))
                .withRequestBody(equalTo("""
                        {
                            "endUserIp": "%s",
                            "userVisibleData": "KkxvZ2dhIGluIHDDpSBXb25kZXJsYW5kKgo=",
                            "userVisibleDataFormat": "simpleMarkdownV1"
                        }
                        """.formatted(TEST_END_USER_IP)))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                "orderRef": "%s",
                                "autoStartToken": "bc6a7029-af6b-4b05-a02e-06f7cc5162db",
                                "qrStartToken": "%s",
                                "qrStartSecret": "0ce68cf7-7d35-4386-9bad-46ee426cadca"
                                }""".formatted(orderReference, TEST_QR_START_TOKEN))));

        final StubMapping collectPending = wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/collect"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "orderRef":"%s",
                                  "status":"%s",
                                  "hintCode":"hintCode"
                                }""".formatted(orderReference, "pending"))));

        ParameterizedTypeReference<ServerSentEvent<AuthenticationEvent>> type = new ParameterizedTypeReference<>() {
        };
        Flux<AuthenticationEvent> serverSentEvents = testClient
                .get()
                .uri("/v1/methods/swedish-bankid/authentication-events?useCase=QR")
                .header("x-envoy-external-address", TEST_END_USER_IP)
                .accept(MediaType.valueOf(MediaType.TEXT_EVENT_STREAM_VALUE))
                .exchange()
                .expectStatus().isOk()
                .returnResult(type)
                .getResponseBody()
                .mapNotNull(ServerSentEvent::data);

        StepVerifier
                .create(serverSentEvents.take(3))
                .expectNextMatches(authenticationEvent -> assertPendingQrEvent(0, authenticationEvent))
                .expectNextMatches(authenticationEvent -> assertPendingQrEvent(1, authenticationEvent))
                .expectNextMatches(authenticationEvent -> assertPendingQrEvent(2, authenticationEvent))
                .verifyComplete();

        await().atMost(Duration.ofSeconds(2L))
                .pollInterval(Duration.ofMillis(100L))
                .until(() -> wireMockExtension.getServeEvents(ServeEventQuery.forStubMapping(collectPending)).getRequests().size() == 3);
    }

    @Test
    void getStatusStreamAllPendingSameDevice() {
        String orderReference = "131daac9-16c6-4618-beb0-365768f37288";

        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/auth"))
                .withRequestBody(equalTo("""
                        {
                            "endUserIp": "%s",
                            "userVisibleData": "KkxvZ2dhIGluIHDDpSBXb25kZXJsYW5kKgo=",
                            "userVisibleDataFormat": "simpleMarkdownV1"
                        }
                        """.formatted(TEST_END_USER_IP)))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                "orderRef": "%s",
                                "autoStartToken": "%s",
                                "qrStartToken": "131daac9-16c6-4618-beb0-365768f37289",
                                "qrStartSecret": "0ce68cf7-7d35-4386-9bad-46ee426cadca"
                                }""".formatted(orderReference, TEST_AUTOSTART_START_TOKEN))));

        final StubMapping collectPending = wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/collect"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "orderRef":"%s",
                                  "status":"%s",
                                  "hintCode":"hintCode"
                                }""".formatted(orderReference, "pending"))));

        ParameterizedTypeReference<ServerSentEvent<AuthenticationEvent>> type = new ParameterizedTypeReference<>() {
        };
        Flux<AuthenticationEvent> serverSentEvents = testClient
                .get()
                .uri("/v1/methods/swedish-bankid/authentication-events?useCase=SAME_DEVICE")
                .header("x-envoy-external-address", TEST_END_USER_IP)
                .accept(MediaType.valueOf(MediaType.TEXT_EVENT_STREAM_VALUE))
                .exchange()
                .expectStatus().isOk()
                .returnResult(type)
                .getResponseBody()
                .mapNotNull(ServerSentEvent::data);

        StepVerifier
                .create(serverSentEvents.take(3))
                .expectNextMatches(authenticationEvent -> assertPendingSameDeviceEvent(0, authenticationEvent))
                .expectNextMatches(authenticationEvent -> assertPendingSameDeviceEvent(1, authenticationEvent))
                .expectNextMatches(authenticationEvent -> assertPendingSameDeviceEvent(2, authenticationEvent))
                .verifyComplete();

        await().atMost(Duration.ofSeconds(2L))
                .pollInterval(Duration.ofMillis(100L))
                .until(() -> wireMockExtension.getServeEvents(ServeEventQuery.forStubMapping(collectPending)).getRequests().size() == 3);
    }

    @Test
    void getWithoutXForwardedForHeader() {
        testClient.get()
                .uri("/v1/methods/swedish-bankid/authentication-events?useCase=QR")
                .accept(MediaType.valueOf(MediaType.TEXT_EVENT_STREAM_VALUE))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void getStatusStreamPendingThenCompletedForQR() {
        String orderReference = "131daac9-16c6-4618-beb0-365768f37288";
        String nationalId = "123412341234";

        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/auth"))
                .withRequestBody(equalTo("""
                        {
                            "endUserIp": "%s",
                            "userVisibleData": "KkxvZ2dhIGluIHDDpSBXb25kZXJsYW5kKgo=",
                            "userVisibleDataFormat": "simpleMarkdownV1"
                        }
                        """.formatted(TEST_END_USER_IP)))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                "orderRef": "%s",
                                "autoStartToken": "bc6a7029-af6b-4b05-a02e-06f7cc5162db",
                                "qrStartToken": "%s",
                                "qrStartSecret": "0ce68cf7-7d35-4386-9bad-46ee426cadca"
                                }""".formatted(orderReference, TEST_QR_START_TOKEN))));

        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/collect"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "orderRef":"%s",
                                  "status":"%s",
                                  "hintCode":"hintCode"
                                }""".formatted(orderReference, "pending"))));

        ParameterizedTypeReference<ServerSentEvent<AuthenticationEvent>> type = new ParameterizedTypeReference<>() {
        };
        Flux<AuthenticationEvent> serverSentEvents = testClient
                .get()
                .uri("/v1/methods/swedish-bankid/authentication-events?useCase=QR")
                .header("x-envoy-external-address", TEST_END_USER_IP)
                .accept(MediaType.valueOf(MediaType.TEXT_EVENT_STREAM_VALUE))
                .exchange()
                .expectStatus().isOk()
                .returnResult(type)
                .getResponseBody()
                .mapNotNull(ServerSentEvent::data)
                .doOnNext(authenticationEvent -> {
                    if (Integer.parseInt(authenticationEvent.id()) == 3) {
                        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/collect"))
                                .willReturn(aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("""
                                                {
                                                      "orderRef":"%s",
                                                      "status":"complete",
                                                      "completionData":{
                                                       "user":{
                                                         "personalNumber":"%s",
                                                         "name":"Karl Karlsson",
                                                         "givenName":"Karl",
                                                         "surname":"Karlsson"
                                                       },
                                                       "device":{
                                                         "ipAddress":"192.168.0.1"
                                                       },
                                                       "bankIdIssueDate":"2020-02-01",
                                                       "signature":"<base64-encoded data>",
                                                       "ocspResponse":"<base64-encoded data>"
                                                     }
                                                }""".formatted(orderReference, nationalId))));
                    }
                });

        StepVerifier
                .create(serverSentEvents.takeLast(5))
                .expectNextMatches(this::assertPendingQrEvent)
                .expectNextMatches(this::assertPendingQrEvent)
                .expectNextMatches(this::assertPendingQrEvent)
                .expectNextMatches(this::assertPendingQrEvent)
                .expectNextMatches(authenticationEvent -> authenticationEvent.status().equals(COMPLETE)
                        && authenticationEvent.data() == null
                        && Integer.parseInt(authenticationEvent.id()) == 4
                        && authenticationEvent.completionData().orderReference().equals(orderReference)
                        && authenticationEvent.completionData().nationalId().equals(nationalId)
                )
                .verifyComplete();

        Flux<String> nationalIdResponse = testClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/methods/swedish-bankid/national-id")
                        .queryParam("orderReference", orderReference)
                        .build())
                .exchange()
                .expectStatus().isOk()
                .returnResult(NationalIdResponse.class)
                .getResponseBody()
                .mapNotNull(NationalIdResponse::nationalId);

        StepVerifier.create(nationalIdResponse)
                .expectNext(nationalId)
                .verifyComplete();

        testClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/methods/swedish-bankid/national-id")
                        .queryParam("orderReference", orderReference)
                        .build())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void getStatusStreamPendingThenCompletedForSameDevice() {
        String orderReference = "131daac9-16c6-4618-beb0-365768f37288";
        String nationalId = "123412341234";

        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/auth"))
                .withRequestBody(equalTo("""
                        {
                            "endUserIp": "%s",
                            "userVisibleData": "KkxvZ2dhIGluIHDDpSBXb25kZXJsYW5kKgo=",
                            "userVisibleDataFormat": "simpleMarkdownV1"
                        }
                        """.formatted(TEST_END_USER_IP)))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                "orderRef": "%s",
                                "autoStartToken": "%s",
                                "qrStartToken": "131daac9-16c6-4618-beb0-365768f37289",
                                "qrStartSecret": "0ce68cf7-7d35-4386-9bad-46ee426cadca"
                                }""".formatted(orderReference, TEST_AUTOSTART_START_TOKEN))));

        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/collect"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "orderRef":"%s",
                                  "status":"%s",
                                  "hintCode":"hintCode"
                                }""".formatted(orderReference, "pending"))));

        ParameterizedTypeReference<ServerSentEvent<AuthenticationEvent>> type = new ParameterizedTypeReference<>() {
        };
        Flux<AuthenticationEvent> serverSentEvents = testClient
                .get()
                .uri("/v1/methods/swedish-bankid/authentication-events?useCase=SAME_DEVICE")
                .header("x-envoy-external-address", TEST_END_USER_IP)
                .accept(MediaType.valueOf(MediaType.TEXT_EVENT_STREAM_VALUE))
                .exchange()
                .expectStatus().isOk()
                .returnResult(type)
                .getResponseBody()
                .mapNotNull(ServerSentEvent::data)
                .doOnNext(authenticationEvent -> {
                    if (Integer.parseInt(authenticationEvent.id()) == 3) {
                        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/collect"))
                                .willReturn(aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("""
                                                {
                                                      "orderRef":"%s",
                                                      "status":"complete",
                                                      "completionData":{
                                                       "user":{
                                                         "personalNumber":"%s",
                                                         "name":"Karl Karlsson",
                                                         "givenName":"Karl",
                                                         "surname":"Karlsson"
                                                       },
                                                       "device":{
                                                         "ipAddress":"%s"
                                                       },
                                                       "bankIdIssueDate":"2020-02-01",
                                                       "signature":"<base64-encoded data>",
                                                       "ocspResponse":"<base64-encoded data>"
                                                     }
                                                }""".formatted(orderReference, nationalId, TEST_END_USER_IP))));
                    }
                });

        StepVerifier
                .create(serverSentEvents.takeLast(5))
                .expectNextMatches(authenticationEvent -> assertPendingSameDeviceEvent(0, authenticationEvent))
                .expectNextMatches(authenticationEvent -> assertPendingSameDeviceEvent(1, authenticationEvent))
                .expectNextMatches(authenticationEvent -> assertPendingSameDeviceEvent(2, authenticationEvent))
                .expectNextMatches(authenticationEvent -> assertPendingSameDeviceEvent(3, authenticationEvent))
                .expectNextMatches(authenticationEvent -> authenticationEvent.status().equals(COMPLETE)
                        && authenticationEvent.data() == null
                        && Integer.parseInt(authenticationEvent.id()) == 4
                        && authenticationEvent.completionData().orderReference().equals(orderReference)
                        && authenticationEvent.completionData().nationalId().equals(nationalId)
                )
                .verifyComplete();

        Flux<String> nationalIdResponse = testClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/methods/swedish-bankid/national-id")
                        .queryParam("orderReference", orderReference)
                        .build())
                .exchange()
                .expectStatus().isOk()
                .returnResult(NationalIdResponse.class)
                .getResponseBody()
                .mapNotNull(NationalIdResponse::nationalId);

        StepVerifier.create(nationalIdResponse)
                .expectNext(nationalId)
                .verifyComplete();

        testClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/methods/swedish-bankid/national-id")
                        .queryParam("orderReference", orderReference)
                        .build())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void getStatusStreamPendingThenCompletedForSameDeviceWhenIpMismatch() {
        String orderReference = "131daac9-16c6-4618-beb0-365768f37288";
        String nationalId = "123412341234";

        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/auth"))
                .withRequestBody(equalTo("""
                        {
                            "endUserIp": "%s",
                            "userVisibleData": "KkxvZ2dhIGluIHDDpSBXb25kZXJsYW5kKgo=",
                            "userVisibleDataFormat": "simpleMarkdownV1"
                        }
                        """.formatted(TEST_END_USER_IP)))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                "orderRef": "%s",
                                "autoStartToken": "%s",
                                "qrStartToken": "131daac9-16c6-4618-beb0-365768f37289",
                                "qrStartSecret": "0ce68cf7-7d35-4386-9bad-46ee426cadca"
                                }""".formatted(orderReference, TEST_AUTOSTART_START_TOKEN))));

        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/collect"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "orderRef":"%s",
                                  "status":"%s",
                                  "hintCode":"hintCode"
                                }""".formatted(orderReference, "pending"))));

        ParameterizedTypeReference<ServerSentEvent<AuthenticationEvent>> type = new ParameterizedTypeReference<>() {
        };
        Flux<AuthenticationEvent> serverSentEvents = testClient
                .get()
                .uri("/v1/methods/swedish-bankid/authentication-events?useCase=SAME_DEVICE")
                .header("x-envoy-external-address", TEST_END_USER_IP)
                .accept(MediaType.valueOf(MediaType.TEXT_EVENT_STREAM_VALUE))
                .exchange()
                .expectStatus().isOk()
                .returnResult(type)
                .getResponseBody()
                .mapNotNull(ServerSentEvent::data)
                .doOnNext(authenticationEvent -> {
                    if (Integer.parseInt(authenticationEvent.id()) == 3) {
                        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/collect"))
                                .willReturn(aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("""
                                                {
                                                      "orderRef":"%s",
                                                      "status":"complete",
                                                      "completionData":{
                                                       "user":{
                                                         "personalNumber":"%s",
                                                         "name":"Karl Karlsson",
                                                         "givenName":"Karl",
                                                         "surname":"Karlsson"
                                                       },
                                                       "device":{
                                                         "ipAddress":"%s"
                                                       },
                                                       "bankIdIssueDate":"2020-02-01",
                                                       "signature":"<base64-encoded data>",
                                                       "ocspResponse":"<base64-encoded data>"
                                                     }
                                                }""".formatted(orderReference, nationalId, TEST_END_USER_IP2))));
                    }
                });

        StepVerifier
                .create(serverSentEvents.takeLast(5))
                .expectNextMatches(authenticationEvent -> assertPendingSameDeviceEvent(0, authenticationEvent))
                .expectNextMatches(authenticationEvent -> assertPendingSameDeviceEvent(1, authenticationEvent))
                .expectNextMatches(authenticationEvent -> assertPendingSameDeviceEvent(2, authenticationEvent))
                .expectNextMatches(authenticationEvent -> assertPendingSameDeviceEvent(3, authenticationEvent))
                .expectNextMatches(authenticationEvent -> authenticationEvent.status().equals(ERROR)
                        && authenticationEvent.data() == null
                        && Integer.parseInt(authenticationEvent.id()) == -1
                        && authenticationEvent.completionData() == null
                        && authenticationEvent.hintCode() == null
                )
                .verifyComplete();
    }

    @Test
    void getStatusStreamPendingThenCompletedButCacheTimeout() throws InterruptedException {
        String orderReference = "131daac9-16c6-4618-beb0-365768f37288";
        String nationalId = "123412341234";

        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/auth"))
                .withRequestBody(equalTo("""
                        {
                            "endUserIp": "%s",
                            "userVisibleData": "KkxvZ2dhIGluIHDDpSBXb25kZXJsYW5kKgo=",
                            "userVisibleDataFormat": "simpleMarkdownV1"
                        }
                        """.formatted(TEST_END_USER_IP)))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                "orderRef": "%s",
                                "autoStartToken": "bc6a7029-af6b-4b05-a02e-06f7cc5162db",
                                "qrStartToken": "%s",
                                "qrStartSecret": "0ce68cf7-7d35-4386-9bad-46ee426cadca"
                                }""".formatted(orderReference, TEST_QR_START_TOKEN))));

        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/collect"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "orderRef":"%s",
                                  "status":"%s",
                                  "hintCode":"hintCode"
                                }""".formatted(orderReference, "pending"))));

        ParameterizedTypeReference<ServerSentEvent<AuthenticationEvent>> type = new ParameterizedTypeReference<>() {
        };
        Flux<AuthenticationEvent> serverSentEvents = testClient
                .get()
                .uri("/v1/methods/swedish-bankid/authentication-events?useCase=QR")
                .header("x-envoy-external-address", TEST_END_USER_IP)
                .accept(MediaType.valueOf(MediaType.TEXT_EVENT_STREAM_VALUE))
                .exchange()
                .expectStatus().isOk()
                .returnResult(type)
                .getResponseBody()
                .mapNotNull(ServerSentEvent::data)
                .doOnNext(authenticationEvent -> {
                    if (Integer.parseInt(authenticationEvent.id()) == 0) {
                        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/collect"))
                                .willReturn(aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("""
                                                {
                                                      "orderRef":"%s",
                                                      "status":"complete",
                                                      "completionData":{
                                                       "user":{
                                                         "personalNumber":"%s",
                                                         "name":"Karl Karlsson",
                                                         "givenName":"Karl",
                                                         "surname":"Karlsson"
                                                       },
                                                       "device":{
                                                         "ipAddress":"192.168.0.1"
                                                       },
                                                       "bankIdIssueDate":"2020-02-01",
                                                       "signature":"<base64-encoded data>",
                                                       "ocspResponse":"<base64-encoded data>"
                                                     }
                                                }""".formatted(orderReference, nationalId))));
                    }
                });

        StepVerifier
                .create(serverSentEvents.takeLast(2))
                .expectNextMatches(this::assertPendingQrEvent)
                .expectNextMatches(authenticationEvent -> authenticationEvent.status().equals(COMPLETE)
                        && authenticationEvent.data() == null
                        && Integer.parseInt(authenticationEvent.id()) == 1
                        && authenticationEvent.completionData().orderReference().equals(orderReference)
                        && authenticationEvent.completionData().nationalId().equals(nationalId)
                        && authenticationEvent.hintCode() == null
                )
                .verifyComplete();

        Thread.sleep(bankIdProperties.getNationalIdCacheTTL().toMillis() + 100);

        testClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/methods/swedish-bankid/national-id")
                        .queryParam("orderReference", orderReference)
                        .build())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void getStatusStreamPendingAndFailed() {
        String orderReference = "131daac9-16c6-4618-beb0-365768f37288";

        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/auth"))
                .withRequestBody(equalTo("""
                        {
                            "endUserIp": "%s",
                            "userVisibleData": "KkxvZ2dhIGluIHDDpSBXb25kZXJsYW5kKgo=",
                            "userVisibleDataFormat": "simpleMarkdownV1"
                        }
                        """.formatted(TEST_END_USER_IP)))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                "orderRef": "%s",
                                "autoStartToken": "bc6a7029-af6b-4b05-a02e-06f7cc5162db",
                                "qrStartToken": "%s",
                                "qrStartSecret": "0ce68cf7-7d35-4386-9bad-46ee426cadca"
                                }""".formatted(orderReference, TEST_QR_START_TOKEN))));

        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/collect"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "orderRef":"%s",
                                  "status":"%s",
                                  "hintCode":"hintCode"
                                }""".formatted(orderReference, "pending"))));

        ParameterizedTypeReference<ServerSentEvent<AuthenticationEvent>> type = new ParameterizedTypeReference<>() {
        };
        Flux<AuthenticationEvent> serverSentEvents = testClient
                .get()
                .uri("/v1/methods/swedish-bankid/authentication-events?useCase=QR")
                .header("x-envoy-external-address", TEST_END_USER_IP)
                .accept(MediaType.valueOf(MediaType.TEXT_EVENT_STREAM_VALUE))
                .exchange()
                .expectStatus().isOk()
                .returnResult(type)
                .getResponseBody()
                .mapNotNull(ServerSentEvent::data)
                .doOnNext(authenticationEvent -> {
                    if (Integer.parseInt(authenticationEvent.id()) == 3) {
                        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/collect"))
                                .willReturn(aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("""
                                                {
                                                  "orderRef":"%s",
                                                  "status":"%s",
                                                  "hintCode":"hintCode"
                                                }""".formatted(orderReference, "failed"))));
                    }
                });

        StepVerifier
                .create(serverSentEvents.takeLast(5))
                .expectNextMatches(this::assertPendingQrEvent)
                .expectNextMatches(this::assertPendingQrEvent)
                .expectNextMatches(this::assertPendingQrEvent)
                .expectNextMatches(this::assertPendingQrEvent)
                .expectNextMatches(authenticationEvent -> authenticationEvent.status().equals(FAILED)
                        && authenticationEvent.data() == null
                        && Integer.parseInt(authenticationEvent.id()) == 4
                        && authenticationEvent.completionData() == null
                        && authenticationEvent.hintCode().equals("hintCode")
                )
                .verifyComplete();

        testClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/methods/swedish-bankid/national-id")
                        .queryParam("orderReference", orderReference)
                        .build())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void getStatusStreamPendingThenServerError() {
        String orderReference = "131daac9-16c6-4618-beb0-365768f37288";

        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/auth"))
                .withRequestBody(equalTo("""
                        {
                            "endUserIp": "%s",
                            "userVisibleData": "KkxvZ2dhIGluIHDDpSBXb25kZXJsYW5kKgo=",
                            "userVisibleDataFormat": "simpleMarkdownV1"
                        }
                        """.formatted(TEST_END_USER_IP)))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                "orderRef": "%s",
                                "autoStartToken": "bc6a7029-af6b-4b05-a02e-06f7cc5162db",
                                "qrStartToken": "%s",
                                "qrStartSecret": "0ce68cf7-7d35-4386-9bad-46ee426cadca"
                                }""".formatted(orderReference, TEST_QR_START_TOKEN))));

        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/collect"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "orderRef":"%s",
                                  "status":"%s",
                                  "hintCode":"hintCode"
                                }""".formatted(orderReference, "pending"))));

        ParameterizedTypeReference<ServerSentEvent<AuthenticationEvent>> type = new ParameterizedTypeReference<>() {
        };

        Flux<AuthenticationEvent> serverSentEvents = testClient
                .get()
                .uri("/v1/methods/swedish-bankid/authentication-events?useCase=QR")
                .header("x-envoy-external-address", TEST_END_USER_IP)
                .accept(MediaType.valueOf(MediaType.TEXT_EVENT_STREAM_VALUE))
                .exchange()
                .expectStatus().isOk()
                .returnResult(type)
                .getResponseBody()
                .mapNotNull(ServerSentEvent::data)
                .doOnEach(eventSignal -> {
                    if (eventSignal.get() != null && Integer.parseInt(eventSignal.get().id()) == 3) {
                        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/collect"))
                                .willReturn(serverError()));
                    }
                });

        StepVerifier
                .create(serverSentEvents.takeLast(3))
                .expectNextMatches(this::assertPendingQrEvent)
                .expectNextMatches(this::assertPendingQrEvent)
                .expectNextMatches(authenticationEvent -> authenticationEvent.status().equals(ERROR)
                        && authenticationEvent.data() == null
                        && authenticationEvent.id().equals("-1")
                        && authenticationEvent.completionData() == null
                        && authenticationEvent.hintCode() == null
                )
                .verifyComplete();

        testClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/methods/swedish-bankid/national-id")
                        .queryParam("orderReference", orderReference)
                        .build())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void getStatusStreamWhenAuthGetsServerError() {
        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/auth"))
                .withRequestBody(equalTo("""
                        {
                            "endUserIp": "%s",
                            "userVisibleData": "KkxvZ2dhIGluIHDDpSBXb25kZXJsYW5kKgo=",
                            "userVisibleDataFormat": "simpleMarkdownV1"
                        }
                        """.formatted(TEST_END_USER_IP)))
                .willReturn(serverError()));

        ParameterizedTypeReference<ServerSentEvent<AuthenticationEvent>> type = new ParameterizedTypeReference<>() {
        };

        Flux<AuthenticationEvent> serverSentEvents = testClient
                .get()
                .uri("/v1/methods/swedish-bankid/authentication-events?useCase=QR")
                .header("x-envoy-external-address", TEST_END_USER_IP)
                .accept(MediaType.valueOf(MediaType.TEXT_EVENT_STREAM_VALUE))
                .exchange()
                .expectStatus().isOk()
                .returnResult(type)
                .getResponseBody()
                .mapNotNull(ServerSentEvent::data);

        StepVerifier
                .create(serverSentEvents)
                .expectNextMatches(authenticationEvent -> authenticationEvent.status().equals(ERROR)
                        && authenticationEvent.data() == null
                        && authenticationEvent.id().equals("-1")
                        && authenticationEvent.completionData() == null
                        && authenticationEvent.hintCode() == null
                )
                .verifyComplete();
    }

    @Test
    void getNationalIdForNonExistingOrderReference() {
        testClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/methods/swedish-bankid/national-id")
                        .queryParam("orderReference", "any value")
                        .build())
                .exchange()
                .expectStatus().isNotFound();
    }

    private boolean assertPendingQrEvent(AuthenticationEvent authenticationEvent) {
        String prefix = String.join(".", "bankid", TEST_QR_START_TOKEN, authenticationEvent.id());
        return authenticationEvent.status().equals(AuthenticationStatus.PENDING) && authenticationEvent.data().startsWith(prefix) && authenticationEvent.hintCode().equals("hintCode");
    }

    private boolean assertPendingQrEvent(int seq, AuthenticationEvent authenticationEvent) {
        return authenticationEvent.id().equals(String.valueOf(seq)) && assertPendingQrEvent(authenticationEvent);
    }

    private boolean assertPendingSameDeviceEvent(int seq, AuthenticationEvent authenticationEvent) {
        return authenticationEvent.id().equals(String.valueOf(seq)) && authenticationEvent.data().equals(TEST_AUTOSTART_START_TOKEN) && authenticationEvent.hintCode().equals("hintCode");
    }
}
