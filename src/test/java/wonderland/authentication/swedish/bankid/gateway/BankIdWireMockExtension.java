package wonderland.authentication.swedish.bankid.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import lombok.Getter;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.junit.jupiter.api.Assertions.fail;

@Getter
@Configuration
public class BankIdWireMockExtension implements BeforeAllCallback, AfterEachCallback, AfterAllCallback {

    private static final int wireMockPort = 11112;
    @Autowired
    public ConfigurableEnvironment configurableEnvironment;
    private WireMockServer wireMockServer;

    @Override
    public void beforeAll(ExtensionContext context) {
        wireMockServer = new WireMockServer(wireMockPort);
        wireMockServer.start();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (!wireMockServer.findAllUnmatchedRequests().isEmpty()) {
            fail("There are stubbed but unmatched http requests during the test.");
        }
        this.wireMockServer.resetAll();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        wireMockServer.stop();
        wireMockServer.shutdown();
    }

    public String getBaseUrl() {
        return "http://localhost:" + wireMockPort;
    }

    public void stubTokenEndpoint() {
        wireMockServer
                .stubFor(post("/token")
                        .willReturn(ok()
                                .withBody("{\"id_token\":\"" + mockIdToken() + "\"}")));
    }

    private String mockIdToken() {
        String header = Base64.getEncoder().encodeToString("""
                {"alg":"RS256","kid":"mockkey","typ":"JWT"}""".getBytes());
        String payload = Base64.getEncoder().encodeToString("""
                {"aud":"clientId","azp":"service-jwt-customer-access@mockserver","email":"service-jwt-customer-access@tmockserver","email_verified":true,"exp":1635278061,"iat":1635274461,"iss":"https://mockserver","sub":"105365709839969680659"}""".getBytes());
        String signature = "mock-signature";
        return header + "." + payload + "." + signature;
    }
}
