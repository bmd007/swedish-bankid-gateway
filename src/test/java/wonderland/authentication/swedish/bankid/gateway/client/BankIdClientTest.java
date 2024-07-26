package wonderland.authentication.swedish.bankid.gateway.client;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import wonderland.authentication.swedish.bankid.gateway.config.BankIdProperties;
import wonderland.authentication.swedish.bankid.gateway.config.WebClientConfig;
import wonderland.authentication.swedish.bankid.gateway.type.AuthenticationResponse;
import wonderland.authentication.swedish.bankid.gateway.type.CollectResponse;

import java.io.IOException;
import java.net.URI;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.badRequest;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BankIdClientTest {

    public static final String END_USER_IP = "194.168.2.25";
    @RegisterExtension
    static WireMockExtension wireMockExtension;

    static {
        try {
            wireMockExtension = WireMockExtension.newInstance()
                    .options(
                            wireMockConfig()
                                    .dynamicHttpsPort()
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

    private BankIdClient bankIdClient;

    @BeforeEach
    void setUp() throws Exception {
        final WebClientConfig webClientConfig = new WebClientConfig();
        BankIdProperties properties = BankIdProperties.builder()
                .baseUrl(URI.create(wireMockExtension.baseUrl()))
                .trustStore(LOCALHOST_CERTIFICATE_CONTENT)
                .keyStoreCertificate(RELAYING_PARTY_CERTIFICATE_CONTENT)
                .keyStorePrivateKey(RELAYING_PARTY_PRIVATE_KEY_CONTENT)
                .build();
        final WebClient webClient = webClientConfig.bankIdWebClient(properties, webClientConfig.defaultHttpConnector(properties));
        bankIdClient = new BankIdClient(webClient);
    }

    @AfterEach
    void afterEach() {
        wireMockExtension.resetAll();
    }

    @Test
    void auth() {
        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/auth"))
                .withRequestBody(equalToJson("""
                        {
                            "endUserIp": "%s",
                            "userVisibleData": "KkxvZ2dhIGluIHDDpSBXb25kZXJsYW5kKgo=",
                            "userVisibleDataFormat": "simpleMarkdownV1"
                        }
                        """.formatted(END_USER_IP)))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                "orderRef": "a5bd28bd-a6d5-47fb-bdb3-94faccb90d7d",
                                "autoStartToken": "bc6a7029-af6b-4b05-a02e-06f7cc5162db",
                                "qrStartToken": "ef2fdf1a-ba96-440e-bf05-e122b43db754",
                                "qrStartSecret": "0ce68cf7-7d35-4386-9bad-46ee426cadca"
                                }""")));
        final AuthenticationResponse response = bankIdClient.auth(END_USER_IP).block();
        assertThat(response.orderRef()).isEqualTo("a5bd28bd-a6d5-47fb-bdb3-94faccb90d7d");
        assertThat(response.autoStartToken()).isEqualTo("bc6a7029-af6b-4b05-a02e-06f7cc5162db");
        assertThat(response.qrStartToken()).isEqualTo("ef2fdf1a-ba96-440e-bf05-e122b43db754");
        assertThat(response.qrStartSecret()).isEqualTo("0ce68cf7-7d35-4386-9bad-46ee426cadca");
    }

    @Test
    void authWhen500FromBankId() {
        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/auth"))
                .withRequestBody(equalToJson("""
                        {
                            "endUserIp": "%s",
                            "userVisibleData": "KkxvZ2dhIGluIHDDpSBXb25kZXJsYW5kKgo=",
                            "userVisibleDataFormat": "simpleMarkdownV1"
                        }
                        """.formatted(END_USER_IP)))
                .willReturn(serverError()));
        WebClientResponseException e = assertThrows(WebClientResponseException.class, () -> bankIdClient.auth(END_USER_IP).block());
        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void authWhen400FromBankId() {
        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/auth"))
                .withRequestBody(equalToJson("""
                        {
                            "endUserIp": "%s",
                            "userVisibleData": "KkxvZ2dhIGluIHDDpSBXb25kZXJsYW5kKgo=",
                            "userVisibleDataFormat": "simpleMarkdownV1"
                        }
                        """.formatted(END_USER_IP)))
                .willReturn(badRequest()));
        WebClientResponseException e = assertThrows(WebClientResponseException.class, () -> bankIdClient.auth(END_USER_IP).block());
        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "failed"})
    void collectStatus(String status) {
        String orderReference = "131daac9-16c6-4618-beb0-365768f37288";
        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/collect"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "orderRef":"%s",
                                  "status":"%s",
                                  "hintCode":"hintCode"
                                }""".formatted(orderReference, status))));
        final CollectResponse response = bankIdClient.collect(orderReference).block();
        assertThat(response.orderRef()).isEqualTo(orderReference);
        assertThat(response.status()).isEqualTo(CollectResponse.Status.fromString(status));
        assertThat(response.hintCode()).isEqualTo("hintCode");
    }

    @Test
    void collectCompleteStatus() {
        String orderReference = "131daac9-16c6-4618-beb0-365768f37288";
        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/collect"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "orderRef":"%s",
                                    "status":"complete",
                                    "completionData":{
                                     "user":{
                                       "personalNumber":"190000000000",
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
                                 }""".formatted(orderReference))));
        final CollectResponse response = bankIdClient.collect(orderReference).block();
        assertThat(response.orderRef()).isEqualTo(orderReference);
        assertThat(response.status()).isEqualTo(CollectResponse.Status.COMPLETE);
        assertThat(response.hintCode()).isNull();
        assertThat(response.completionData().user().personalNumber()).isEqualTo("190000000000");
        assertThat(response.completionData().device().ipAddress()).isEqualTo("192.168.0.1");
    }

    @Test
    void collectWhen500FromBankId() {
        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/collect"))
                .willReturn(serverError()));
        WebClientResponseException e = assertThrows(WebClientResponseException.class, () -> bankIdClient.collect("someOrderRef").block());
        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void collectWhen400FromBankId() {
        wireMockExtension.stubFor(post(urlPathEqualTo("/rp/v6.0/collect"))
                .willReturn(badRequest()));
        WebClientResponseException e = assertThrows(WebClientResponseException.class, () -> bankIdClient.collect("someOrderRef").block());
        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static final String LOCALHOST_CERTIFICATE_CONTENT = """
            -----BEGIN CERTIFICATE-----
            MIICpDCCAYwCCQDBo4kpmxQzoDANBgkqhkiG9w0BAQsFADAUMRIwEAYDVQQDDAls
            b2NhbGhvc3QwHhcNMjMwNjEyMDg0MjM1WhcNMzMwNjA5MDg0MjM1WjAUMRIwEAYD
            VQQDDAlsb2NhbGhvc3QwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDJ
            ioOJ6CUQwoofohsXcT5nwFSz7yIg6wqYqafBoRbfiDJ3kMPTksFpqgE5nAWnE0Q9
            vju9P/XiUxLHoHDwNHys+UpWSUOQhcawAUQHfA04JFq3VTlibh+hdEoaBxz62woh
            Sw8fhxcn121uRa4IDisAYBzmj7//9tLB5BWLLYv8KM99FCtDw5qpKvz0XS0zbvNy
            tjt70BFY8oiDd+qCJH5dBJ7BeegdfddNCEzmVuhMyfO6QSmiubdRz5y3Qr6SzvFe
            LlgnC0XcOELH8B7VUzkfmLWNVCcgDGmUZkCvMVgjtkSR0W21zJo7WVpa/n4CWNtN
            NyiDF9Dzo3XZ7ZkQor+nAgMBAAEwDQYJKoZIhvcNAQELBQADggEBAJofeVRckOY8
            j9uCvAZzOlJ2mFrlUQEaiIxW8qjrmsASV3NW+vbv1rMh8lgYr+cWCJJQRVb0G5Bm
            u1UzXZaw2bThMDYjKSnzgEUQyt5Da9hm8UPMDFtnZDYY3Pe4Mu7X6hPmQN326UdJ
            PhTmKkBG8uReUZcDNMjr3ztkgoU9WA3IxHJix6AAM5nQbSO9jI9525UuZZdFOqkg
            tEMDliJmLaXxiuZYzT5U7IhD+rHouHM06Ni+YbOtKphdzj2PicMiXVuTqxGj1ydm
            +YgrkuHZX1+eOK+p/cZ27bA6gjKTb62XDDhcS3f3cFu4rKxZiMAQ4qoKCvfUaQCE
            wIYckGWJTTo=
            -----END CERTIFICATE-----
            """;
    private static final String RELAYING_PARTY_PRIVATE_KEY_CONTENT = """
            -----BEGIN PRIVATE KEY-----
            MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC+2slmnq1880Cj
            QFuILu9LrGC0cUZZOoZ9iP3wJVXw6x6gBwOee3CHPwMfUPftVimJJAZVCDpg0Ffz
            o+D4k4hqNyZxkOmkWZdj8NT3wWN1A8AxgVMNOtui5TcokLatqunmi3amo1q0Br+I
            XVrp4wWJbHpexydtNMfBzNaYBEfiGz/z2j5TSmz3HNF8RTkwI2ZGXkVgrwrafuxd
            avkkbIK4vp8HQuAUUs58fit+TJpCskMzNBbTAla6ONTWWoOXuv/amACLWfv0zaSw
            0yE5VcOsIsgVZqMQXhaLSO3O19uwZ4zAL6uvd8YlLVUIRHVR4ldPRrSkDR+AxZPm
            7kylf5dlAgMBAAECggEBAKcBxKMrzxw7sCAqvO3aREOvv5+mT6zc7bsEGVH/GckL
            tWzLNnuEAOAYX58Ttx/T+dyrzW6zGIKHTa9BhCeA0io/wEA4UnsiertVjsaOT32h
            npizVf2Jt5A/i5jPqUsm+C5pc5Y187T9ArVNG7T2l229owc0tmd1fCc2G8JCXLSZ
            KMpR5Reh423UlyhkakODhVxEHGMBD+PtvbJmOtWK6hoKnE/det0xXlS30Fjn0OW9
            4wKJ8fyGPoymEKH+K+yD2qGTInoMep7BlDLA7f2mh7TXP98kFVFv/XfpM/AC/r7+
            iZTz1743sCyWBhhE+48VWusKLafTFUaktQTtK8960bECgYEA4z7TTFgl3XTW1jNQ
            1imoX5krwj5/P2V+JGb6nJikprWQY2tE95jApmu+VgTShVXJPh2K69PF7p5Lflgz
            Qw9LxfU94wDoY9x10z8YRuQEeUmIKdve7G/JvMi+6odQ3HvJW5C/odybcIulOVwJ
            6jrDGYVqGUDPj6V47MZBLy8hUdcCgYEA1wEnFqZ087epk85ANX47a2J/4QGFlFTa
            61o3zm6UGMWTFx5irLUDJpeIsKuZAAbuhCCevN5hTRSIMkNOPaVqZBnSlsJa7pes
            LI+5Bzq1veXU5YM9TknY+cjv/Q9kpc9eiIVRcrfGnGWrF/v1mjtY+nhTqsCtVb7v
            duQ9+giK8SMCgYBHSJfjpOsL0vDpdGNxKtQkWNn/Lref3Wh6ZstKgB92JBJM/YM+
            3+3exoGIXi7joItmLsI7Q80dEw6/bU93Q78TM+Db6pb7bFaRk4M8CZ7VpRlPeCcv
            p8lyrM4mp5fX8gSx8nAKiDdCUKvdmF+L2C8HPHCRx2DUwKV0MKSV9oTPNQKBgCq5
            WOogU7cmdPUhFBNNLUOOhDjTE5dBMWt5NwO3Z4hwomUCrbsCEUk27Xul7bZaqkTp
            MoH3csBdZx8NzttjJnwTwYwhvO4Sh60nNi5glULSC/c7mBAZjps8OaxAxdBJH9Dj
            JSc1q6ribRhMAicOygSjqoSQ2yDh2zX17vbjgbCfAoGAceQskQqCBXRQKG1nUpjk
            SWc4xTFul1I4bxk2ooCIs/TWS5X/7rPIXi8IYVvCH4bBqE3Ktyru9RJeKk5u6p7k
            3owGCN8yvW4DRfDGVCMOwfwxhv3V3ma75iE12yj6YRzNp8Bk+wMAwuN+TT4Lrp9d
            Ujmpiu8MJUe843hC8tDU++M=
            -----END PRIVATE KEY-----
            """;

    private static final String RELAYING_PARTY_CERTIFICATE_CONTENT = """
            -----BEGIN CERTIFICATE-----
            MIICpDCCAYwCCQCIfpZE1rxY1jANBgkqhkiG9w0BAQsFADAUMRIwEAYDVQQDDAls
            b2NhbGhvc3QwHhcNMjMwNjEyMDg1NzEyWhcNMzMwNjA5MDg1NzEyWjAUMRIwEAYD
            VQQDDAlsb2NhbGhvc3QwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQC+
            2slmnq1880CjQFuILu9LrGC0cUZZOoZ9iP3wJVXw6x6gBwOee3CHPwMfUPftVimJ
            JAZVCDpg0Ffzo+D4k4hqNyZxkOmkWZdj8NT3wWN1A8AxgVMNOtui5TcokLatqunm
            i3amo1q0Br+IXVrp4wWJbHpexydtNMfBzNaYBEfiGz/z2j5TSmz3HNF8RTkwI2ZG
            XkVgrwrafuxdavkkbIK4vp8HQuAUUs58fit+TJpCskMzNBbTAla6ONTWWoOXuv/a
            mACLWfv0zaSw0yE5VcOsIsgVZqMQXhaLSO3O19uwZ4zAL6uvd8YlLVUIRHVR4ldP
            RrSkDR+AxZPm7kylf5dlAgMBAAEwDQYJKoZIhvcNAQELBQADggEBAG0RECu+Hd7W
            Hf/fvTJ0VcAkpy9mkJKOIPNy5Cs7F4HDLdUgx6v9a9nI0r2xMDX3OO0jVofJAgAt
            s1EmNz1oheghrcCeB+p0+QCsVqqSofnbNJDUn8mo4K4iCQg2qRhBmUa9QJvcc30r
            7DBHh/JNkhqa6eC5Wnw3xBUO66vM4NLr60nijrOzQeSQOefu9IY9Sidlv/c9QOa2
            4qaasUxNqY8pUffHsxBvd0oF44h35nMR2jWZEeDW08qpNojHXn+hBIt1oOR2dftV
            YPMPpHxJcJoO/scKV4/HL6MxqQ8NWBV1S1Zs9BlNnUWA7TYIaIv1lk/cwVa8gLbR
            AatgatdX+t8=
            -----END CERTIFICATE-----
            """;
}
