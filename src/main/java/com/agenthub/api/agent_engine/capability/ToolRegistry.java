package com.agenthub.api.agent_engine.capability;

import com.agenthub.api.agent_engine.model.AgentToolDefinition;
import com.agenthub.api.agent_engine.tool.AgentTool;
import com.agenthub.api.prompt.service.ISysPromptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 工具注册中心
 * <p>在应用启动时预构建工具描述缓存，避免运行时重复计算</p>
 *
 * <h3>优化效果：</h3>
 * <ul>
 *   <li>避免每次请求都重新 stream + map + collect</li>
 *   <li>避免每轮循环都重新调用 sysPromptService.render()</li>
 *   <li>O(1) 获取工具描述和工具实例</li>
 * </ul>
 *
 * @author AgentHub
 * @since 2026-02-04
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry implements ApplicationListener<ContextRefreshedEvent> {

    private final List<AgentTool> agentTools;
    private final ISysPromptService sysPromptService;

    /**
     * 预构建的工具描述字符串（所有工具拼接后的完整描述）
     * <p>直接用于 System Prompt 模板中的 {{tools_desc}} 变量</p>
     */
    private volatile String cachedFullToolsDesc = "";

    /**
     * 工具名称到工具实例的索引
     * <p>用于 O(1) 查找工具实例进行执行</p>
     */
    private volatile Map<String, AgentTool> toolNameIndex = Collections.emptyMap();

    /**
     * 单个工具的描述缓存
     * <p>用于需要动态组装工具描述的场景（如未来的多租户权限过滤）</p>
     */
    private volatile Map<String, String> toolDescCache = Collections.emptyMap();

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // 应用启动完成后执行预构建
        preloadToolCache();
    }

    /**
     * 预构建工具缓存
     * <p>在应用启动时执行一次，构建所有工具的描述字符串和索引</p>
     */
    private void preloadToolCache() {
        log.info("[ToolRegistry] 开始预构建工具缓存，当前AgentTool数量: {}", agentTools.size());

        // 按工具名称排序，保证每次启动生成的字符串顺序一致
        // 这对于 LLM 的 Prompt Cache 命中很重要
        List<AgentTool> sortedTools = agentTools.stream()
                .sorted(Comparator.comparing(t -> t.getDefinition().getName()))
                .toList();

        log.info("[ToolRegistry] AgentTool 数量: {}", sortedTools.size());

        Map<String, AgentTool> nameIndex = new HashMap<>();
        Map<String, String> descCache = new HashMap<>();
        StringBuilder fullDescBuilder = new StringBuilder();

        for (AgentTool tool : sortedTools) {
            String toolName = tool.getDefinition().getName();
            nameIndex.put(toolName, tool);

            // 预渲染工具描述
            String toolDesc = renderToolDescription(tool);
            descCache.put(toolName, toolDesc);
            fullDescBuilder.append(toolDesc).append("\n");
        }

        // 原子性替换缓存（Copy-On-Write，线程安全）
        this.toolNameIndex = Map.copyOf(nameIndex);
        this.toolDescCache = Map.copyOf(descCache);
        this.cachedFullToolsDesc = fullDescBuilder.toString();

        log.info("[ToolRegistry] 工具缓存构建完成，工具总数: {}", sortedTools.size());
    }

    /**
     * 渲染单个工具的描述
     *
     * @param tool 工具实例
     * @return 格式化的描述字符串
     */
    private String renderToolDescription(AgentTool tool) {
        AgentToolDefinition def = tool.getDefinition();
        String toolCode = "TOOL-" + def.getName().toUpperCase() + "-v1.0";
        try {
            String dbDesc = sysPromptService.render(toolCode, Collections.emptyMap());
            if (dbDesc != null && !dbDesc.isEmpty()) {
                return String.format("- %s: %s (参数: %s)", def.getName(), dbDesc, def.getParameterSchema());
            }
        } catch (Exception e) {
            log.debug("[ToolRegistry] 工具描述渲染失败: {}", def.getName(), e);
        }
        return String.format("- %s: %s (参数: %s)", def.getName(), def.getDescription(), def.getParameterSchema());
    }

    // ==================== 对外暴露的高性能接口 ====================

    /**
     * 获取完整的工具描述字符串
     * <p>直接用于 System Prompt，复杂度 O(1)</p>
     *
     * @return 所有工具的描述拼接字符串
     */
    public String getFullToolsDescription() {
        return cachedFullToolsDesc;
    }

    /**
     * 根据工具名称获取工具实例
     * <p>用于执行阶段，复杂度 O(1)</p>
     *
     * @param toolName 工具名称
     * @return 工具实例，如果不存在返回 null
     */
    public AgentTool getTool(String toolName) {
        return toolNameIndex.get(toolName);
    }

    /**
     * 获取所有工具的索引
     * <p>用于需要遍历工具的场景</p>
     *
     * @return 工具名称到工具实例的不可变 Map
     */
    public Map<String, AgentTool> getAllTools() {
        return toolNameIndex;
    }

    /**
     * 获取所有工具的列表
     * <p>兼容原有代码，返回工具列表</p>
     *
     * @return 所有工具的不可变列表
     */
    public List<AgentTool> getToolList() {
        return List.copyOf(toolNameIndex.values());
    }

    /**
     * 获取单个工具的描述
     * <p>用于需要动态组装工具描述的场景</p>
     *
     * @param toolName 工具名称
     * @return 工具描述字符串
     */
    public String getToolDescription(String toolName) {
        return toolDescCache.get(toolName);
    }

    /**
     * 获取工具数量
     *
     * @return 当前注册的工具总数
     */
    public int getToolCount() {
        return toolNameIndex.size();
    }

    /**
     * 获取所有工具名称
     * <p>用于需要排除所有工具的场景</p>
     *
     * @return 所有工具名称的不可变集合
     */
    public Set<String> getAllToolNames() {
        return Set.copyOf(toolNameIndex.keySet());
    }

    /**
     * 获取所有工具列表
     *
     * @return 所有 AgentTool 的列表
     */
    public List<AgentTool> getTools(Set<String> excludeToolNames) {
        return toolNameIndex.values().stream()
                .filter(tool -> excludeToolNames == null || !excludeToolNames.contains(tool.getDefinition().getName()))
                .collect(Collectors.toList());
    }
}
