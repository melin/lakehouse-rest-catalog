package io.github.melin.lakehouse.rest.lance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.lance.support.LanceJsonArrow;
import io.github.melin.lakehouse.rest.lance.support.LanceManifest;
import io.github.melin.lakehouse.rest.lance.support.LanceVersionNaming;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * manifest 解析与命名方案的**纯单测**（不启 Spring、不碰存储）。
 *
 * <p>这里守的三件事恰好是 P1 最可能悄悄错的三处：
 *
 * <ol>
 *   <li><b>版本号与时间戳</b>——{@code timestamp} 是嵌套的
 *       {@code google.protobuf.Timestamp}，秒与纳秒分开编码；</li>
 *   <li><b>嵌套 schema</b>——{@code fields} 是扁平的深度优先列表，
 *       父子关系在 {@code parent_id} 里，还原错了客户端就会按错的布局读数据；</li>
 *   <li><b>未知字段必须被跳过</b>——这是「抗 Lance 版本漂移」的全部依据。</li>
 * </ol>
 */
class LanceManifestReaderTests {

    /** {@code a: int32, b: struct { c: list<int32>, d: int32 }}，与规范文档里的例子同形。 */
    private static List<LanceManifestFixture.FieldSpec> nested() {
        return List.of(
                LanceManifestFixture.leaf("a", 1, 0, "int32"),
                LanceManifestFixture.parent("b", 2, 0),
                LanceManifestFixture.repeated("b.c", 3, 2),
                LanceManifestFixture.leaf("b.c", 4, 3, "int32"),
                LanceManifestFixture.leaf("b.d", 5, 2, "int32"));
    }

    @Test
    void readsVersionTimestampFragmentsAndDeletedRows() {
        byte[] bytes = LanceManifestFixture.manifest(7L, 1_700_000_000L, nested(), 3, 4);
        LanceManifest.Manifest manifest = LanceManifest.parse(bytes);

        assertEquals(7L, manifest.version());
        assertEquals(1_700_000_000_000L, manifest.timestampMillis());
        assertEquals(5, manifest.fields().size());
        assertEquals(3, manifest.fragmentCount());
        assertEquals(12L, manifest.deletedRows());
    }

    /** 没有 {@code timestamp} 字段时是 {@code null}，而不是 epoch 0——后者会被当成 1970 年。 */
    @Test
    void missingTimestampIsNullNotEpoch() {
        LanceManifest.Manifest manifest =
                LanceManifest.parse(LanceManifestFixture.manifest(1L, null, nested(), 0, 0));
        assertNull(manifest.timestampMillis());
    }

    /** 未知字段：protobuf 的基本性质，也是 P1 抗版本漂移的唯一依据。 */
    @Test
    void unknownFieldsAreSkipped() {
        byte[] known = LanceManifestFixture.manifest(2L, 1L, nested(), 1, 0);
        byte[] withUnknown = new byte[known.length + LanceManifestFixture.unknownField().length];
        System.arraycopy(known, 0, withUnknown, 0, known.length);
        System.arraycopy(LanceManifestFixture.unknownField(), 0, withUnknown, known.length,
                LanceManifestFixture.unknownField().length);

        LanceManifest.Manifest manifest = LanceManifest.parse(withUnknown);
        assertEquals(2L, manifest.version());
        assertEquals(5, manifest.fields().size());
    }

    @Test
    void malformedBytesAreReportedAsUnreadable() {
        assertThrows(LanceManifest.Unreadable.class,
                () -> LanceManifest.parse(LanceManifestFixture.truncated()));
        assertThrows(LanceManifest.Unreadable.class, () -> LanceManifest.parse(new byte[0]));
        assertThrows(LanceManifest.Unreadable.class, () -> LanceManifest.parse(null));
    }

    // ------------------------------------------------------------------ JSON Arrow

    @Test
    void schemaKeepsTheNestingAndMapsLogicalTypes() {
        Map<String, Object> schema = LanceJsonArrow.schema(
                LanceManifest.parse(LanceManifestFixture.manifest(1L, 1L, nested(), 0, 0)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) schema.get("fields");
        assertEquals(2, fields.size(), "顶层只有 a 与 b: " + fields);
        assertEquals("a", fields.get(0).get("name"));

        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) fields.get(0).get("type");
        assertEquals("int", a.get("type"));
        assertEquals(32, a.get("bitWidth"));
        assertEquals(true, a.get("isSigned"));

        Map<String, Object> b = fields.get(1);
        assertEquals("b", b.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> struct = (Map<String, Object>) b.get("type");
        assertEquals("struct", struct.get("type"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) struct.get("fields");
        assertEquals(2, members.size());

        Map<String, Object> list = members.get(0);
        assertEquals("b.c", list.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> listType = (Map<String, Object>) list.get("type");
        assertEquals("list", listType.get("type"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> element = (List<Map<String, Object>>) listType.get("fields");
        assertEquals(1, element.size());
        assertEquals("item", element.get(0).get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> itemType = (Map<String, Object>) element.get(0).get("type");
        assertEquals("int", itemType.get("type"));
    }

    /** 认不出的类型原样透传：猜一个类型会让客户端按错的布局读数据。 */
    @Test
    void unknownLogicalTypeIsPassedThroughVerbatim() {
        byte[] bytes = LanceManifestFixture.manifest(1L, 1L,
                List.of(LanceManifestFixture.leaf("weird", 1, 0, "quantum:42")), 0, 0);
        Map<String, Object> schema = LanceJsonArrow.schema(LanceManifest.parse(bytes));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) schema.get("fields");
        @SuppressWarnings("unchecked")
        Map<String, Object> type = (Map<String, Object>) fields.get(0).get("type");
        assertEquals("quantum:42", type.get("type"));
    }

    // ------------------------------------------------------------------ 命名方案

    @Test
    void v1NamesAreReadAsPlainNumbers() {
        assertEquals(1L, LanceVersionNaming.versionOf("1.manifest"));
        assertEquals(42L, LanceVersionNaming.versionOf("42.manifest"));
        assertNull(LanceVersionNaming.versionOf("latest_version_hint.json"));
        assertNull(LanceVersionNaming.versionOf("nope.manifest"));
    }

    /**
     * V2 是 {@code u64::MAX - version} 补零 20 位。
     *
     * <p>这里同时验「读」与「写」两个方向：只验一个方向的话，
     * 「读出来的号」与「拼出来的文件名」可能在某一天不再互逆，
     * 而那时 {@code DescribeTableVersion} 会去探测一个不存在的文件。
     */
    @Test
    void v2NamesRoundTripThroughTheUnsignedInverse() {
        String latest = LanceVersionNaming.manifestName(0, true);
        assertTrue(latest.startsWith("18446744073709551615"), latest);
        assertTrue(latest.endsWith(".manifest"), latest);
        assertEquals(0L, LanceVersionNaming.versionOf(latest));

        for (long version : new long[]{1L, 2L, 10L, 12345L}) {
            String name = LanceVersionNaming.manifestName(version, true);
            assertEquals(version, LanceVersionNaming.versionOf(name), name);
            // V2 的意义就在字典序：版本号越大，文件名越小
            assertTrue(name.compareTo(LanceVersionNaming.manifestName(version + 1, true)) > 0, name);
        }
    }
}
