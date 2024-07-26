package wonderland.authentication.swedish.bankid.gateway.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;

import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CollectResponse(
        @NotBlank
        String orderRef,
        @NotBlank
        Status status,
        String hintCode,
        CompletionData completionData) {

    public enum Status {
        PENDING,
        COMPLETE,
        FAILED;
        @JsonCreator
        public static Status fromString(String value) {
            return Status.valueOf(value.toUpperCase());
        }
    }

    public record CompletionData(User user, Device device, String risk) {
    }

    public record User(String personalNumber) {
    }

    public record Device(String ipAddress) {
    }
}







