package com.bsmlabs.vector_search.service;

import com.bsmlabs.vector_search.domain.ArxivPaper;

import java.util.List;

public interface DatasetStreamer {

    List<ArxivPaper> loadPapers() throws Exception;
}
