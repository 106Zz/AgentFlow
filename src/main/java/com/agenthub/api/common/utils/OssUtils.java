package com.agenthub.api.common.utils;

import com.aliyun.oss.OSS;
import com.aliyun.oss.common.utils.BinaryUtil;
import com.aliyun.oss.model.MatchMode;
import com.aliyun.oss.model.PolicyConditions;
import com.agenthub.api.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 阿里云OSS工具类
 */
@Component
public class OssUtils {

    private static final Logger log = LoggerFactory.getLogger(OssUtils.class);

    @Autowired
    private OSS ossClient;

    @Value("${aliyun.oss.bucketName}")
    private String bucketName;

    @Value("${aliyun.oss.urlPrefix:}")
    private String urlPrefix;

    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    @Value("${aliyun.oss.accessKeyId}")
    private String accessKeyId;

    @Value("${aliyun.oss.accessKeySecret}")
    private String accessKeySecret;

    /**
     * 生成前端直传 OSS 的临时上传凭证
     * 
     * @param dir 上传目录前缀（如：knowledge/user/123/）
     * @param filename 原始文件名（可选，用于生成唯一文件名）
     * @return 包含 policy、signature、accessKeyId 等信息的 Map
     */
    public Map<String, String> generateUploadPolicy(String dir, String filename) {
        try {
            // 生成唯一文件名（只保留扩展名，避免中文乱码）
            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String uuid = UUID.randomUUID().toString().replace("-", "");
            
            // 提取文件扩展名
            String ext = "";
            if (filename != null && filename.contains(".")) {
                ext = filename.substring(filename.lastIndexOf("."));
            }
            
            String objectName = dir + date + "/" + uuid + ext;

            // 设置上传策略有效期（30分钟）
            long expireTime = 30 * 60 * 1000L;
            long expireEndTime = System.currentTimeMillis() + expireTime;
            Date expiration = new Date(expireEndTime);

            // 设置上传文件大小限制（100MB）
            PolicyConditions policyConds = new PolicyConditions();
            policyConds.addConditionItem(PolicyConditions.COND_CONTENT_LENGTH_RANGE, 0, 100 * 1024 * 1024);
            policyConds.addConditionItem(MatchMode.StartWith, PolicyConditions.COND_KEY, dir);

            // 生成 policy
            String postPolicy = ossClient.generatePostPolicy(expiration, policyConds);
            byte[] binaryData = postPolicy.getBytes(StandardCharsets.UTF_8);
            String encodedPolicy = BinaryUtil.toBase64String(binaryData);

            // 生成签名
            String postSignature = ossClient.calculatePostSignature(postPolicy);

            // 构建返回结果
            Map<String, String> result = new HashMap<>();
            result.put("accessKeyId", accessKeyId);
            result.put("policy", encodedPolicy);
            result.put("signature", postSignature);
            result.put("dir", dir);
            result.put("host", getOssHost());
            result.put("expire", String.valueOf(expireEndTime / 1000));
            result.put("key", objectName); // 建议的完整文件路径
            
            log.info("生成OSS上传凭证成功，目录: {}, 文件: {}", dir, objectName);
            
            return result;
        } catch (Exception e) {
            log.error("生成OSS上传凭证失败", e);
            throw new ServiceException("生成上传凭证失败: " + e.getMessage());
        }
    }

    /**
     * 获取 OSS Host
     */
    private String getOssHost() {
        if (urlPrefix != null && !urlPrefix.isEmpty()) {
            return urlPrefix;
        }
        return "https://" + bucketName + "." + endpoint.replace("https://", "").replace("http://", "");
    }

    /**
     * 删除文件
     * 
     * @param objectName OSS文件路径
     */
    public void deleteFile(String objectName) {
        try {
            ossClient.deleteObject(bucketName, objectName);
            log.info("文件删除成功，OSS路径: {}", objectName);
        } catch (Exception e) {
            log.error("文件删除失败", e);
            throw new ServiceException("文件删除失败: " + e.getMessage());
        }
    }

    /**
     * 获取文件访问URL
     * 
     * @param objectName OSS文件路径
     * @return 完整URL
     */
    public String getFileUrl(String objectName) {
        return getOssHost() + "/" + objectName;
    }

    /**
     * 下载文件到本地临时目录（用于文档解析）
     * 
     * @param objectName OSS文件路径
     * @return 本地临时文件路径
     */
    public String downloadToTemp(String objectName) {
        try {
            String tempDir = System.getProperty("java.io.tmpdir");
            String localPath = tempDir + "/" + UUID.randomUUID().toString() + "_" + getFileName(objectName);
            
            ossClient.getObject(new com.aliyun.oss.model.GetObjectRequest(bucketName, objectName), 
                    new java.io.File(localPath));
            
            log.info("文件下载到本地: {}", localPath);
            return localPath;
        } catch (Exception e) {
            log.error("文件下载失败", e);
            throw new ServiceException("文件下载失败: " + e.getMessage());
        }
    }

    /**
     * 从OSS路径中提取文件名
     */
    private String getFileName(String objectName) {
        return objectName.substring(objectName.lastIndexOf("/") + 1);
    }

    /**
     * 验证文件是否存在
     * 
     * @param objectName OSS文件路径
     * @return 是否存在
     */
    public boolean doesObjectExist(String objectName) {
        try {
            return ossClient.doesObjectExist(bucketName, objectName);
        } catch (Exception e) {
            log.error("检查文件是否存在失败", e);
            return false;
        }
    }
}
