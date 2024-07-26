package wonderland.authentication.swedish.bankid.gateway.type;

public record AuthenticationEvent(String id, AuthenticationStatus status, String data,
                                  CompletedAuthentication completionData, String hintCode) {
    public static AuthenticationEvent error() {
        return new AuthenticationEvent("-1", AuthenticationStatus.ERROR, null, null, null);
    }

    public static AuthenticationEvent pending(String sequence, String data, String hintCode) {
        return new AuthenticationEvent(sequence, AuthenticationStatus.PENDING, data, null, hintCode);
    }

    public static AuthenticationEvent failed(String sequence, String hintCode) {
        return new AuthenticationEvent(sequence, AuthenticationStatus.FAILED, null, null, hintCode);
    }

    public static AuthenticationEvent complete(String sequence, CompletedAuthentication completionData) {
        return new AuthenticationEvent(sequence, AuthenticationStatus.COMPLETE, null, completionData, null);
    }
}
