package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.support.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 阿里云 RAM 的受限制 policy：把一次 {@code AssumeRole} 的权限收窄到这张表。
 *
 * <p>与 S3 的 {@link S3SessionPolicy} 是同一条思路（只能收窄、写少了会 403、
 * 写多了不越权），但**资源 ARN 的形状不同**：S3 是
 * {@code arn:aws:s3:::bucket/prefix/*}，OSS 是 {@code acs:oss:*:*:bucket/prefix/*}
 * ——注意中间那两个星号是「区域」与「账号」，OSS 的 ARN 里它们必须占位。
 * 照 S3 的写法复制过来会得到一个语法正确、永不匹配的策略，而它不会报错。
 *
 * <p>三段与 S3 一一对应：
 * <ol>
 *   <li><b>读对象</b>：{@code oss:GetObject} / {@code oss:GetObjectVersion}，
 *       资源收在表前缀下；</li>
 *   <li><b>列对象</b>：{@code oss:ListObjects} 的资源是**桶**，前缀走
 *       {@code Condition.StringLike."oss:Prefix"}——与 S3 的 {@code s3:prefix}
 *       是同一个坑的两种拼法；</li>
 *   <li><b>写</b>：{@code PutObject} / {@code AbortMultipartUpload} / {@code DeleteObject}，
 *       只在客户端要写时出现。{@code AbortMultipartUpload} 的理由与 S3 相同：
 *       大文件上传中断后不清理，会留下按存储计费的碎片。</li>
 * </ol>
 *
 * <p><b>没有 {@code GetBucketLocation} 对应的动作。</b>OSS 的 {@code GetBucketInfo}
 * 不在读表路径上：hadoop-aliyun 不需要探测区域（端点就是区域）。
 * 把 S3 那条「顺手加一个」直接抄过来，只会让 policy 里多一条用不上的动作。
 */
public final class OssStsPolicy {

    private static final String VERSION = "1";

    private OssStsPolicy() {
    }

    /**
     * 生成 policy JSON。
     *
     * <p>用 {@link LinkedHashMap} 固定键序，理由与 {@link S3SessionPolicy#build} 相同：
     * 这份字符串会进单测断言，顺序漂移会让「内容没变」的改动看起来像改动。
     *
     * @param scopes 作用域；空列表时只产出读语句的空壳，调用方应当先拒绝签发
     */
    public static String build(List<CredentialScope> scopes) {
        List<CredentialScope> effective = scopes == null ? List.of() : List.copyOf(scopes);
        List<Map<String, Object>> statements = new ArrayList<>();
        statements.add(readObjects(effective));
        statements.add(listObjects(effective));
        if (effective.stream().anyMatch(scope -> !scope.writePrefixes().isEmpty())) {
            statements.add(writeObjects(effective));
        }
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("Version", VERSION);
        policy.put("Statement", statements);
        return Json.write(policy);
    }

    private static Map<String, Object> readObjects(List<CredentialScope> scopes) {
        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("Effect", "Allow");
        statement.put("Action", List.of("oss:GetObject", "oss:GetObjectVersion"));
        statement.put("Resource", objectArns(scopes, false));
        return statement;
    }

    private static Map<String, Object> listObjects(List<CredentialScope> scopes) {
        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("Effect", "Allow");
        statement.put("Action", List.of("oss:ListObjects"));
        Map<String, Object> stringLike = new LinkedHashMap<>();
        stringLike.put("oss:Prefix", prefixes(scopes, true));
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("StringLike", stringLike);
        statement.put("Resource", bucketArns(scopes));
        statement.put("Condition", condition);
        return statement;
    }

    private static Map<String, Object> writeObjects(List<CredentialScope> scopes) {
        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("Effect", "Allow");
        statement.put("Action", List.of("oss:PutObject", "oss:AbortMultipartUpload",
                "oss:DeleteObject"));
        statement.put("Resource", objectArns(scopes, true));
        return statement;
    }

    /** 桶级 ARN（去重保序）：{@code ListObjects} 的 {@code Resource} 用它。 */
    private static List<String> bucketArns(List<CredentialScope> scopes) {
        LinkedHashSet<String> arns = new LinkedHashSet<>();
        for (CredentialScope scope : scopes) {
            arns.add(bucketArn(scope.bucket()));
        }
        return List.copyOf(arns);
    }

    /** 对象级 ARN；{@code write=true} 取写前缀，否则取读前缀。 */
    private static List<String> objectArns(List<CredentialScope> scopes, boolean write) {
        LinkedHashSet<String> arns = new LinkedHashSet<>();
        for (CredentialScope scope : scopes) {
            for (String prefix : write ? scope.writePrefixes() : scope.readPrefixes()) {
                arns.add(bucketArn(scope.bucket())
                        + (prefix.isEmpty() ? "/*" : "/" + prefix + "/*"));
            }
        }
        return List.copyOf(arns);
    }

    /** 条件里的前缀列表；桶根写成 {@code *}（写空串只能匹配「空前缀」）。 */
    private static List<String> prefixes(List<CredentialScope> scopes, boolean listOnly) {
        LinkedHashSet<String> prefixes = new LinkedHashSet<>();
        for (CredentialScope scope : scopes) {
            if (listOnly) {
                prefixes.addAll(scope.listPrefixes());
            } else {
                prefixes.addAll(scope.readPrefixes());
            }
        }
        return List.copyOf(prefixes);
    }

    private static String bucketArn(String bucket) {
        return "acs:oss:*:*:" + bucket;
    }
}
