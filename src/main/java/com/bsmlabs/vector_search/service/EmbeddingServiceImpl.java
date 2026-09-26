package com.bsmlabs.vector_search.service;

import com.bsmlabs.vector_search.config.AppProperties;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

@Service
public class EmbeddingServiceImpl implements EmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingServiceImpl.class);

    private final BedrockRuntimeClient bedrockRuntimeClient;
    private final AppProperties properties;
    private final JsonMapper jsonMapper;

    public EmbeddingServiceImpl(BedrockRuntimeClient bedrockRuntimeClient, AppProperties properties, JsonMapper jsonMapper) {
        this.bedrockRuntimeClient = bedrockRuntimeClient;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public String clean(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    @Override
    public List<Float> generateEmbedding(String text) {
        try {
            String inputText = clean(text);
            int originalLength = inputText.length();
            int maxEmbedChars = properties.bedrockProperties().maxEmbedChars();
            if (originalLength > maxEmbedChars) {
                inputText = inputText.substring(0, maxEmbedChars);
                logger.warn("Embedding input truncated from {} to {} characters", originalLength, maxEmbedChars);
            }

            var payload = jsonMapper.createObjectNode();
            payload.put("inputText", inputText);
            payload.put("dimensions", properties.bedrockProperties().dimensions());
            payload.put("normalize", properties.bedrockProperties().normalize());

            var invokeModelRequest = InvokeModelRequest.builder()
                    .modelId(properties.bedrockProperties().modelId())
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(jsonMapper.writeValueAsString(payload)))
                    .build();

            String responseBody = bedrockRuntimeClient.invokeModel(invokeModelRequest)
                    .body()
                    .asUtf8String();

            JsonNode root = jsonMapper.readTree(responseBody);
            List<Float> embedding = new ArrayList<>();
            for (JsonNode node : root.get("embedding")) {
                embedding.add((float) node.asDouble());
            }

            if (embedding.size() != properties.bedrockProperties().dimensions()) {
                throw new IllegalStateException("Bedrock returned "
                        + embedding.size() + " dimensions; expected "
                        + properties.bedrockProperties().dimensions());
            }

            return embedding;

        } catch (Exception exception) {
            throw new RuntimeException("Error generating vector embedding", exception);
        }
    }
}
