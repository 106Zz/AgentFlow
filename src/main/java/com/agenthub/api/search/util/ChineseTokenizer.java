package com.agenthub.api.search.util;


import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 中文分词器
 *
 * 设计思路：
 * 1. 针对电力行业文档特点
 * 2. 保留专有名词的完整性
 * 3. 过滤无意义的停用词
 */
@Slf4j
@Component
public class ChineseTokenizer {

    // ========== 停用词表 ==========

    /**
     * 中文停用词（无实际意义，不参与检索）
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人",
            "都", "一", "一个", "上", "也", "很", "到", "说", "要", "去",
            "你", "会", "着", "没有", "看", "好", "自己", "这", "那", "里",
            "么", "之", "与", "及", "等", "或", "但", "而", "中", "为",
            "按", "对", "并", "将"
    );

    // ========== 专有名词模式 ==========

    /**
     * 电力行业专有名词正则
     */
    // ========== 专有名词模式 (强规则) ==========
    // 这些规则优先级高于 Jieba，用于提取标准号、特定格式的短语
    private static final List<Pattern> INDUSTRY_PATTERNS = List.of(
            // 标准编号：GB/T 19964
            Pattern.compile("GB/T\\s*\\d+(\\.\\d+)?"),
            Pattern.compile("DL/T\\s*\\d+(\\.\\d+)?"),

            // 文档引用：附件A、表1
            Pattern.compile("附件?[A-Z]\\d*"),
            Pattern.compile("表\\d+"),
            Pattern.compile("图\\d+"),
            Pattern.compile("第[一二三四五六七八九十百千\\d]+[条章节款]"),

            // 强制保留的行业术语 (防止被切碎)
            // 注意：引入 Jieba 后，这里只需要保留非常特殊的、Jieba 容易分错的词
            // 像 "光伏发电" Jieba 也能分出来，但为了保险可以保留
            Pattern.compile("功率因数"),
            Pattern.compile("光伏发电"),
            Pattern.compile("虚拟电厂"),
            Pattern.compile("储能系统"),

            // 日期
            Pattern.compile("\\d{4}年\\d{1,2}月\\d{1,2}日"),
            Pattern.compile("\\d{4}-\\d{1,2}-\\d{1,2}")
    );

    /**
     * 数字+单位模式
     */
    private static final Pattern NUMBER_UNIT_PATTERN = Pattern.compile(
            "\\d+(\\.\\d+)?\\s*([kKmMgGtT]?[wWVVAhH]{1,2}|%|元|$|°C|℃)"
    );

    // ========== 核心分词方法 ==========

    /**
     * 对文本进行分词
     *
     * @param text 输入文本
     * @return 分词后的词语列表
     */
    public List<String> tokenize(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<String> tokens = new ArrayList<>();
        String remaining = text;

        // 步骤1：提取专有名词
        for (Pattern pattern : INDUSTRY_PATTERNS) {
            Matcher matcher = pattern.matcher(remaining);
            StringBuffer sb = new StringBuffer();

            while (matcher.find()) {
                String matched = matcher.group();
                tokens.add(matched);
                // 用占位符替换，避免重复处理
                matcher.appendReplacement(sb, " ");
            }
            matcher.appendTail(sb);
            remaining = sb.toString();
        }

        // 步骤2：使用 Jieba 处理剩余文本
        // SegMode.SEARCH 适合搜索引擎模式，会将长词再切分 (例如 "清华大学" -> "清华", "大学")
        // SegMode.INDEX 适合索引模式
        // 这里推荐使用 SEARCH 模式，或者默认模式
        List<SegToken> segTokens = new JiebaSegmenter().process(remaining, JiebaSegmenter.SegMode.SEARCH);

        // 步骤3：处理每个分片
        for (SegToken token : segTokens) {
            String word = token.word.trim();

            // 过滤条件
            if (word.length() < 2) continue;      // 过滤单字
            if (isStopWord(word)) continue;      // 过滤停用词
            if (word.matches("\\d+")) continue;  // 过滤纯数字

            // 检查数字+单位 (Jieba 可能会把 "100MW" 分成 "100" 和 "MW")
            // 如果你的正则在步骤1没匹配到，这里可以补救，或者直接信任 Jieba
            if (NUMBER_UNIT_PATTERN.matcher(word).matches()) {
                tokens.add(word);
                continue;
            }

            tokens.add(word);
        }

        return tokens;
    }

    /**
     * 判断是否是停用词
     */
    private boolean isStopWord(String word) {
        return STOP_WORDS.contains(word);
    }

    // ========== 批量处理方法 ==========

    /**
     * 批量分词（v4.3 优化：使用并行处理）
     *
     * @param texts 文本列表
     * @return 文本 -> 分词结果的映射
     */
    public Map<String, List<String>> tokenizeBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new HashMap<>();
        }

        // v4.3 - 使用并行流处理，充分利用多核 CPU
        return texts.parallelStream()
                .collect(Collectors.toMap(
                        text -> text,
                        this::tokenize,
                        (a, b) -> a  // 合并函数（理论上不会有重复 key）
                ));
    }

    /**
     * 批量分词（返回列表形式，按输入顺序）
     * v4.3 新增：保持输入顺序的批量分词方法
     *
     * @param texts 文本列表
     * @return 分词结果列表（与输入一一对应）
     */
    public List<List<String>> tokenizeBatchList(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }

        // v4.3 - 使用并行流处理，但保持顺序
        return texts.parallelStream()
                .map(this::tokenize)
                .collect(Collectors.toList());
    }

    /**
     * 分词并统计词频
     *
     * @param text 输入文本
     * @return 词频统计 Map
     */
    public Map<String, Integer> tokenizeAndCount(String text) {
        List<String> tokens = tokenize(text);
        Map<String, Integer> freqMap = new HashMap<>();

        for (String token : tokens) {
            freqMap.merge(token, 1, Integer::sum);
        }

        return freqMap;
    }

}
