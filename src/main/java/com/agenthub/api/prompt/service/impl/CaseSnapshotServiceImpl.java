package com.agenthub.api.prompt.service.impl;

import com.agenthub.api.prompt.domain.entity.CaseSnapshot;
import com.agenthub.api.prompt.enums.CaseStatus;
import com.agenthub.api.prompt.mapper.CaseSnapshotMapper;
import com.agenthub.api.prompt.service.ICaseSnapshotService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Case 快照服务实现
 *
 * @author AgentHub
 * @since 2026-01-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseSnapshotServiceImpl implements ICaseSnapshotService {

    private final CaseSnapshotMapper caseSnapshotMapper;
    private final ObjectMapper objectMapper;

    @Value("${case.freeze.enabled:false}")
    private boolean freezeEnabled;

    @Value("${case.freeze.sampling-rate:1.0}")
    private double samplingRate;

    @Value("${case.freeze.fail-only:false}")
    private boolean failOnly;

    @Override
    @Async("taskExecutor")
    public void freezeAsync(CaseSnapshot snapshot) {
        if (!freezeEnabled) {
            return;
        }

        // 采样控制
        if (!shouldSample()) {
            log.debug("[CaseSnapshot] 采样跳过: samplingRate={}", samplingRate);
            return;
        }

        freezeInternal(snapshot);
    }

    @Override
    public CaseSnapshot getByCaseId(String caseId) {
        LambdaQueryWrapper<CaseSnapshot> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CaseSnapshot::getCaseId, caseId)
                .eq(CaseSnapshot::getDelFlag, 0);
        return caseSnapshotMapper.selectOne(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean markEvaluated(String caseId) {
        CaseSnapshot snapshot = getByCaseId(caseId);
        if (snapshot == null) {
            return false;
        }

        snapshot.setIsEvaluated(true);
        return caseSnapshotMapper.updateById(snapshot) > 0;
    }

    /**
     * 内部冻结逻辑（注意：此方法可能在异步线程中执行，ThreadLocal 已失效）
     */
    private void freezeInternal(CaseSnapshot snapshot) {
        try {
            // 如果只记录失败，且状态为完成，则跳过
            if (failOnly && CaseStatus.COMPLETED.equals(snapshot.getStatus())) {
                log.debug("[CaseSnapshot] 仅记录失败模式，跳过成功 Case");
                return;
            }

            // 生成 Case ID
            String caseId = generateCaseId();
            snapshot.setCaseId(caseId);

            // 设置时间戳
            LocalDateTime now = LocalDateTime.now();
            snapshot.setRequestTime(snapshot.getRequestTime() != null ? snapshot.getRequestTime() : now);
            snapshot.setCreateTime(now);
            snapshot.setUpdateTime(now);

            // ⚠️ 提示词数据已在主线程通过 CaseSnapshotBuilder.capturePromptData() 捕获
            // 此时不再从 ThreadLocal 获取（异步线程已失效）

            // 填充元数据（环境信息、采样配置等）
            enrichMetadata(snapshot);

            // 设置默认值
            if (snapshot.getStatus() == null) {
                snapshot.setStatus(CaseStatus.COMPLETED);
            }
            if (snapshot.getIsEvaluated() == null) {
                snapshot.setIsEvaluated(false);
            }

            // 保存到数据库
            caseSnapshotMapper.insert(snapshot);

            log.info("[CaseSnapshot] 已冻结: caseId={}, scenario={}, intent={}",
                    caseId, snapshot.getScenario(), snapshot.getIntent());

        } catch (Exception e) {
            log.warn("[CaseSnapshot] 冻结失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 填充元数据
     */
    private void enrichMetadata(CaseSnapshot snapshot) {
        try {
            ObjectNode metaNode;
            if (snapshot.getMetadata() != null && snapshot.getMetadata().isObject()) {
                metaNode = (ObjectNode) snapshot.getMetadata();
            } else {
                metaNode = objectMapper.createObjectNode();
            }

            // 环境信息
            metaNode.put("environment", getEnvironment());
            metaNode.put("hostname", getHostname());
            metaNode.put("agenthub_version", "4.0.0");

            // 快照配置
            metaNode.put("sampling_rate", samplingRate);
            metaNode.put("freeze_enabled", freezeEnabled);
            metaNode.put("fail_only", failOnly);

            snapshot.setMetadata(metaNode);

        } catch (Exception e) {
            log.warn("[CaseSnapshot] 填充元数据失败: {}", e.getMessage());
        }
    }

    /**
     * 判断是否应该采样
     */
    private boolean shouldSample() {
        if (samplingRate >= 1.0) {
            return true;
        }
        if (samplingRate <= 0) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble() < samplingRate;
    }

    /**
     * 生成 Case ID
     * 格式：case_YYYYMMDD_XXXXXXXX
     */
    private String generateCaseId() {
        String date = LocalDateTime.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return "case_" + date + "_" + random;
    }

    private String getEnvironment() {
        String env = System.getenv("SPRING_PROFILES_ACTIVE");
        return env != null ? env : "development";
    }

    private String getHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
