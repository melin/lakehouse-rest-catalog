package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.support.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
        return build(List.of(scope));
    }

    /**
     * 多作用域的 policy：服务端自己取票时一次覆盖 catalog 的多个允许位置。
     *
     * <p><b>为什么合成一条语句而不是每个位置各来一条。</b>S3 的 {@code Resource} 与
     * {@code Condition} 都接受数组，数组语义就是「任一匹配」；位置多了之后一条语句
     * 与十条语句的判定结果相同，但 policy 文档有 2048 字符的内联上限（设计稿 §6.1），
     * 逐个位置叠语句会先撞到上限，而撞上限的报错是 {@code PolicyTooLarge}——
     * 又一个与「凭据配错了」毫无关系的现象。
     *
     * <p><b>单作用域的输出必须与改造前逐字节相同</b>，否则「默认关闭时行为不变」这条
     * 承诺会在策略这一层悄悄失效：合并之后 {@code Resource} 仍是一个元素的数组，
     * {@code s3:prefix} 仍是同一个列表，顺序也照原样。
     *
     * @param scopes 作用域列表；空列表时只产出读语句的空壳，调用方应当先拒绝签发
     */
    public static String build(List<CredentialScope> scopes) {
        List<CredentialScope> effective = scopes == null ? List.of() : List.copyOf(scopes);
        List<Map<String, Object>> statements = new ArrayList<>();
        statements.add(readObjects(effective));
        statements.add(listBucket(effective));
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
        statement.put("Sid", "ReadTableObjects");
        statement.put("Effect", "Allow");
        statement.put("Action", List.of("s3:GetObject", "s3:GetObjectVersion"));
        statement.put("Resource", objectArns(scopes, false));
        return statement;
    }

    private static Map<String, Object> listBucket(List<CredentialScope> scopes) {
        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("Sid", "ListTablePrefix");
        statement.put("Effect", "Allow");
        statement.put("Action", List.of("s3:ListBucket", "s3:GetBucketLocation"));
        // Resource 是桶；前缀走 Condition。写成 arn:aws:s3:::bucket/prefix 是无效的，
        // 而它不会报错，只会让客户端在列目录时被拒。
        Map<String, Object> stringLike = new LinkedHashMap<>();
        stringLike.put("s3:prefix", merged(scopes, false, true));
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("StringLike", stringLike);
        statement.put("Resource", buckets(scopes));
        statement.put("Condition", condition);
        return statement;
    }

    private static Map<String, Object> writeObjects(List<CredentialScope> scopes) {
        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("Sid", "WriteTableObjects");
        statement.put("Effect", "Allow");
        statement.put("Action", List.of("s3:PutObject", "s3:AbortMultipartUpload", "s3:DeleteObject"));
        statement.put("Resource", objectArns(scopes, true));
        return statement;
    }

    /** 所有桶的 ARN（去重保序）：{@code ListBucket} 的 {@code Resource} 用它。 */
    private static List<String> buckets(List<CredentialScope> scopes) {
        LinkedHashSet<String> arns = new LinkedHashSet<>();
        for (CredentialScope scope : scopes) {
            arns.add(scope.bucketArn());
        }
        return List.copyOf(arns);
    }

    /** 对象级 ARN；{@code write=true} 时取写前缀，否则取读前缀。 */
    private static List<String> objectArns(List<CredentialScope> scopes, boolean write) {
        LinkedHashSet<String> arns = new LinkedHashSet<>();
        for (CredentialScope scope : scopes) {
            List<String> prefixes = write ? scope.writePrefixes() : scope.readPrefixes();
            arns.addAll(scope.objectArns(prefixes));
        }
        return List.copyOf(arns);
    }

    /** 合并前缀列表：{@code listOnly=true} 时取 {@code ListBucket} 的条件前缀。 */
    private static List<String> merged(List<CredentialScope> scopes, boolean write, boolean listOnly) {
        LinkedHashSet<String> prefixes = new LinkedHashSet<>();
        for (CredentialScope scope : scopes) {
            if (listOnly) {
                prefixes.addAll(scope.listPrefixes());
            } else {
                prefixes.addAll(write ? scope.writePrefixes() : scope.readPrefixes());
            }
        }
        return List.copyOf(prefixes);
    }
}
