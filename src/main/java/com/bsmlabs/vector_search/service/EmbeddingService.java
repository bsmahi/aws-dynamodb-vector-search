package com.bsmlabs.vector_search.service;

import java.util.List;

public interface EmbeddingService {
    String clean(String text);

    List<Float> generateEmbedding(String text);
}
