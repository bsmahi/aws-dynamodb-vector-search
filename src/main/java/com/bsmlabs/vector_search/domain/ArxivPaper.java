package com.bsmlabs.vector_search.domain;

public record ArxivPaper(
        String id,
        String title,
        String abstractText,
        String authors
) {
    // Compact constructor for validation or field normalization (optional)
    public ArxivPaper {
        id = (id == null) ? "" : id;
        title = (title == null) ? "" : title.trim();
        abstractText = (abstractText == null) ? "" : abstractText.trim();
        authors = (authors == null) ? "" : authors.trim();
    }
}
