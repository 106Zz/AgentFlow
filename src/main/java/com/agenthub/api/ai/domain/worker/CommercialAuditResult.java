package com.agenthub.api.ai.domain.worker;


import java.util.List;

public record CommercialAuditResult(
        String item,         // 审核项：如 "投标保证金", "付款周期"
        boolean passed,      // 是否合规
        String detail,       // 风险描述或合规理由
        String requirement,  // 依据的规则原文（来自 RAG）
        List<Source> sources // 证据溯源链接
) {

    /**
     * 内部定义的证据对象 (不要导 javax.xml.transform!)
     */
    public record Source(
            String filename,    // 文件名 (e.g., "2026广东市场规则.pdf")
            String downloadUrl  // OSS 临时签名链接 (e.g., "http://oss...Signature=...")
    ) {}
}
