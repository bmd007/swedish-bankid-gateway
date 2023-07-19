package wonderland.authentication.swedish.bankid.gateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import wonderland.authentication.swedish.bankid.gateway.client.bankid.BankIdClient;
import wonderland.authentication.swedish.bankid.gateway.client.type.AuthenticationResponse;
import wonderland.authentication.swedish.bankid.gateway.client.type.CollectResponse;
import wonderland.authentication.swedish.bankid.gateway.entity.CompletedAuthentication;
import wonderland.authentication.swedish.bankid.gateway.repository.CompletedAuthenticationRepository;
import wonderland.authentication.swedish.bankid.gateway.type.AuthenticationEvent;
import wonderland.authentication.swedish.bankid.gateway.type.UseCase;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

import static wonderland.authentication.swedish.bankid.gateway.type.UseCase.QR;
import static wonderland.authentication.swedish.bankid.gateway.type.AuthenticationStatus.COMPLETE;
import static wonderland.authentication.swedish.bankid.gateway.type.AuthenticationStatus.ERROR;
import static wonderland.authentication.swedish.bankid.gateway.type.AuthenticationStatus.FAILED;

@Slf4j
@Service
public class BankIdService {

    private static final String HMAC_SHA_256 = "HmacSHA256";

    private final CompletedAuthenticationRepository completedAuthenticationRepository;
    private final BankIdClient bankIdClient;
    private final Mac mac;

    public BankIdService(CompletedAuthenticationRepository completedAuthenticationRepository, BankIdClient bankIdClient) throws NoSuchAlgorithmException {
        this.completedAuthenticationRepository = completedAuthenticationRepository;
        mac = Mac.getInstance(HMAC_SHA_256);
        this.bankIdClient = bankIdClient;
    }

    public Mono<String> getNationalId(String orderReference) {
        return completedAuthenticationRepository.getNationalId(orderReference)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .doOnNext(nationalId -> log.info("national id {} found for order reference {}", nationalId, orderReference))
                .doOnError(throwable -> log.error("Failed to get national id for order reference {}", orderReference, throwable));
    }

    public Flux<AuthenticationEvent> authenticationEventStream(String endUserIp, UseCase useCase) {
        return bankIdClient.auth(endUserIp)
                .flatMapMany(authRsp ->
                        Flux.interval(Duration.ZERO, Duration.ofSeconds(1))
                                .flatMap(sequence -> bankIdClient.collect(authRsp.orderRef())
                                        .filter(collectResponse -> ipCheck(useCase, endUserIp, collectResponse))
                                        .map(colRsp -> createAuthenticationEvent(authRsp, sequence, colRsp, useCase))
                                        .switchIfEmpty(Mono.just(AuthenticationEvent.error()))
                                )
                )
                .doOnError(throwable -> log.error("unexpected error in the stream", throwable))
                .onErrorReturn(AuthenticationEvent.error())
                .delayUntil(this::saveCompletedAuthenticationData)
                .takeUntil(authenticationEvent -> authenticationEvent.status() == COMPLETE || authenticationEvent.status() == FAILED || authenticationEvent.status() == ERROR)
                .take(Duration.ofMinutes(2L));
    }

    private Mono<Void> saveCompletedAuthenticationData(AuthenticationEvent authenticationEvent) {
            if (authenticationEvent.status() == COMPLETE) {
                String orderReference = authenticationEvent.completionData().orderReference();
                String nationalId = authenticationEvent.completionData().nationalId();
                return completedAuthenticationRepository.save(orderReference, nationalId).then();
            }
            return Mono.empty().then();
    }

    private boolean ipCheck(UseCase useCase, String endUserIp, CollectResponse collectResponse) {
        if (useCase == QR) {
            return true;
        }
        if (collectResponse.status() != CollectResponse.Status.COMPLETE){
            return true;
        }
        if(!collectResponse.completionData().device().ipAddress().equals(endUserIp)){
            log.warn("End user IP mismatch: end user IP {}  vs. device IP reported by BankId {}", endUserIp, collectResponse.completionData().device().ipAddress());
            return false;
        }
        return true;
    }

    private AuthenticationEvent createAuthenticationEvent(AuthenticationResponse authRsp, Long sequence, CollectResponse colRsp, UseCase authenticationMode) {
        final String sequenceString = String.valueOf(sequence);
        return switch (colRsp.status()) {
            case PENDING ->
                    switch (authenticationMode) {
                        case QR -> createQrPendingEvent(sequenceString, authRsp.qrStartToken(), authRsp.qrStartSecret(), colRsp.hintCode());
                        case SAME_DEVICE -> AuthenticationEvent.pending(sequenceString, authRsp.autoStartToken(), colRsp.hintCode());
            };
            case COMPLETE -> createCompletedAuthenticationEvent(colRsp, sequenceString);
            case FAILED -> AuthenticationEvent.failed(sequenceString, colRsp.hintCode());
        };
    }

    private AuthenticationEvent createCompletedAuthenticationEvent(CollectResponse colRsp, String sequenceString) {
        String orderReference = colRsp.orderRef();
        String nationalId = colRsp.completionData().user().personalNumber();
        CompletedAuthentication completionData = new CompletedAuthentication(orderReference, nationalId);
        return AuthenticationEvent.complete(sequenceString, completionData);
    }

    private AuthenticationEvent createQrPendingEvent(String sequence, String qrStartToken, String qrStartSecret, String hintCode) {
        try {
            mac.init(new SecretKeySpec(qrStartSecret.getBytes(StandardCharsets.US_ASCII), HMAC_SHA_256));
        } catch (InvalidKeyException e) {
            log.error("Invalid secret: {}", qrStartSecret, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid secret", e);
        }
        mac.update(sequence.getBytes(StandardCharsets.US_ASCII));

        String qrAuthCode = String.format("%064x", new BigInteger(1, mac.doFinal()));

        String qrData = String.join(".", "bankid", qrStartToken, sequence, qrAuthCode);
        return AuthenticationEvent.pending(sequence, qrData, hintCode);
    }
}
