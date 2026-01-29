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
     * v4.3 - 临时文件目录配置（与KnowledgeBaseController保持一致）
     * 默认使用 src/main/resources/temp 目录
     */
    @Value("${upload.temp.dir:}")
    private String tempDirConfig;

    /**
     * 获取临时文件目录（绝对路径）
     */
    private String getTempDir() {
        if (tempDirConfig != null && !tempDirConfig.isEmpty()) {
            java.io.File dir = new java.io.File(tempDirConfig);
            if (dir.isAbsolute()) {
                return tempDirConfig;
            }
            return new java.io.File(System.getProperty("user.dir"), tempDirConfig).getAbsolutePath() + java.io.File.separator;
        }
        // 默认：src/main/resources/temp
        return new java.io.File(System.getProperty("user.dir"), "src" + java.io.File.separator + "main" + java.io.File.separator + "resources" + java.io.File.separator + "temp" + java.io.File.separator).getAbsolutePath() + java.io.File.separator;
    }

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
            result.put("OSSAccessKeyId", accessKeyId); // 兼容标准字段名
            result.put("policy", encodedPolicy);
            result.put("signature", postSignature);
            result.put("Signature", postSignature); // 兼容标准字段名
            result.put("dir", dir);
            result.put("host", getOssHost());
            result.put("expire", String.valueOf(expireEndTime / 1000));
            result.put("key", objectName); // 建议的完整文件路径
            result.put("success_action_status", "200"); // 默认返回200状态码
            
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
     * v4.3 - 使用配置的临时目录，而非系统临时目录
     *
     * @param objectName OSS文件路径
     * @return 本地临时文件路径
     */
    public String downloadToTemp(String objectName) {
        try {
            // 获取临时目录（绝对路径）
            String tempDir = getTempDir();
            java.io.File tempDirFile = new java.io.File(tempDir);
            if (!tempDirFile.exists()) {
                tempDirFile.mkdirs();
            }

            String localPath = tempDir + UUID.randomUUID().toString() + "_" + getFileName(objectName);

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
     * 后端直接上传文件到 OSS
     *
     * @param fileBytes 文件字节数组
     * @param dir 上传目录（如：knowledge/user/123/）
     * @param filename 原始文件名
     * @return OSS 文件路径（objectName）
     */
    public String uploadFile(byte[] fileBytes, String dir, String filename) {
        try {
            // 生成唯一文件名
            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String uuid = UUID.randomUUID().toString().replace("-", "");

            // 提取文件扩展名
            String ext = "";
            if (filename != null && filename.contains(".")) {
                ext = filename.substring(filename.lastIndexOf("."));
            }

            String objectName = dir + date + "/" + uuid + ext;

            // 上传文件
            ossClient.putObject(bucketName, objectName, new java.io.ByteArrayInputStream(fileBytes));

            log.info("后端上传文件到OSS成功，目录: {}, 文件: {}, 大小: {} bytes",
                    dir, objectName, fileBytes.length);

            return objectName;
        } catch (Exception e) {
            log.error("后端上传文件到OSS失败", e);
            throw new ServiceException("文件上传失败: " + e.getMessage());
        }
    }

    /**
     * 后端直接上传文件到 OSS（使用 File 对象）
     *
     * @param file 本地文件
     * @param dir 上传目录（如：knowledge/user/123/）
     * @return OSS 文件路径（objectName）
     */
    public String uploadFile(java.io.File file, String dir) {
        try {
            // 生成唯一文件名
            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String uuid = UUID.randomUUID().toString().replace("-", "");

            // 提取文件扩展名
            String ext = "";
            String filename = file.getName();
            if (filename.contains(".")) {
                ext = filename.substring(filename.lastIndexOf("."));
            }

            String objectName = dir + date + "/" + uuid + ext;

            // 上传文件
            ossClient.putObject(bucketName, objectName, file);

            log.info("后端上传文件到OSS成功，目录: {}, 文件: {}, 大小: {} bytes",
                    dir, objectName, file.length());

            return objectName;
        } catch (Exception e) {
            log.error("后端上传文件到OSS失败", e);
            throw new ServiceException("文件上传失败: " + e.getMessage());
        }
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

    /**
     * v4.3 - 直接从 OSS 读取文件内容为字节数组（不下载到临时文件）
     * 用于文档处理时直接读取 OSS 文件，避免不必要的下载
     *
     * @param objectName OSS文件路径
     * @return 文件字节数组
     */
    public byte[] readFileAsBytes(String objectName) {
        try {
            com.aliyun.oss.model.OSSObject ossObject = ossClient.getObject(bucketName, objectName);
            try (java.io.InputStream inputStream = ossObject.getObjectContent()) {
                byte[] bytes = inputStream.readAllBytes();
                log.info("从OSS读取文件成功，路径: {}, 大小: {} bytes", objectName, bytes.length);
                return bytes;
            }
        } catch (Exception e) {
            log.error("从OSS读取文件失败，路径: {}", objectName, e);
            throw new ServiceException("从OSS读取文件失败: " + e.getMessage());
        }
    }
}
