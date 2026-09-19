package com.bsmlabs.vector_search.runner;

import com.bsmlabs.vector_search.domain.ArxivPaper;
import com.bsmlabs.vector_search.infrastructure.DynamoDbTableManager;
import com.bsmlabs.vector_search.service.DatasetStreamer;
import com.bsmlabs.vector_search.service.PaperVectorService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

import static java.lang.System.out;

//@Component
public class DatabaseInitializerRunner implements CommandLineRunner {

    private final DynamoDbTableManager tableManager;
    private final DatasetStreamer datasetStreamer;
    private final PaperVectorService paperVectorService;

    public DatabaseInitializerRunner(DynamoDbTableManager tableManager,
                                     DatasetStreamer datasetStreamer,
                                     PaperVectorService paperVectorService) {
        this.tableManager = tableManager;
        this.datasetStreamer = datasetStreamer;
        this.paperVectorService = paperVectorService;
    }

    @Override
    public void run(String... args) throws Exception {
        out.println("=== STEP 1: Creating DynamoDB Table ===");
        tableManager.createTableIfNotExists();
        tableManager.waitUntilActive();

        out.println("\n=== STEP 2: Ingesting Data & Generating Bedrock Vector Embeddings ===");
        List<ArxivPaper> papers = datasetStreamer.loadPapers();
        for (ArxivPaper paper : papers) {
            out.printf("Embedding & Indexing paper: %s%n", paper.title());
            paperVectorService.storePaper(paper);
        }
    }
}