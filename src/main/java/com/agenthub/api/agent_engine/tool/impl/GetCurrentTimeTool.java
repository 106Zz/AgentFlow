package com.agenthub.api.agent_engine.tool.impl;

import com.agenthub.api.agent_engine.model.AgentContext;
import com.agenthub.api.agent_engine.model.AgentToolDefinition;
import com.agenthub.api.agent_engine.model.ToolExecutionRequest;
import com.agenthub.api.agent_engine.model.ToolExecutionResult;
import com.agenthub.api.agent_engine.tool.AgentTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.Map;

/**
 * 获取当前时间工具
 * <p>提供当前日期、时间、星期等信息</p>
 */
@Slf4j
@Component
public class GetCurrentTimeTool implements AgentTool {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final WeekFields WEEK_FIELDS = WeekFields.of(Locale.CHINA);

    @Override
    public AgentToolDefinition getDefinition() {
        return AgentToolDefinition.builder()
                .name("get_current_time")
                .description("获取当前日期和时间。当用户询问今天几号、现在几点、本周是第几周等时间相关问题时使用。")
                .parameterSchema("""
                        {
                            "type": "object",
                            "properties": {
                                "timezone": {
                                    "type": "string",
                                    "description": "时区，默认 Asia/Shanghai",
                                    "default": "Asia/Shanghai"
                                },
                                "format": {
                                    "type": "string",
                                    "enum": ["full", "date", "time", "week"],
                                    "description": "返回格式：full=完整日期时间, date=仅日期, time=仅时间, week=星期信息",
                                    "default": "full"
                                }
                            }
                        }
                        """)
                .requiresConfirmation(false)
                .costWeight(0) // 无成本
                .build();
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request, AgentContext context) {
        try {
            Map<String, Object> args = request.getArguments();
            String timezone = args.containsKey("timezone") && args.get("timezone") != null ?
                (String) args.get("timezone") : "Asia/Shanghai";
            String format = args.containsKey("format") ? (String) args.get("format") : "full";

            ZoneId zoneId = ZoneId.of(timezone);
            LocalDateTime now = LocalDateTime.now(zoneId);

            String result;
            switch (format) {
                case "date":
                    result = String.format("当前日期: %s (时区: %s)",
                        now.format(DATE_FORMATTER), timezone);
                    break;

                case "time":
                    result = String.format("当前时间: %s (时区: %s)",
                        now.format(TIME_FORMATTER), timezone);
                    break;

                case "week":
                    int weekOfYear = now.get(WEEK_FIELDS.weekOfYear());
                    int weekOfMonth = now.get(WEEK_FIELDS.weekOfMonth());
                    String[] weekdays = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
                    int weekday = now.getDayOfWeek().getValue() - 1; // MONDAY=1 -> 0

                    result = String.format(
                        "当前时间: %s\n今天是: %s\n本月第%d周，本年第%d周",
                        now.format(DATETIME_FORMATTER),
                        weekdays[weekday >= 0 ? weekday : 6],
                        weekOfMonth,
                        weekOfYear
                    );
                    break;

                case "full":
                default:
                    result = String.format(
                        "当前日期时间: %s\n今天是: %s\n时区: %s",
                        now.format(DATETIME_FORMATTER),
                        now.getDayOfWeek().toString(),
                        timezone
                    );
                    break;
            }

            log.info("[TimeTool] 获取时间: format={}, timezone={}, result={}", format, timezone, result);
            return ToolExecutionResult.success(result, Map.of(
                "datetime", now.format(DATETIME_FORMATTER),
                "date", now.format(DATE_FORMATTER),
                "time", now.format(TIME_FORMATTER),
                "timezone", timezone
            ));

        } catch (Exception e) {
            log.error("[TimeTool] 获取时间失败", e);
            return ToolExecutionResult.failure("获取时间失败: " + e.getMessage());
        }
    }
}
