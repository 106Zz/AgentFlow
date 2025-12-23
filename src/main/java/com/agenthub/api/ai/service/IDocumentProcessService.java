package com.agenthub.api.ai.service;

import java.io.File;
import java.util.List;

/**
 * 文档处理服务接口
 */
public interface IDocumentProcessService {

    /**
     * 解析文档内容
     * 
     * @param file 文件
     * @param fileType 文件类型（pdf/word/excel/image）
     * @return 提取的文本内容
     */
    String extractText(File file, String fileType);

    /**
     * 文档分块
     * 
     * @param content 文档内容
     * @param chunkSize 块大小
     * @param overlap 重叠大小
     * @return 文档块列表
     */
    List<String> splitDocument(String content, int chunkSize, int overlap);

    /**
     * 生成文档摘要
     * 
     * @param content 文档内容
     * @return 摘要
     */
    String generateSummary(String content);
}
