package com.agenthub.api.knowledge.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文档处理结果
 * <p>
 * 封装页面级处理统计，用于区分 SUCCESS / PARTIAL / FAILED 状态
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProcessResult {

    /** 入库块数 */
    private int chunkCount;

    /** PDF 总页数（非 PDF 文件为 0） */
    private int totalPages;

    /** 成功处理页数 */
    private int successPages;

    /** 失败页数 */
    private int failedPages;

    /** 失败页码列表，例如 [3, 8, 10] */
    private List<Integer> failedPageNums;

    /** 失败摘要 */
    private String failureSummary;

    /**
     * 判断是否为非 PDF 文件的处理结果（无页面概念）
     */
    public boolean isNonPdf() {
        return totalPages == 0;
    }

    /**
     * 获取最终处理状态
     *
     * @return "3"=SUCCESS, "4"=FAILED, "5"=PARTIAL
     */
    public String getFinalStatus() {
        if (isNonPdf()) {
            return chunkCount > 0 ? "3" : "4";
        }
        if (failedPages == 0) {
            return "3"; // SUCCESS
        }
        if (successPages > 0) {
            return "5"; // PARTIAL
        }
        return "4"; // FAILED
    }
}
