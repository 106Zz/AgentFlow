package com.agenthub.api.ai.utils;


import org.springframework.ai.document.Document;

import java.io.InputStream;
import java.util.List;

public interface MultiModalDocumentReader {
  List<Document> read(InputStream inputStream, String filename);
}
