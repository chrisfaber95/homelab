package dev.homelab.inbox.proxy;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * What the Python server answers for a path that is not a route of the API: 404 {@code {"error": "Not found."}}, also
 * for a wrong method on a known path (Python has no 405 there). Without this, Spring's own error body would reach the page.
 */
@RestControllerAdvice
public class ApiErrorHandler {

    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    public void notFound(HttpServletResponse response) throws IOException {
        Upstream.write(response, Upstream.plainError(404, "Not found."));
    }
}
