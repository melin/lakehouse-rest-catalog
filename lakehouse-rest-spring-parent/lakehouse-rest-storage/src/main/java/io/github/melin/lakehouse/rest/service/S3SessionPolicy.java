package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.support.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AWS STS {@code AssumeRole} 的 session policy：把一次签发的权限收窄到这张表。
 *
 * <p><b>session policy 只能收窄、不能放大。</b>它与角色自身的策略取**交集**，
 * 所以这里写少了会 403、写多了不会越权。这个单向性让「先保守、按需放行」成为正确
 * 的演进方向，也意味着**漏写一条的代价是引擎在某个路径上 403**，因此三条语句
 * 各自的存在理由要写清楚，不能凭感觉删。
 *
 * <p>三段的结构与理由（对照设计 §5.5）：
 * <ol>
 *   <li><b>读对象</b>：{@code s3:GetObject} / {@code s3:GetObjectVersion}，
 *       {@code Resource} 收在表前缀下的对象。Iceberg 的元数据与数据文件在同一
 *       location 下，所以不用额外分支。</li>
 *   <li><b>列目录</b>：{@code s3:ListBucket} 的 {@code Resource} 是**桶**，
 *       前缀必须走 {@code Condition.StringLike."s3:prefix"}。这一条最容易写错——
 *       把前缀拼进 ARN 会得到一个语法合法、永远不匹配、也不报错的策略，
 *       表现为「读单文件正常、列目录 403」。</li>
 *   <li><b>写</b>：{@code PutObject} / {@code AbortMultipartUpload} / {@code DeleteObject}，
 *       只在客户端要写时出现。{@code AbortMultipartUpload} 不是可有可无的：
 *       大文件上传中断后不清理会留下按存储计费的碎片，而清理它需要这个动作。</li>
 * </ol>
 *
 * <p>{@code GetBucketLocation} 归在第二条里。它只读桶级元数据，成本为零；
 * 漏了它，部分 SDK 会在初始化阶段报一个与权限完全不像的错（区域探测失败被包装成
 * 「无法连接」）。
 */
public final class S3SessionPolicy {

    private static final String VERSION = "2012-10-17";

    private S3SessionPolicy() {
    }

    /**
     * 生成 policy JSON。
     *
     * <p>用 {@link LinkedHashMap} 固定键序：这份字符串会被写进 STS 请求、也会进单测断言，
     * 顺序漂移会让「内容没变」的改动看起来像改动。
     *
     * @param scope 作用域，不可为 {@code null}
     */
    public static String build(CredentialScope scope) {
        List<Map<String, Object>> statements = new ArrayList<>();
        statements.add(readObjects(scope));
        statements.add(listBucket(scope));
        if (!scope.writePrefixes().isEmpty()) {
            statements.add(writeObjects(scope));
        }
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("Version", VERSION);
        policy.put("Statement", statements);
        return Json.write(policy);
    }

    private static Map<String, Object> readObjects(CredentialScope scope) {
        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("Sid", "ReadTableObjects");
        statement.put("Effect", "Allow");
        statement.put("Action", List.of("s3:GetObject", "s3:GetObjectVersion"));
        statement.put("Resource", scope.objectArns(scope.readPrefixes()));
        return statement;
    }

    private static Map<String, Object> listBucket(CredentialScope scope) {
        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("Sid", "ListTablePrefix");
        statement.put("Effect", "Allow");
        statement.put("Action", List.of("s3:ListBucket", "s3:GetBucketLocation"));
        // Resource 是桶；前缀走 Condition。写成 arn:aws:s3:::bucket/prefix 是无效的，
        // 而它不会报错，只会让客户端在列目录时被拒。
        statement.put("Resource", List.of(scope.bucketArn()));
        Map<String, Object> stringLike = new LinkedHashMap<>();
        stringLike.put("s3:prefix", scope.listPrefixes());
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("StringLike", stringLike);
        statement.put("Condition", condition);
        return statement;
    }

    private static Map<String, Object> writeObjects(CredentialScope scope) {
        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("Sid", "WriteTableObjects");
        statement.put("Effect", "Allow");
        statement.put("Action", List.of("s3:PutObject", "s3:AbortMultipartUpload", "s3:DeleteObject"));
        statement.put("Resource", scope.objectArns(scope.writePrefixes()));
        return statement;
    }
}
