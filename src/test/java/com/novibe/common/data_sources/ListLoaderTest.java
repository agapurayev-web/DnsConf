package com.novibe.common.data_sources;

import com.novibe.common.base_structures.HostsLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings({"preview", "unchecked"})
class ListLoaderTest {
    private static final String URL = "https://source.example/hosts";
    private HttpClient client;
    private ListLoader<HostsLine> loader;

    @BeforeEach
    void setUp() {
        client = mock(HttpClient.class);
        loader = new ListLoader<>() {
            protected HostsLine toObject(HostsLine line) { return line; }
            protected String listType() { return "Override"; }
            protected Predicate<HostsLine> filterRelatedLines() { return HostsLine::hasIpAndDomain; }
            void pauseBeforeRetry(int attempt) { }
        };
        loader.setClient(client);
    }

    private HttpResponse<String> response(int status, String body, String contentType) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(
                Map.of("Content-Type", List.of(contentType)), (name, value) -> true));
        return response;
    }

    private HttpResponse<String> validResponse() {
        return response(200, "203.0.113.1 example.com\n", "text/plain");
    }

    @Test
    void retriesTemporaryHttpErrorsAndParsesSuccess() throws Exception {
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response(503, "Unavailable", "text/plain"),
                        response(502, "Bad gateway", "text/plain"), validResponse());
        assertEquals("example.com", loader.fetchWebsites(List.of(URL)).getFirst().domain());
        verify(client, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void retriesConnectionTimeout() throws Exception {
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpConnectTimeoutException("HTTP connect timed out"))
                .thenReturn(validResponse());
        assertEquals(1, loader.fetchWebsites(List.of(URL)).size());
        verify(client, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void permanentNetworkFailureIncludesUrlAndOriginalCause() throws Exception {
        var timeout = new HttpConnectTimeoutException("HTTP connect timed out");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(timeout);
        var failure = assertThrows(StructuredTaskScope.FailedException.class,
                () -> loader.fetchWebsites(List.of(URL)));
        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains(URL));
        assertSame(timeout, failure.getCause().getCause());
        verify(client, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void persistent503FailsAfterThreeAttempts() throws Exception {
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response(503, "Unavailable", "text/plain"));
        var failure = assertThrows(StructuredTaskScope.FailedException.class,
                () -> loader.fetchWebsites(List.of(URL)));
        assertTrue(failure.getCause().getMessage().contains("HTTP 503"));
        assertTrue(failure.getCause().getMessage().contains(URL));
        verify(client, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void doesNotRetry404() throws Exception {
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response(404, "Missing", "text/plain"));
        assertThrows(StructuredTaskScope.FailedException.class, () -> loader.fetchWebsites(List.of(URL)));
        verify(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void rejectsEmptyHtmlAndUnparseableLists() throws Exception {
        for (String body : List.of("", "<html>Error</html>", "# comments only\n")) {
            when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(response(200, body, "text/plain"));
            assertThrows(RuntimeException.class, () -> loader.fetchWebsites(List.of(URL)));
        }
    }

    @Test
    void doesNotReturnPartialListsWhenOneSourceFails() throws Exception {
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> ((HttpRequest) invocation.getArgument(0)).uri().toString().equals(URL)
                        ? validResponse() : response(503, "Unavailable", "text/plain"));
        assertThrows(StructuredTaskScope.FailedException.class,
                () -> loader.fetchWebsites(List.of(URL, "https://broken.example/hosts")));
    }

    @Test
    void doesNotRetryInterruptedRequests() throws Exception {
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("cancelled"));
        var failure = assertThrows(StructuredTaskScope.FailedException.class,
                () -> loader.fetchWebsites(List.of(URL)));
        assertInstanceOf(InterruptedException.class, failure.getCause());
        verify(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }
}
