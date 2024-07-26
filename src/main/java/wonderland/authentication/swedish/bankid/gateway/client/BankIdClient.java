package wonderland.authentication.swedish.bankid.gateway.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import wonderland.authentication.swedish.bankid.gateway.type.AuthenticationResponse;
import wonderland.authentication.swedish.bankid.gateway.type.CollectResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Level;

@Component
@Slf4j
public class BankIdClient {

    private static final String AUTH_TEXT_BASE64 = Base64.getEncoder().encodeToString("""
            *Login to Wonderland*
            """.getBytes(StandardCharsets.UTF_8));

    private final WebClient bankIdWebClient;

    public BankIdClient(WebClient bankIdWebClient) {
        this.bankIdWebClient = bankIdWebClient;
    }

    public Mono<AuthenticationResponse> auth(String endUserIp) {
        return bankIdWebClient.post()
                .uri("/rp/v6.0/auth")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                            "endUserIp": "%s",
                            "userVisibleData": "%s",
                            "userVisibleDataFormat": "simpleMarkdownV1"
                        }
                        """.formatted(endUserIp, AUTH_TEXT_BASE64))
                .retrieve()
                .bodyToMono(AuthenticationResponse.class)
                .doOnNext(authenticationResponse -> log.info("Started auth {} for ip {}", authenticationResponse, endUserIp))
                .doOnError(authenticationResponse -> log.error("Failed to start auth for ip {}", endUserIp));
    }

    public Mono<CollectResponse> collect(String orderReference) {
        return bankIdWebClient.post()
                .uri("/rp/v6.0/collect")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"orderRef": "%s"}
                        """.formatted(orderReference))
                .retrieve()
                .bodyToMono(CollectResponse.class)
                .log("BankIdClient.collect", Level.FINE, SignalType.ON_NEXT)
                .log("BankIdClient.collect", Level.WARNING, SignalType.ON_ERROR);
    }
}
