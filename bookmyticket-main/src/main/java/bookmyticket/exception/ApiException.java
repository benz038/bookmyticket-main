package bookmyticket.exception;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException seatUnavailable(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException paymentFailed(String message) {
        return new ApiException(HttpStatus.PAYMENT_REQUIRED, message);
    }
}
