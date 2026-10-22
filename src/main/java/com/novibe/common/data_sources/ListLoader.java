package com.novibe.common.data_sources;

import com.novibe.common.base_structures.HostsLine;
import com.novibe.common.exception.UserInputException;
import com.novibe.common.util.DataParser;
import com.novibe.common.util.Log;
import lombok.Cleanup;
import lombok.Setter;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Setter(onMethod_ = @Autowired)
public abstract class ListLoader<T> {

    private HttpClient client;

    protected abstract T toObject(HostsLine hostsLine);

    protected abstract String listType();

    protected abstract Predicate<HostsLine> filterRelatedLines();

    @SneakyThrows
    @SuppressWarnings("preview")
    public List<T> fetchWebsites(List<String> urls) {
        @Cleanup var scope = StructuredTaskScope.open();
        List<StructuredTaskScope.Subtask<String>> requests = new ArrayList<>();
        urls.stream()
                .map(url -> scope.fork(() -> fetchList(url)))
                .forEach(requests::add);
        scope.join();
        List<T> websites = requests.stream()
                .map(StructuredTaskScope.Subtask::get)
                .flatMap(DataParser::splitByEol)
                .map(String::strip)
                .parallel()
                .filter(line -> !line.isBlank())
                .filter(line -> !DataParser.isComment(line))
                .map(String::toLowerCase)
                .map(DataParser::parseHostsLine)
                .filter(Objects::nonNull)
                .filter(filterRelatedLines())
                .distinct()
                .map(this::toObject)
                .collect(Collectors.toCollection(ArrayList::new));
        if (!urls.isEmpty() && websites.isEmpty()) {
            throw UserInputException.noStackTrace("No valid %s entries were loaded; DNS settings were not changed"
                    .formatted(listType()));
        }
        return websites;
    }

    @SneakyThrows
    private String fetchList(String url) {
        Log.io("Loading %s list from url: %s".formatted(listType(), url));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = sendWithRetry(request);
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw UserInputException.noStackTrace("Failed to load %s list from %s: HTTP %s"
                    .formatted(listType(), url, response.statusCode()));
        }
        String body = response.body();
        String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
        if (body.isBlank() || contentType.contains("text/html") || looksLikeHtml(body)) {
            throw UserInputException.noStackTrace("Invalid or empty %s list received from %s"
                    .formatted(listType(), url));
        }
        return body;
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request) throws IOException, InterruptedException {
        final int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> response = client.send(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (attempt == maxAttempts || (status != 502 && status != 503 && status != 504)) {
                    return response;
                }
                Log.fail("Temporary HTTP %s loading %s (attempt %s/%s); retrying"
                        .formatted(status, request.uri(), attempt, maxAttempts));
            } catch (IOException exception) {
                if (attempt == maxAttempts) {
                    throw new IOException("Failed to load %s list from %s after %s attempts"
                            .formatted(listType(), request.uri(), maxAttempts), exception);
                }
                Log.fail("%s loading %s (attempt %s/%s); retrying"
                        .formatted(exception.getClass().getSimpleName(), request.uri(), attempt, maxAttempts));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw exception;
            }
            pauseBeforeRetry(attempt);
        }
        throw new IllegalStateException("Retry loop exhausted");
    }

    void pauseBeforeRetry(int attempt) throws InterruptedException {
        Thread.sleep(Duration.ofSeconds(2L * attempt));
    }

    private boolean looksLikeHtml(String body) {
        String beginning = body.stripLeading().toLowerCase();
        return beginning.startsWith("<!doctype html") || beginning.startsWith("<html");
    }

}
