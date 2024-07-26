package wonderland.authentication.swedish.bankid.gateway.type;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthenticationResponse(String orderRef, String autoStartToken, String qrStartToken,
                                     String qrStartSecret) {
}
