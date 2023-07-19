package wonderland.authentication.swedish.bankid.gateway.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;
import wonderland.authentication.swedish.bankid.gateway.config.BankIdProperties;

import java.util.logging.Level;

@Slf4j
@Repository
public class CompletedAuthenticationRepository {

    private final BankIdProperties bankIdProperties;
    private final ReactiveRedisOperations<String, String> redisOperations;

    public CompletedAuthenticationRepository(BankIdProperties bankIdProperties, ReactiveRedisOperations<String, String> redisOperations) {
        this.bankIdProperties = bankIdProperties;
        this.redisOperations = redisOperations;
    }

    public Mono<Boolean> save(String orderReference, String nationalId) {
        return redisOperations.opsForValue()
                .set(orderReference, nationalId, bankIdProperties.getNationalIdCacheTTL())
                .filter(isSaved -> {
                    if (Boolean.TRUE.equals(isSaved)) {
                        log.info("Saved completed authentication in redis. Data: {}:{}", orderReference, nationalId);
                    }
                    return isSaved;
                })
                .switchIfEmpty(Mono.error(new IllegalStateException("Failed to save in redis. Data: %s".formatted(orderReference))))
                .log("CompletedAuthenticationRepository.save", Level.WARNING, SignalType.ON_ERROR)
                .publishOn(Schedulers.boundedElastic())
                .doOnError(throwable -> redisOperations.delete(orderReference).subscribe());
    }

    public Mono<String> getNationalId(String orderReference) {
        return redisOperations.opsForValue().getAndDelete(orderReference);
    }
}
