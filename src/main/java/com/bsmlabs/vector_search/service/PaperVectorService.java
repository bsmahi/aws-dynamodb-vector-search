package com.bsmlabs.vector_search.service;

import com.bsmlabs.vector_search.config.AppProperties;
import com.bsmlabs.vector_search.domain.ArxivPaper;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

@Service
public class PaperVectorService {

    public record SearchResult(Map<String, AttributeValue> item, double score) {}

    private final DynamoDbClient dynamoDbClient;
    private final EmbeddingService embeddingService;
    private final AppProperties properties;

    public PaperVectorService(DynamoDbClient dynamoDbClient, EmbeddingService embeddingService, AppProperties properties) {
        this.dynamoDbClient = dynamoDbClient;
        this.embeddingService = embeddingService;
        this.properties = properties;
    }

    public void storePaper(ArxivPaper paper) {
        String cleanTitle = embeddingService.clean(paper.title());
        String cleanAbstract = embeddingService.clean(paper.abstractText());

        List<Float> embedding = embeddingService.generateEmbedding(cleanTitle + "." + cleanAbstract);
        List<AttributeValue> embeddingDetails = embedding.stream()
                .map(value -> AttributeValue.builder().n(String.valueOf(value)).build())
                .toList();

        Map<String, AttributeValue> items = Map.of(
                "paper_id", AttributeValue.builder().s(paper.id()).build(),
                "title", AttributeValue.builder().s(cleanTitle).build(),
                "abstract", AttributeValue.builder().s(cleanAbstract).build(),
                "authors", AttributeValue.builder().s(embeddingService.clean(paper.authors())).build(),
                "embedding", AttributeValue.builder().l(embeddingDetails).build(),
                "embedding_model_id", AttributeValue.builder()
                        .s(properties.bedrockProperties().modelId()).build(),
                "embedding_dimensions", AttributeValue.builder()
                        .n(String.valueOf(properties.bedrockProperties().dimensions())).build(),
                "embedding_normalized", AttributeValue.builder()
                        .bool(properties.bedrockProperties().normalize()).build(),
                "embedding_version", AttributeValue.builder()
                        .s(embeddingVersion()).build()
        );

        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(properties.dynamoDb().tableName())
                .item(items)
                .build());
    }

    public List<SearchResult> searchInMemory(String queryText, int topK) {
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be at least 1");
        }

        // 1. Generate vector embedding for input query
        List<Float> queryVector = embeddingService.generateEmbedding(queryText);

        double queryNorm = calculateSquaredNorm(queryVector);
        PriorityQueue<SearchResult> topResults = new PriorityQueue<>(
                Comparator.comparingDouble(SearchResult::score));
        Map<String, AttributeValue> lastEvaluatedKey = null;

        do {
            ScanRequest.Builder scanRequest = ScanRequest.builder()
                    .tableName(properties.dynamoDb().tableName());
            if (lastEvaluatedKey != null && !lastEvaluatedKey.isEmpty()) {
                scanRequest.exclusiveStartKey(lastEvaluatedKey);
            }

            var scanResponse = dynamoDbClient.scan(scanRequest.build());
            for (Map<String, AttributeValue> item : scanResponse.items()) {
                if (!item.containsKey("embedding")) {
                    continue;
                }

                List<Float> itemVector = item.get("embedding").l().stream()
                        .map(av -> Float.parseFloat(av.n()))
                        .toList();
                if (!isCompatibleEmbedding(item, itemVector)) {
                    continue;
                }

                double similarity = calculateCosineSimilarity(queryVector, itemVector, queryNorm);
                topResults.offer(new SearchResult(item, similarity));
                if (topResults.size() > topK) {
                    topResults.poll();
                }
            }
            lastEvaluatedKey = scanResponse.lastEvaluatedKey();
        } while (lastEvaluatedKey != null && !lastEvaluatedKey.isEmpty());

        return topResults.stream()
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .toList();
    }

    private boolean isCompatibleEmbedding(Map<String, AttributeValue> item, List<Float> vector) {
        if (vector.size() != properties.bedrockProperties().dimensions()) {
            return false;
        }

        AttributeValue modelId = item.get("embedding_model_id");
        if (modelId != null && !properties.bedrockProperties().modelId().equals(modelId.s())) {
            return false;
        }

        AttributeValue dimensions = item.get("embedding_dimensions");
        if (dimensions != null
                && !String.valueOf(properties.bedrockProperties().dimensions()).equals(dimensions.n())) {
            return false;
        }

        AttributeValue normalized = item.get("embedding_normalized");
        if (normalized != null
                && normalized.bool() != properties.bedrockProperties().normalize()) {
            return false;
        }

        AttributeValue version = item.get("embedding_version");
        return version == null || embeddingVersion().equals(version.s());
    }

    private String embeddingVersion() {
        return properties.bedrockProperties().modelId()
                + ":" + properties.bedrockProperties().dimensions()
                + ":" + properties.bedrockProperties().normalize()
                + ":" + properties.bedrockProperties().maxEmbedChars();
    }

    private double calculateCosineSimilarity(List<Float> vecA, List<Float> vecB, double squaredNormA) {
        if (vecA == null || vecB == null || vecA.size() != vecB.size() || vecA.isEmpty()) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vecA.size(); i++) {
            dotProduct += vecA.get(i) * vecB.get(i);
            normB += vecB.get(i) * vecB.get(i);
        }

        if (squaredNormA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(squaredNormA) * Math.sqrt(normB));
    }

    private double calculateSquaredNorm(List<Float> vector) {
        double squaredNorm = 0.0;
        for (Float value : vector) {
            squaredNorm += value * value;
        }
        return squaredNorm;
    }
}
