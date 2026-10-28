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

    // ------------------------------------------------------------------ 容器格式（Lance 4.x）

    /**
     * 真客户端写的 manifest 不是裸 protobuf，而是带 footer 的容器。
     *
     * <p>这里前面塞一段「索引段」，于是 footer 里的偏移**不是 0**——正是这个偏移
     * 把「按 footer 取」和「按第 0 字节取」区分开。按第 0 字节取不会抛异常，
     * 它会解出一份字段名乱码的 protobuf：schema 悄悄错，比报错难查得多。
     */
    @Test
    void readsTheManifestSectionThroughTheFooterOffset() {
        byte[] bare = LanceManifestFixture.manifest(4L, 1_700_000_000L, nested(), 2, 1);
        LanceManifest.Manifest manifest = LanceManifest.parse(LanceManifestFixture.container(bare, 12));

        assertEquals(4L, manifest.version());
        assertEquals(1_700_000_000_000L, manifest.timestampMillis());
        assertEquals(5, manifest.fields().size());
        assertEquals(2, manifest.fragmentCount());
        assertEquals(2L, manifest.deletedRows());
    }

    /** 没有前置段时偏移是 0，也要认——否则「第一版就是最新版」的表会读不出 schema。 */
    @Test
    void readsTheContainerWithoutAnyPrefixSection() {
        byte[] bare = LanceManifestFixture.manifest(1L, 1L,
                List.of(LanceManifestFixture.leaf("a", 1, 0, "int32")), 0, 0);
        LanceManifest.Manifest manifest = LanceManifest.parse(LanceManifestFixture.container(bare, 0));
        assertEquals(1L, manifest.version());
        assertEquals(1, manifest.fields().size());
    }

    /**
     * footer 指向文件外、或段长度声明得比文件还大：一律 {@link LanceManifest.Unreadable}。
     *
     * <p>两种都是「文件坏了」，而坏文件的处置必须是**降级**（只回 location），
     * 不是猜一份元数据。因此这里不允许「尽力读出来」。
     */
    @Test
    void brokenContainerFootersAreUnreadable() {
        byte[] bare = LanceManifestFixture.manifest(2L, 1L, nested(), 1, 0);

        byte[] outside = LanceManifestFixture.containerWithFooterPosition(bare, 0, 9_999);
        assertThrows(LanceManifest.Unreadable.class, () -> LanceManifest.parse(outside));

        byte[] overrun = LanceManifestFixture.container(bare, 0);
        overrun[0] = (byte) 0xFF;
        overrun[1] = (byte) 0xFF;
        assertThrows(LanceManifest.Unreadable.class, () -> LanceManifest.parse(overrun));
    }

    // ------------------------------------------------------------------ JSON Arrow

    /**
     * 现代形状（无 {@code type}、顶层 {@code parent_id = -1}）也要还原出**同一个树**。
     *
     * <p>这条是被真客户端逼出来的：4.x 不再写 {@code type}，而 protobuf 把「没写」
     * 读成枚举 0 = {@code PARENT}。照它理解，`a: int32` 就成了一个没有成员的 struct——
     * schema 变成一张**空表**，而且整个过程不报错。所以这里断言的不只是「不抛异常」，
     * 是「顶层真的是两列、且 a 是 int」。
     */
    @Test
    void modernManifestWithoutTypeStillBuildsTheSameTree() {
        byte[] bytes = LanceManifestFixture.modernManifest(2L, 1L, nested(), 1, 0);
        LanceManifest.Manifest manifest = LanceManifest.parse(bytes);

        assertEquals(2L, manifest.version());
        assertEquals(5, manifest.fields().size());
        // 角色由 logical_type 决定，而不是那个缺省的枚举值
        assertEquals(LanceManifest.TYPE_LEAF, manifest.fields().get(0).type(), "a 是叶子列");
        assertEquals(LanceManifest.TYPE_PARENT, manifest.fields().get(1).type(), "b 是 struct");
        assertEquals(LanceManifest.TYPE_REPEATED, manifest.fields().get(2).type(), "b.c 是 list");
        assertEquals(-1, manifest.fields().get(0).parentId(), "顶层的 parent_id 是 -1");

        Map<String, Object> schema = LanceJsonArrow.schema(manifest);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) schema.get("fields");
        assertEquals(2, fields.size(), "顶层应是 a 与 b，实际: " + fields);
        assertEquals("a", fields.get(0).get("name"));
        assertEquals("b", fields.get(1).get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) fields.get(0).get("type");
        assertEquals("int", a.get("type"));
        assertEquals(32, a.get("bitWidth"));
    }

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
