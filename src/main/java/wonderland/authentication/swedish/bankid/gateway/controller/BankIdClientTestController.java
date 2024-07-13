package wonderland.authentication.swedish.bankid.gateway.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import wonderland.authentication.swedish.bankid.gateway.client.bankid.BankIdClient;
import wonderland.authentication.swedish.bankid.gateway.client.type.AuthenticationResponse;
import wonderland.authentication.swedish.bankid.gateway.client.type.CollectResponse;

@Slf4j
@RestController
@RequestMapping("/v1/methods/swedish-bankid/client-test")
public class BankIdClientTestController {

    private final BankIdClient bankIdClient;

    public BankIdClientTestController(BankIdClient bankIdClient) {
        this.bankIdClient = bankIdClient;
    }

    @GetMapping("/auth")
    Mono<AuthenticationResponse> checkAuth(@RequestParam String ip) {
        return bankIdClient.auth(ip);
    }

    @GetMapping("/collect")
    Mono<CollectResponse> checkCollect(@RequestParam String orderReference) {
        return bankIdClient.collect(orderReference);
    }
}
