package com.bsmlabs.vector_search.service;

import com.bsmlabs.vector_search.config.AppProperties;
import com.bsmlabs.vector_search.domain.ArxivPaper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static java.lang.System.out;

@Service
public class GzipHttpDatasetStreamer implements DatasetStreamer {

    private final AppProperties config;
    private final JsonMapper mapper;

    public GzipHttpDatasetStreamer(AppProperties config, JsonMapper mapper) {
        this.config = config;
        this.mapper = mapper;
    }

    @Override
    public List<ArxivPaper> loadPapers() throws Exception {
        List<ArxivPaper> papers = new ArrayList<>();
        String datasetUrl = config.dataset().url();

        out.println("Connecting to dataset URL: " + datasetUrl);

        // Build HttpClient with REDIRECT ALWAYS enabled for Hugging Face CDN redirects
        try (var client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build()) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(datasetUrl))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();

            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                throw new RuntimeException(
                        "Failed to download dataset. HTTP status code: "
                                + response.statusCode());
            }

            // Wrap stream in GZIPInputStream and read line-by-line
            try (var gzStream = new GZIPInputStream(response.body());
                 var reader = new BufferedReader(
                         new InputStreamReader(gzStream, StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null
                        && papers.size() < config.dataset().sampleSize()) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }

                    var jsonNode = mapper.readTree(line);

                    // Extract fields based on arxiv-abstracts-2021 JSON schema
                    String id = jsonNode.path("id").asString("");
                    String title = jsonNode.path("title").asString("");
                    String abstractText = jsonNode.path("abstract").asString("");
                    String authors = jsonNode.path("authors").asString("");

                    papers.add(new ArxivPaper(id, title, abstractText, authors));
                }
            }
        }

        out.printf("Successfully loaded %d records from Hugging Face dataset.%n", papers.size());
        return papers;
    }
}