package com.bsmlabs.vector_search.service;

import com.bsmlabs.vector_search.config.AppProperties;
import com.bsmlabs.vector_search.domain.ArxivPaper;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
                "embedding", AttributeValue.builder().l(embeddingDetails).build()
        );

        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(properties.dynamoDb().tableName())
                .item(items)
                .build());
    }

    public List<SearchResult> searchInMemory(String queryText, int topK) {
        // 1. Generate vector embedding for input query
        List<Float> queryVector = embeddingService.generateEmbedding(queryText);

        // 2. Fetch stored items from DynamoDB
        var scanResponse = dynamoDbClient.scan(ScanRequest.builder()
                .tableName(properties.dynamoDb().tableName())
                .build());

        List<Map.Entry<Map<String, AttributeValue>, Double>> scoredItems = new ArrayList<>();

        // 3. Compute cosine similarity for each item
        for (Map<String, AttributeValue> item : scanResponse.items()) {
            if (item.containsKey("embedding")) {
                List<Float> itemVector = item.get("embedding").l().stream()
                        .map(av -> Float.parseFloat(av.n()))
                        .toList();

                double similarity = calculateCosineSimilarity(queryVector, itemVector);
                scoredItems.add(Map.entry(item, similarity));
            }
        }

        // 4. Sort descending by similarity score and take top K
        return scoredItems.stream()
                .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                .limit(topK)
                .map(entry -> new SearchResult(entry.getKey(), entry.getValue()))
                .toList();
    }

    private double calculateCosineSimilarity(List<Float> vecA, List<Float> vecB) {
        if (vecA == null || vecB == null || vecA.size() != vecB.size() || vecA.isEmpty()) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vecA.size(); i++) {
            dotProduct += vecA.get(i) * vecB.get(i);
            normA += Math.pow(vecA.get(i), 2);
            normB += Math.pow(vecB.get(i), 2);
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
