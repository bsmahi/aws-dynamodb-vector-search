package com.bsmlabs.vector_search.config;


import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.aws")
public record AppProperties(DynamoDbProperties dynamoDb,
                            BedrockProperties bedrockProperties,
                            DatasetProperties dataset) {

    public record DynamoDbProperties(String tableName, String indexName) {}

    public record BedrockProperties(String modelId,
                                    Long dimensions,
                                    String distanceFunction,
                                    int maxEmbedChars,
                                    boolean normalize) {}

    public record DatasetProperties(String url, Long sampleSize) {}
}
