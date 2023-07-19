package wonderland.authentication.swedish.bankid.gateway.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import wonderland.authentication.swedish.bankid.gateway.service.BankIdService;
import wonderland.authentication.swedish.bankid.gateway.type.AuthenticationEvent;
import wonderland.authentication.swedish.bankid.gateway.type.UseCase;
import wonderland.authentication.swedish.bankid.gateway.type.NationalIdResponse;

@Slf4j
@RestController
@RequestMapping("/v1/methods/swedish-bankid/")
public class BankIdController {

    private final BankIdService bankIdService;

    public BankIdController(BankIdService bankIdService) {
        this.bankIdService = bankIdService;
    }

    @GetMapping(value = "/authentication-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AuthenticationEvent>> getAuthenticationEvents(@RequestHeader("x-envoy-external-address") String endUserIp,
                                                                              @RequestParam UseCase useCase) {
        return bankIdService.authenticationEventStream(endUserIp, useCase)
                .map(this::toServerSentEvent);
    }

    private ServerSentEvent<AuthenticationEvent> toServerSentEvent(AuthenticationEvent authenticationEvent) {
        return ServerSentEvent.<AuthenticationEvent>builder()
                .id(authenticationEvent.id())
                .event(authenticationEvent.status().toString())
                .data(authenticationEvent)
                .build();
    }

    @GetMapping("/national-id")
    public Mono<NationalIdResponse> getNationalId(@RequestParam String orderReference) {
        return bankIdService.getNationalId(orderReference).map(NationalIdResponse::new);
    }
}
