package com.bsmlabs.vector_search.controller;

import com.bsmlabs.vector_search.service.PaperVectorService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

@Controller
public class SearchController {

    private final PaperVectorService paperVectorService;

    public SearchController(PaperVectorService paperVectorService) {
        this.paperVectorService = paperVectorService;
    }

    @GetMapping("/")
    public String searchPage(Model model) {
        model.addAttribute("query", "");
        model.addAttribute("topK", 5);
        return "search";
    }

    @PostMapping("/")
    public String search(@RequestParam String query,
                         @RequestParam(defaultValue = "5") int topK,
                         Model model) {
        String normalizedQuery = query == null ? "" : query.trim();
        int normalizedTopK = Math.clamp(topK, 1, 20);

        model.addAttribute("query", normalizedQuery);
        model.addAttribute("topK", normalizedTopK);

        if (normalizedQuery.isBlank()) {
            model.addAttribute("error", "Enter a search query.");
            return "search";
        }

        long startTime = System.nanoTime();
        try {
            List<PaperVectorService.SearchResult> searchResults =
                    paperVectorService.searchInMemory(normalizedQuery, normalizedTopK);
            double elapsedSeconds =
                    (System.nanoTime() - startTime) / 1_000_000_000.0;

            List<SearchResultView> results = IntStream.range(0, searchResults.size())
                    .mapToObj(index -> toView(searchResults.get(index), index + 1))
                    .toList();

            model.addAttribute("results", results);
            model.addAttribute("elapsedSeconds", elapsedSeconds);
        } catch (RuntimeException exception) {
            model.addAttribute("error", "Search failed: " + exception.getMessage());
        }

        return "search";
    }

    private SearchResultView toView(PaperVectorService.SearchResult result, int citationNumber) {
        Map<String, AttributeValue> item = result.item();
        String paperId = stringAttribute(item, "paper_id", "");
        String title = stringAttribute(item, "title", "Untitled");
        String authors = stringAttribute(item, "authors", "Unknown Authors");
        return new SearchResultView(
                title,
                authors,
                stringAttribute(item, "abstract", "No abstract available."),
                paperId,
                formatCitation(citationNumber, authors, title, paperId),
                result.score());
    }

    private String formatCitation(int citationNumber,
                                  String authors,
                                  String title,
                                  String paperId) {
        String citation = "[" + citationNumber + "] " + authors + ". " + title + ".";
        return paperId.isBlank() ? citation : citation + " arXiv:" + paperId + ".";
    }

    private String stringAttribute(Map<String, AttributeValue> item,
                                   String attributeName,
                                   String defaultValue) {
        AttributeValue attribute = item.get(attributeName);
        return attribute == null || attribute.s() == null || attribute.s().isBlank()
                ? defaultValue
                : attribute.s();
    }

    public record SearchResultView(String title,
                                   String authors,
                                   String abstractText,
                                   String paperId,
                                   String citation,
                                   double score) {
    }
}
