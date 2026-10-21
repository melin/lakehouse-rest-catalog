package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.dto.PreviewDtos.PartitionValue;
import io.github.melin.lakehouse.rest.preview.ColumnFilter;
import io.github.melin.lakehouse.rest.service.PreviewCursorCodec;
import io.github.melin.lakehouse.rest.service.PreviewCursorCodec.Cursor;
import io.github.melin.lakehouse.rest.support.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 游标编解码的单元测试。
 *
 * <p>游标是"无状态续读"的全部依据：它错了不会报错，只会**行错位**——用户看到的
 * 是两页数据里有些行重复、有些行消失，而接口一路 200。所以这一层要钉死三件事：
 * 内容能原样回来（含中文与分隔符）、被人改过就拒、条件变了摘要就变。
 */
class PreviewCursorCodecTests {

    private static final String KEY = base64Key("0123456789abcdef0123456789abcdef");

    private static final String OTHER_KEY = base64Key("fedcba9876543210fedcba9876543210");

    private final PreviewCursorCodec codec = new PreviewCursorCodec(properties(KEY));

    // ------------------------------------------------------------------ 往返

    /** 所有字段原样回来——包括中文 label 与带 {@code &} {@code =} 的分区值。 */
    @Test
    void everyFieldSurvivesARoundTrip() {
        Cursor original = new Cursor(8L, 3, 42L, "a1b2c3d4",
                List.of(new PartitionValue("dt", "identity", "2026-10-21"),
                        new PartitionValue("region", "identity", "cn-east&south=1")),
                "auto", true, 2, "dt = 2026-10-21 / region = cn-east&south=1（自动收敛）");

        Cursor decoded = codec.decode(codec.encode(original));

        assertEquals(original.snapshotId(), decoded.snapshotId());
        assertEquals(original.splitOffset(), decoded.splitOffset());
        assertEquals(original.splitRows(), decoded.splitRows());
        assertEquals(original.fingerprint(), decoded.fingerprint());
        assertEquals(original.resolved(), decoded.resolved());
        assertEquals(original.mode(), decoded.mode());
        assertEquals(original.auto(), decoded.auto());
        assertEquals(original.depth(), decoded.depth());
        assertEquals(original.label(), decoded.label());
    }

    /** 空表（还没有任何快照）时 {@code s} 是空：不能编成 {@code 0}，那会指向一个不存在的快照。 */
    @Test
    void aTableWithoutSnapshotsKeepsANullSnapshotId() {
        Cursor decoded = codec.decode(codec.encode(
                new Cursor(null, 0, 0L, "ffffffff", List.of(), "auto", false, 0, null)));

        assertNull(decoded.snapshotId());
        assertNull(decoded.label());
        assertTrue(decoded.resolved().isEmpty());
    }

    /** 空串 / null 表示"这是第一页"，不是错误。 */
    @Test
    void blankMeansFirstPage() {
        assertNull(codec.decode(null));
        assertNull(codec.decode(""));
        assertNull(codec.decode("   "));
    }

    /** 编码结果里不该出现 base64url 之外的字符（它会进 query 与日志）。 */
    @Test
    void theEncodedFormIsUrlSafe() {
        String text = codec.encode(new Cursor(8L, 0, 0L, "a1b2c3d4",
                List.of(new PartitionValue("dt", "identity", "2026-10-21")), "auto", true, 1, "dt = 1"));

        assertTrue(text.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.'),
                text);
    }

    // ------------------------------------------------------------------ 拒收

    /** 内容被改过（签名对不上）：400，让用户重新取第一页。 */
    @Test
    void aTamperedPayloadIsRejected() {
        String text = codec.encode(new Cursor(8L, 3, 42L, "a1b2c3d4", List.of(), "auto", true, 1, null));
        int dot = text.lastIndexOf('.');
        String body = new String(Base64.getUrlDecoder().decode(text.substring(0, dot)), StandardCharsets.UTF_8);
        String tampered = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(body.replace("r=42", "r=99").getBytes(StandardCharsets.UTF_8))
                + text.substring(dot);

        ApiException exception = assertThrows(ApiException.class, () -> codec.decode(tampered));
        assertEquals(400, exception.getStatus());
    }

    /**
     * 换了密钥（另一个实例、或配了不同的 signing-key）：拒收而不是"读出垃圾"。
     *
     * <p>这正是多实例部署没配统一密钥时的现象：每次请求落到别的实例就 400。
     * 拒收能让人立刻看出是配置问题；容忍则会读到错位的数据。
     */
    @Test
    void aCursorSignedWithAnotherKeyIsRejected() {
        String text = codec.encode(new Cursor(8L, 0, 0L, "a1b2c3d4", List.of(), "auto", true, 1, null));

        PreviewCursorCodec other = new PreviewCursorCodec(properties(OTHER_KEY));
        assertEquals(400, assertThrows(ApiException.class, () -> other.decode(text)).getStatus());
    }

    /** 不是游标的东西（随便一串）：400，不是 500。 */
    @Test
    void garbageIsABadRequest() {
        assertEquals(400, assertThrows(ApiException.class, () -> codec.decode("not-a-cursor")).getStatus());
        assertEquals(400, assertThrows(ApiException.class, () -> codec.decode("abc.")).getStatus());
        assertEquals(400, assertThrows(ApiException.class,
                () -> codec.decode("Zm9v.YmFy")).getStatus(), "签名不符也是 400");
    }

    /**
     * 随机密钥模式的后果被接受：重启后旧游标 400，用户重新加载第一页。
     *
     * <p>这里断言的是"不会读到错位数据"，而不是"游标必须失效"——后者是配置问题，
     * 前者是安全问题。
     */
    @Test
    void aProcessWithoutAConfiguredKeyStillSignsConsistently() {
        PreviewCursorCodec first = new PreviewCursorCodec(new RestServerProperties());
        PreviewCursorCodec second = new PreviewCursorCodec(new RestServerProperties());
        String text = first.encode(new Cursor(1L, 0, 0L, "a1b2c3d4", List.of(), "auto", true, 1, null));

        assertEquals(0, first.decode(text).splitOffset(), "同一个实例内必须能解开");
        assertFalse(text.isEmpty());
        assertEquals(400, assertThrows(ApiException.class, () -> second.decode(text)).getStatus(),
                "另一个实例用的是另一把随机密钥，因此解不开（重取第一页即可）");
    }

    // ------------------------------------------------------------------ 摘要

    /** 同样的条件摘出同样的值：否则每翻一页都会被判成"条件变了"。 */
    @Test
    void theFingerprintIsStableForTheSameInput() {
        List<ColumnFilter> filters = List.of(new ColumnFilter("amount", "ge", 100));
        List<PartitionValue> resolved = List.of(new PartitionValue("dt", "identity", "2026-10-21"));

        assertEquals(PreviewCursorCodec.fingerprint(filters, List.of("id", "amount"), resolved),
                PreviewCursorCodec.fingerprint(filters, List.of("id", "amount"), resolved));
    }

    /**
     * 摘的是**解析后**的分区值：{@code auto} 在不同时刻指向不同分区，拿它当摘要等于没摘。
     */
    @Test
    void theFingerprintCoversTheResolvedPartition() {
        String first = PreviewCursorCodec.fingerprint(List.of(), List.of("id"),
                List.of(new PartitionValue("dt", "identity", "2026-10-20")));
        String second = PreviewCursorCodec.fingerprint(List.of(), List.of("id"),
                List.of(new PartitionValue("dt", "identity", "2026-10-21")));

        assertNotEquals(first, second);
    }

    /** 换列、换算子、换值都要变——否则"条件变了却拿旧游标"会被放过。 */
    @Test
    void theFingerprintCoversColumnsOperatorsAndValues() {
        String base = PreviewCursorCodec.fingerprint(
                List.of(new ColumnFilter("amount", "ge", 100)), List.of("id"), List.of());

        assertNotEquals(base, PreviewCursorCodec.fingerprint(
                List.of(new ColumnFilter("amount", "ge", 100)), List.of("id", "name"), List.of()));
        assertNotEquals(base, PreviewCursorCodec.fingerprint(
                List.of(new ColumnFilter("amount", "gt", 100)), List.of("id"), List.of()));
        assertNotEquals(base, PreviewCursorCodec.fingerprint(
                List.of(new ColumnFilter("amount", "ge", 101)), List.of("id"), List.of()));
    }

    /**
     * {@code 1} 与 {@code "1"} 是两种值，摘要必须能区分。
     *
     * <p>否则一个数字条件被改成字符串条件（语义不同：一个走数值比较、一个走字典序）
     * 时摘要不变，"条件变了"就检测不出来。
     */
    @Test
    void theFingerprintDistinguishesNumbersFromStrings() {
        assertNotEquals(
                PreviewCursorCodec.fingerprint(List.of(new ColumnFilter("id", "eq", 1)), List.of("id"), List.of()),
                PreviewCursorCodec.fingerprint(List.of(new ColumnFilter("id", "eq", "1")), List.of("id"), List.of()));
    }

    // ------------------------------------------------------------------ 工具

    private static RestServerProperties properties(String signingKey) {
        RestServerProperties properties = new RestServerProperties();
        properties.getAuth().getAccessToken().setSigningKey(signingKey);
        return properties;
    }

    private static String base64Key(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
