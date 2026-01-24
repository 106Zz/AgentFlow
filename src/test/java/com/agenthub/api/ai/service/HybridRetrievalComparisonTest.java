package com.agenthub.api.ai.service;

import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeQuery;
import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeResult;
import com.agenthub.api.system.domain.model.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 混合检索 vs 纯向量检索 A/B 对比测试
 *
 * 使用方法：
 * 1. 先设置 enable-hybrid: false，运行测试保存向量检索结果
 * 2. 再设置 enable-hybrid: true，运行测试保存混合检索结果
 * 3. 对比两个 CSV 文件的结果
 */
@SpringBootTest
@TestPropertySource(properties = {
        "knowledge.retrieval.enable-hybrid=false"  // 默认关闭混合检索，可手动修改
})
class HybridRetrievalComparisonTest {

    @Autowired
    private PowerKnowledgeService powerKnowledgeService;

    @Value("${knowledge.retrieval.enable-hybrid:false}")
    private boolean enableHybridSearch;

    /**
     * 输出目录：项目 test-results 文件夹
     */
    private static final String OUTPUT_DIR = "D:\\study\\AI\\agenthub\\AgentHub\\test-results";

    /**
     * 设置测试用户（管理员，可以查看所有文档）
     */
    private void setupTestUser() {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(1L);  // admin ID (数据库中的 admin 用户)
        loginUser.setUsername("admin");
        loginUser.setRoles(List.of("admin"));

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        loginUser,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_admin"))
                );

        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /**
     * 清理安全上下文
     */
    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 核心对比测试 - 运行一组查询并保存结果
     */
    @Test
    @Disabled("手动执行：先测试向量模式，再测试混合模式")
    void compareVectorVsHybrid() throws IOException {
        // 设置测试用户
        setupTestUser();
        String mode = enableHybridSearch ? "混合检索" : "向量检索";
        System.out.println("=".repeat(60));
        System.out.println("当前模式: " + mode);
        System.out.println("=".repeat(60));

        // 测试查询集（基于实际文档设计）
        List<TestCase> testCases = List.of(
                new TestCase("2026年广东电力市场的交易机制是什么？", "机制理解"),
                new TestCase("年度交易的实施步骤有哪些？", "流程查询"),
                new TestCase("什么是双边交易？", "关键词定义"),
                new TestCase("2026年广东电力交易的交易参数有哪些？", "参数查询"),
                new TestCase("广东电力市场的交易方式和结算方式", "多方面问题"),
                new TestCase("年度交易的时间安排", "时间查询"),
                new TestCase("市场成员的权利和义务", "权利义务"),
                new TestCase("交易价格的形成机制", "价格机制"),
                new TestCase("结算规则和违约处理", "结算规则")
        );

        // 结果列表
        List<TestResult> results = new ArrayList<>();

        for (TestCase testCase : testCases) {
            System.out.println("\n" + "─".repeat(50));
            System.out.println("查询: " + testCase.query());
            System.out.println("类型: " + testCase.description());

            try {
                long start = System.currentTimeMillis();
                PowerKnowledgeResult result = powerKnowledgeService.retrieve(
                        new PowerKnowledgeQuery(testCase.query(), 5, null, null)
                );
                long elapsed = System.currentTimeMillis() - start;

                // 记录结果
                TestResult testResult = new TestResult(
                        testCase.query(),
                        testCase.description(),
                        result.rawContentSnippets().size(),
                        result.sources().size(),
                        elapsed,
                        getTopScore(result),
                        getSourceFiles(result)
                );
                results.add(testResult);

                // 打印结果
                System.out.println("耗时: " + elapsed + "ms");
                System.out.println("片段数: " + result.rawContentSnippets().size());
                System.out.println("来源数: " + result.sources().size());
                System.out.println("最高分: " + getTopScore(result));
                System.out.println("来源文件: " + getSourceFiles(result));

            } catch (Exception e) {
                System.err.println("查询失败: " + e.getMessage());
                results.add(new TestResult(
                        testCase.query(),
                        testCase.description(),
                        0,
                        0,
                        -1,
                        0.0,
                        "ERROR: " + e.getMessage()
                ));
            }
        }

        // 保存结果到 CSV
        saveToCsv(results, mode);

        // 打印汇总
        printSummary(results, mode);
    }

    /**
     * 精确对比测试 - 针对单个查询的详细分析
     */
    @Test
    @Disabled("手动执行：用于深入分析单个查询")
    void detailedComparison() {
        setupTestUser();
        String query = "2026年广东电力市场的年度交易流程";

        System.out.println("=".repeat(60));
        System.out.println("详细分析: " + query);
        System.out.println("当前模式: " + (enableHybridSearch ? "混合检索" : "向量检索"));
        System.out.println("=".repeat(60));

        PowerKnowledgeResult result = powerKnowledgeService.retrieve(
                new PowerKnowledgeQuery(query, 5, null, null)
        );

        System.out.println("\n摘要: " + result.answer());
        System.out.println("\n来源文件:");
        List<PowerKnowledgeResult.SourceDocument> sources = result.sources();
        for (int i = 0; i < sources.size(); i++) {
            PowerKnowledgeResult.SourceDocument source = sources.get(i);
            System.out.printf("  %d. %s\n", i + 1, source.filename());
            System.out.println("     " + source.downloadUrl());
        }

        System.out.println("\n文档片段:");
        for (int i = 0; i < result.rawContentSnippets().size(); i++) {
            String snippet = result.rawContentSnippets().get(i);
            System.out.printf("\n[片段 %d]\n%s\n", i + 1,
                    snippet.length() > 200 ? snippet.substring(0, 200) + "..." : snippet);
        }

        System.out.println("\n调试信息:");
        result.debugInfo().forEach((key, value) -> {
            System.out.println("  " + key + " = " + value);
        });
    }

    /**
     * 精确术语匹配测试（测试 BM25 的关键词匹配能力）
     */
    @Test
    @Disabled("手动执行：测试精确术语查询能力")
    void exactTermMatchTest() {
        setupTestUser();
        String[] terms = {
                "双边交易",
                "年度交易",
                "交易参数",
                "结算方式",
                "违约处理"
        };

        System.out.println("=".repeat(60));
        System.out.println("精确术语查询测试 - 当前模式: " + (enableHybridSearch ? "混合检索" : "向量检索"));
        System.out.println("=".repeat(60));

        for (String term : terms) {
            System.out.println("\n查询: " + term);

            PowerKnowledgeResult result = powerKnowledgeService.retrieve(
                    new PowerKnowledgeQuery(term + "的具体规定", 5, null, null)
            );

            System.out.println("  结果数: " + result.rawContentSnippets().size());
            System.out.println("  来源: " + getSourceFiles(result));
        }
    }

    /**
     * 性能基准测试
     */
    @Test
    @Disabled("手动执行：性能对比")
    void performanceBenchmark() {
        setupTestUser();
        String[] queries = {
                "2026年广东电力市场的交易机制",
                "年度交易的实施步骤",
                "双边交易的结算方式"
        };

        int warmupRounds = 2;
        int testRounds = 5;

        System.out.println("=".repeat(60));
        System.out.println("性能基准测试 - 当前模式: " + (enableHybridSearch ? "混合检索" : "向量检索"));
        System.out.println("=".repeat(60));

        for (String query : queries) {
            System.out.println("\n查询: " + query);

            // 预热
            for (int i = 0; i < warmupRounds; i++) {
                powerKnowledgeService.retrieve(
                        new PowerKnowledgeQuery(query, 5, null, null)
                );
            }

            // 正式测试
            List<Long> times = new ArrayList<>();
            for (int i = 0; i < testRounds; i++) {
                long start = System.nanoTime();
                powerKnowledgeService.retrieve(
                        new PowerKnowledgeQuery(query, 5, null, null)
                );
                long elapsed = System.nanoTime() - start;
                times.add(elapsed / 1_000_000);  // 转换为毫秒
            }

            // 统计
            long avg = times.stream().mapToLong(Long::longValue).sum() / testRounds;
            long min = times.stream().mapToLong(Long::longValue).min().orElse(0);
            long max = times.stream().mapToLong(Long::longValue).max().orElse(0);

            System.out.printf("  平均: %dms, 最小: %dms, 最大: %dms\n", avg, min, max);
            System.out.println("  原始数据: " + times);
        }
    }

    // ==================== 辅助方法 ====================

    private double getTopScore(PowerKnowledgeResult result) {
        Object score = result.debugInfo().get("top_score");
        if (score instanceof Number) {
            return ((Number) score).doubleValue();
        }
        return 0.0;
    }

    private String getSourceFiles(PowerKnowledgeResult result) {
        return result.sources().stream()
                .map(PowerKnowledgeResult.SourceDocument::filename)
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
    }

    private void saveToCsv(List<TestResult> results, String mode) throws IOException {
        // 确保输出目录存在
        File outputDir = new File(OUTPUT_DIR);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = String.format("ab_test_%s_%s.csv",
                mode.replace(" ", "_"), timestamp);
        File file = new File(outputDir, filename);

        try (FileWriter writer = new FileWriter(file)) {
            // CSV 头部
            writer.write("查询,描述,片段数,来源数,耗时ms,最高分,来源文件\n");

            // 数据行
            for (TestResult r : results) {
                writer.write(String.format("\"%s\",\"%s\",%d,%d,%d,%.4f,\"%s\"\n",
                        r.query().replace("\"", "\"\""),
                        r.description(),
                        r.snippetCount(),
                        r.sourceCount(),
                        r.elapsedMs(),
                        r.topScore(),
                        r.sourceFiles().replace("\"", "\"\"")
                ));
            }
        }

        System.out.println("\n结果已保存到: " + file.getAbsolutePath());
    }

    private void printSummary(List<TestResult> results, String mode) {
        long totalTime = results.stream()
                .mapToLong(TestResult::elapsedMs)
                .sum();
        long avgTime = totalTime / results.size();
        long errorCount = results.stream()
                .filter(r -> r.elapsedMs() < 0)
                .count();

        double avgTopScore = results.stream()
                .mapToDouble(TestResult::topScore)
                .filter(s -> s > 0)
                .average()
                .orElse(0.0);

        System.out.println("\n" + "=".repeat(60));
        System.out.println("汇总统计 [" + mode + "]");
        System.out.println("=".repeat(60));
        System.out.printf("总查询数: %d\n", results.size());
        System.out.printf("失败数: %d\n", errorCount);
        System.out.printf("平均耗时: %dms\n", avgTime);
        System.out.printf("平均最高分: %.4f\n", avgTopScore);
        System.out.println("=".repeat(60));
    }

    // ==================== 内部类 ====================

    record TestCase(String query, String description) {}

    record TestResult(
            String query,
            String description,
            int snippetCount,
            int sourceCount,
            long elapsedMs,
            double topScore,
            String sourceFiles
    ) {}
}
