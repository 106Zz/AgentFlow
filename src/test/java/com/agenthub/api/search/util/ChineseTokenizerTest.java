package com.agenthub.api.search.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChineseTokenizerTest {

    @Test
    void tokenize() {

      // 测试分词器
      ChineseTokenizer tokenizer = new ChineseTokenizer();

// 示例1
      String text1 = "功率因数调整电费按实际功率因数计算";
      List<String> tokens1 = tokenizer.tokenize(text1);
      System.out.println(tokens1.toString());
// 输出: ["功率因数", "调整", "电费", "按", "实际", "功率因数", "计算"]

// 示例2
      String text2 = "GB/T 19964 光伏发电站技术规范要求";
      List<String> tokens2 = tokenizer.tokenize(text2);
      System.out.println(tokens2.toString());
// 输出: ["GB/T 19964", "光伏发电", "站", "技术", "规范", "要求"]

// 示例3：词频统计
      String text3 = "功率因数和电费都很重要";
      Map<String, Integer> freq3 = tokenizer.tokenizeAndCount(text3);
      System.out.println(freq3.toString());
    }
}