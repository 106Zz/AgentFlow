package com.agenthub.api.ai.service;

import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeQuery;
import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeResult;
import com.agenthub.api.system.domain.model.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PowerKnowledgeService 单元测试
 *
 * 测试文档：
 * 1. 广东电力市场2026年交易关键机制和参数.pdf
 * 2. 广东电力市场常态化开展年度交易实施方案.pdf
 */
@SpringBootTest
class PowerKnowledgeServiceTest {

    @Autowired
    private PowerKnowledgeService powerKnowledgeService;

    /**
     * 设置测试用户（管理员，对应数据库中的 admin/admin123）
     */
    private void setupTestUser() {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(1L);  // admin 用户 ID
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

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @Disabled("需要真实数据和数据库连接")
    void testBasicRetrieval() {
        setupTestUser();  // 设置测试用户

        // 基础检索测试
        PowerKnowledgeQuery query = new PowerKnowledgeQuery(
                "2026年广东电力市场的交易机制是什么？",
                5,
                null,
                null
        );

        PowerKnowledgeResult result = powerKnowledgeService.retrieve(query);

        assertThat(result).isNotNull();
        assertThat(result.rawContentSnippets()).isNotEmpty();

        System.out.println("=== 检索结果 ===");
        System.out.println("摘要: " + result.answer());
        System.out.println("片段数: " + result.rawContentSnippets().size());
        System.out.println("来源数: " + result.sources().size());
    }

    @Test
    @Disabled("需要真实数据")
    void testSpecificQuestion() {
        setupTestUser();
        // 测试具体问题
        PowerKnowledgeQuery query = new PowerKnowledgeQuery(
                "年度交易的实施步骤有哪些？",
                5,
                null,
                null
        );

        PowerKnowledgeResult result = powerKnowledgeService.retrieve(query);

        assertThat(result).isNotNull();
        assertThat(result.rawContentSnippets()).isNotEmpty();

        System.out.println("=== 检索结果 ===");
        System.out.println("摘要: " + result.answer());
    }

    @Test
    @Disabled("需要真实数据")
    void testKeywordMatch() {
        setupTestUser();
        // 测试关键词匹配（BM25 应该更有优势）
        PowerKnowledgeQuery query = new PowerKnowledgeQuery(
                "什么是双边交易？",
                5,
                null,
                null
        );

        PowerKnowledgeResult result = powerKnowledgeService.retrieve(query);

        assertThat(result).isNotNull();
        assertThat(result.rawContentSnippets()).isNotEmpty();

        System.out.println("=== 检索结果 ===");
        System.out.println("摘要: " + result.answer());
        result.sources().forEach(source -> {
            System.out.println("来源: " + source.filename());
        });
    }

    @Test
    @Disabled("需要真实数据")
    void testParameterQuery() {
        setupTestUser();
        // 测试参数查询
        PowerKnowledgeQuery query = new PowerKnowledgeQuery(
                "2026年广东电力交易的交易参数有哪些？",
                5,
                null,
                null
        );

        PowerKnowledgeResult result = powerKnowledgeService.retrieve(query);

        assertThat(result).isNotNull();

        System.out.println("=== 参数查询结果 ===");
        System.out.println("摘要: " + result.answer());
        System.out.println("耗时: " + result.debugInfo().get("total_time_ms") + "ms");
        System.out.println("最高分: " + result.debugInfo().get("top_score"));
    }

    @Test
    @Disabled("需要真实数据")
    void testYearFilter() {
        setupTestUser();
        // 测试年份过滤
        PowerKnowledgeQuery query = new PowerKnowledgeQuery(
                "2026年的电力交易规则",
                5,
                "2026",
                null
        );

        PowerKnowledgeResult result = powerKnowledgeService.retrieve(query);

        assertThat(result).isNotNull();
        System.out.println("=== 2026年查询结果 ===");
        System.out.println("摘要: " + result.answer());
    }

    @Test
    @Disabled("需要真实数据")
    void testEmptyResult() {
        setupTestUser();
        // 测试查不到的情况
        PowerKnowledgeQuery query = new PowerKnowledgeQuery(
                "核电站建设规范",
                5,
                null,
                null
        );

        PowerKnowledgeResult result = powerKnowledgeService.retrieve(query);

        assertThat(result).isNotNull();
        System.out.println("空结果摘要: " + result.answer());
    }

    @Test
    @Disabled("需要真实数据")
    void testMultipleAspects() {
        setupTestUser();
        // 测试多方面问题
        PowerKnowledgeQuery query = new PowerKnowledgeQuery(
                "广东电力市场的交易方式和结算方式",
                5,
                null,
                null
        );

        PowerKnowledgeResult result = powerKnowledgeService.retrieve(query);

        assertThat(result).isNotNull();
        System.out.println("=== 多方面查询结果 ===");
        System.out.println("摘要: " + result.answer());
        System.out.println("来源文件数: " + result.sources().size());
    }

    @Test
    @Disabled("需要真实数据")
    void testExactTermMatch() {
        setupTestUser();
        // 测试精确术语匹配（BM25 优势）
        PowerKnowledgeQuery query = new PowerKnowledgeQuery(
                "双边交易的具体流程是什么？",
                5,
                null,
                null
        );

        PowerKnowledgeResult result = powerKnowledgeService.retrieve(query);

        assertThat(result).isNotNull();
        System.out.println("=== 精确术语查询结果 ===");
        System.out.println("摘要: " + result.answer());
    }
}
