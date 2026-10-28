package io.github.melin.lakehouse.rest.lance.support;

import java.util.ArrayList;
import java.util.List;

/**
 * Lance {@code _versions/*.manifest} 的最小解析器。
 *
 * <p><b>为什么手写而不是引 protobuf 库。</b>manifest 是 protobuf，但本模块
 * （{@code protocol-lance}）的 POM 禁掉一切格式库——它是三条模块不变量里
 * 「协议面不引格式实现」的一端。更重要的是：我们只需要 {@code version}、
 * {@code timestamp} 与 {@code fields} 三样东西，而 {@code Manifest} 这个消息
 * 横跨十几个 Lance 版本、字段号只增不改。protobuf 的 wire 格式天生允许
 * 「不认识的字段跳过」，手写解析反而比生成代码**更抗漂移**：新字段出现时我们
 * 只是看不见它，不会解析失败。
 *
 * <p><b>只解必要字段。</b>字段号抄自 {@code lance/protos/format.proto}：
 *
 * <pre>
 * message Manifest {
 *   repeated Field fields    = 1;
 *   repeated DataFragment fragments = 2;
 *   uint64 version          = 3;
 *   google.protobuf.Timestamp timestamp = 7;
 * }
 * message Field {
 *   enum Type { PARENT = 0; REPEATED = 1; LEAF = 2; }
 *   Type type = 1; string name = 2; int32 id = 3; int32 parent_id = 4;
 *   string logical_type = 5; bool nullable = 6;
 * }
 * message DataFragment { uint64 id = 1; repeated DataFile files = 2;
 *                        DeletionFile deletion_file = 3; uint64 physical_rows = 4; }
 * message DeletionFile { ... uint64 num_deleted_rows = 4; }
 * </pre>
 *
 * <p><b>但 {@code Field.type} 不能信。</b>4.x 的写入方已经不写这个字段了（Rust 侧的结构体里
 * 它已经不存在），树形改由 {@code logical_type} 表达；未写出的字段读出来是枚举 0，
 * 而 0 是 {@code PARENT}——照它理解会把每个叶子列都当成 struct。角色的判定见
 * {@link #role}，那是本解析器唯一一处「字段号对了、语义已经变了」的地方。
 *
 * <p><b>读不出就抛 {@link Unreadable}。</b>调用方的处置是**降级**（不回 schema，
 * 只回 location），因此解析失败必须是「能被接住的一种异常」而不是随便一个
 * {@code RuntimeException}——否则调用方只能捕一大片，把真正的 bug 也一起吞掉。
 */
public final class LanceManifest {

    private LanceManifest() {
    }

    /** 字段在 schema 里的角色；值取自 {@code Field.type} 枚举。 */
    public static final int TYPE_PARENT = 0;
    public static final int TYPE_REPEATED = 1;
    public static final int TYPE_LEAF = 2;

    // ------------------------------------------------------------------ 结构

    /**
     * 一个字段（列）。
     *
     * @param type        {@code PARENT} / {@code REPEATED} / {@code LEAF}
     * @param logicalType Lance 的类型串（{@code "int32"} / {@code "struct"} /
     *                    {@code "list"} / {@code "timestamp:us"} …）。它是**原文**，
     *                    不做归一化：认不出就原样交给调用方，比猜一个类型诚实。
     */
    public record Field(int type, String name, int id, int parentId, String logicalType, boolean nullable) {

        public boolean parent() {
            return type == TYPE_PARENT;
        }

        public boolean repeated() {
            return type == TYPE_REPEATED;
        }
    }

    /**
     * manifest 里我们关心的全部内容。
     *
     * @param timestampMillis 版本创建时刻（毫秒）；manifest 里没有 {@code timestamp} 时为 {@code null}
     * @param fragmentCount   fragment 个数（{@code stats.num_fragments}）
     * @param deletedRows     被标记删除的行数（{@code stats.num_deleted_rows}）
     */
    public record Manifest(long version, Long timestampMillis, List<Field> fields,
                           int fragmentCount, long deletedRows) {
    }

    /** manifest 读不出来：坏字节、截断的文件、或者它压根不是 manifest。 */
    public static final class Unreadable extends RuntimeException {

        public Unreadable(String message, Throwable cause) {
            super(message, cause);
        }

        public Unreadable(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 解析一个 {@code _versions/*.manifest} 文件的字节。
     *
     * <p><b>两种文件形状都要认</b>，判别只看尾部 4 字节的魔数：
     *
     * <ul>
     *   <li><b>裸 protobuf</b>（没有 {@code LANC} 魔数）：{@code Manifest} 直接从第 0 字节开始。
     *       这是手工构造的 manifest（测试夹具、以及一些早期写入路径）的形状；</li>
     *   <li><b>4.x 的容器</b>（以 {@code LANC} 结尾）：见 {@link #payload}。</li>
     * </ul>
     */
    public static Manifest parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new Unreadable("manifest is empty");
        }
        try {
            return readManifest(new Cursor(payload(bytes)));
        } catch (Unreadable e) {
            throw e;
        } catch (RuntimeException e) {
            throw new Unreadable("cannot read the lance manifest: " + e.getMessage(), e);
        }
    }

    /** 尾部 footer：{@code manifest 段偏移(i64)} + {@code major(u16)} + {@code minor(u16)} + {@code 魔数}。 */
    private static final int FOOTER_LENGTH = 16;

    /** 容器格式的魔数，落在文件最后 4 字节。 */
    private static final byte[] MAGIC = {'L', 'A', 'N', 'C'};

    /**
     * 从容器里取出 {@code Manifest} 那一段。
     *
     * <p><b>容器长什么样</b>（抄自 Lance 的 {@code lance-table/src/io/manifest.rs}，
     * 写入方是 {@code do_write_manifest}）：
     *
     * <pre>
     * [索引段]        [u32 长度][IndexSection protobuf]      ← 有索引才有
     * [内联事务段]    [u32 长度][Transaction protobuf]       ← 有事务才有
     * [manifest 段]   [u32 长度][Manifest protobuf]          ← 我们只要这一段
     * [footer 16B]    [段偏移 i64][major u16][minor u16][LANC]
     * </pre>
     *
     * <p><b>不能假设「manifest 在第 0 字节」</b>：只有不带索引、不带内联事务的 manifest
     * 才恰好从 0 开始。客户端的 {@code InsertIntoTable} 第一次写入就带内联事务，
     * 那时第 0 字节是事务段——按 0 去解会得到一份「字段全是垃圾」的 protobuf：
     * 不报错、字段名乱码、schema 悄悄错。所以按 footer 里的偏移取，且严格校验
     * 长度不越界，越界就报 {@link Unreadable} 让调用方降级。
     *
     * <p>footer 里的 major / minor 我们不用，也不校验：它们描述的是**文件格式代数**，
     * 而 manifest 的 protobuf 字段号只增不改——这与整份解析器「不认识就跳过」的
     * 策略一致。
     */
    private static byte[] payload(byte[] bytes) {
        if (!container(bytes)) {
            return bytes;
        }
        int footer = bytes.length - FOOTER_LENGTH;
        long position = 0;
        for (int i = 0; i < 8; i++) {
            position |= (long) (bytes[footer + i] & 0xFF) << (8 * i);
        }
        if (position < 0 || position + 4 > footer) {
            throw new Unreadable("manifest footer points at offset " + position
                    + ", outside the " + footer + " bytes it precedes");
        }
        int start = (int) position;
        long length = (bytes[start] & 0xFFL)
                | (bytes[start + 1] & 0xFFL) << 8
                | (bytes[start + 2] & 0xFFL) << 16
                | (bytes[start + 3] & 0xFFL) << 24;
        if (start + 4 + length > footer) {
            throw new Unreadable("manifest section at offset " + start + " declares " + length
                    + " bytes, which overruns the file (" + footer + " bytes before the footer)");
        }
        byte[] slice = new byte[(int) length];
        System.arraycopy(bytes, start + 4, slice, 0, (int) length);
        return slice;
    }

    private static boolean container(byte[] bytes) {
        if (bytes.length < FOOTER_LENGTH) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (bytes[bytes.length - MAGIC.length + i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static Manifest readManifest(Cursor cursor) {
        long version = 0;
        Long timestampMillis = null;
        List<Field> fields = new ArrayList<>();
        int fragmentCount = 0;
        long deletedRows = 0;
        while (cursor.next()) {
            switch (cursor.field()) {
                case 1 -> fields.add(readField(cursor.bytes()));
                case 2 -> {
                    int[] counted = readFragment(cursor.bytes());
                    fragmentCount++;
                    deletedRows += counted[0];
                }
                case 3 -> version = cursor.varint();
                case 7 -> timestampMillis = readTimestamp(cursor.bytes());
                default -> cursor.skip();
            }
        }
        return new Manifest(version, timestampMillis, List.copyOf(fields), fragmentCount, deletedRows);
    }

    private static Field readField(byte[] bytes) {
        Cursor cursor = new Cursor(bytes);
        int declaredType = 0;
        String name = "";
        int id = 0;
        int parentId = 0;
        String logicalType = "";
        boolean nullable = false;
        while (cursor.next()) {
            switch (cursor.field()) {
                case 1 -> declaredType = (int) cursor.varint();
                case 2 -> name = cursor.string();
                case 3 -> id = (int) cursor.varint();
                case 4 -> parentId = (int) cursor.varint();
                case 5 -> logicalType = cursor.string();
                case 6 -> nullable = cursor.varint() != 0;
                default -> cursor.skip();
            }
        }
        return new Field(role(declaredType, logicalType), name, id, parentId, logicalType, nullable);
    }

    /**
     * 字段角色：**以 {@code logical_type} 为准**，{@code type} 只作兜底。
     *
     * <p>这一条是被真客户端纠正的。Lance 4.x 的 {@code Field} 里**不再写 {@code type}**
     * （Rust 侧的结构体里这个字段已经没有了）：树形完全由 {@code logical_type} 决定——
     * {@code struct} 是父节点，{@code list} / {@code large_list} /
     * {@code fixed_size_list:...} / {@code list.struct} 是可重复节点，其余是叶子。
     * protobuf 对未写出的字段读出来是枚举的 0，而 0 恰好是 {@code PARENT}，
     * 于是照 {@code type} 解释会把**每一个叶子列都当成 struct**：
     * schema 不报错，只是变成一张空表（父节点没有成员）。
     *
     * <p>{@code logical_type} 也给不出结论时才回头看 {@code type}——老版本会显式写它，
     * 两者本来就一致，所以这不算是两套真相。
     */
    private static int role(int declaredType, String logicalType) {
        String logical = logicalType == null ? "" : logicalType.trim().toLowerCase(java.util.Locale.ROOT);
        if (logical.isEmpty()) {
            return declaredType;
        }
        if (logical.equals("struct") || logical.startsWith("map")) {
            return TYPE_PARENT;
        }
        if (logical.equals("list") || logical.equals("large_list")
                || logical.startsWith("list.") || logical.startsWith("large_list.")
                || logical.startsWith("fixed_size_list")) {
            return TYPE_REPEATED;
        }
        return TYPE_LEAF;
    }


    /**
     * 读一个 fragment，只取 {@code deletion_file.num_deleted_rows}。
     *
     * <p>返回数组而不是新建 record：这是逐 fragment 调用的热路径（一张大表
     * 几百个 fragment），而只有第一个槽被用到——{@code physical_rows} 我们不要，
     * 因为「当前行数」要减掉删除行，而我们没有 fragment 的写入时刻，
     * 算出来的数会与客户端看到的不一致。
     */
    private static int[] readFragment(byte[] bytes) {
        Cursor cursor = new Cursor(bytes);
        long deleted = 0;
        while (cursor.next()) {
            if (cursor.field() == 3) {
                deleted = readDeletedRows(cursor.bytes());
            } else {
                cursor.skip();
            }
        }
        return new int[]{(int) Math.max(0L, deleted)};
    }

    /** {@code DeletionFile.num_deleted_rows}（字段号 4）。 */
    private static long readDeletedRows(byte[] bytes) {
        Cursor cursor = new Cursor(bytes);
        long deleted = 0;
        while (cursor.next()) {
            if (cursor.field() == 4) {
                deleted = cursor.varint();
            } else {
                cursor.skip();
            }
        }
        return deleted;
    }

    /** {@code google.protobuf.Timestamp}：{@code seconds = 1}、{@code nanos = 2}。 */
    private static Long readTimestamp(byte[] bytes) {
        if (bytes.length == 0) {
            return null;
        }
        Cursor cursor = new Cursor(bytes);
        long seconds = 0;
        long nanos = 0;
        while (cursor.next()) {
            switch (cursor.field()) {
                case 1 -> seconds = cursor.varint();
                case 2 -> nanos = cursor.varint();
                default -> cursor.skip();
            }
        }
        if (seconds == 0 && nanos == 0) {
            return null;
        }
        return seconds * 1000L + nanos / 1_000_000L;
    }

    // ------------------------------------------------------------------ wire

    /**
     * protobuf wire 格式的游标。
     *
     * <p>刻意不实现 fixed32 / fixed64 之外的浮点解码：manifest 里我们关心的字段
     * 没有浮点。遇到它们 {@link #skip()} 按长度跳过即可——这也是 protobuf
     * 「未知字段可忽略」这条性质唯一要实现的地方。
     */
    private static final class Cursor {

        private static final int WIRE_VARINT = 0;
        private static final int WIRE_64 = 1;
        private static final int WIRE_LEN = 2;
        private static final int WIRE_32 = 5;

        private final byte[] data;
        private int position;
        private int field;
        private int wire;

        private Cursor(byte[] data) {
            this.data = data;
        }

        /** 读下一个 tag；到末尾返回 {@code false}。 */
        boolean next() {
            if (position >= data.length) {
                return false;
            }
            long tag = varint();
            field = (int) (tag >>> 3);
            wire = (int) (tag & 7);
            if (field == 0) {
                throw new Unreadable("field number 0 is not valid in a protobuf message");
            }
            return true;
        }

        int field() {
            return field;
        }

        /** 当前字段按 wire 类型跳过——不认识的消息字段靠它略过。 */
        void skip() {
            switch (wire) {
                case WIRE_VARINT -> varint();
                case WIRE_64 -> advance(8);
                case WIRE_32 -> advance(4);
                case WIRE_LEN -> advance((int) varint());
                default -> throw new Unreadable("unsupported protobuf wire type " + wire);
            }
        }

        /** 长度前缀的字节块（嵌套消息 / string / bytes）。 */
        byte[] bytes() {
            if (wire != WIRE_LEN) {
                throw new Unreadable("expected a length-delimited field, got wire type " + wire);
            }
            int length = (int) varint();
            if (length < 0 || position + length > data.length) {
                throw new Unreadable("truncated protobuf field: " + length + " bytes declared,"
                        + " " + (data.length - position) + " available");
            }
            byte[] slice = new byte[length];
            System.arraycopy(data, position, slice, 0, length);
            position += length;
            return slice;
        }

        String string() {
            return new String(bytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        long varint() {
            long value = 0;
            int shift = 0;
            for (int i = 0; i < 10; i++) {
                if (position >= data.length) {
                    throw new Unreadable("truncated varint");
                }
                int b = data[position++] & 0xFF;
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
                shift += 7;
            }
            throw new Unreadable("varint longer than 10 bytes");
        }

        private void advance(int count) {
            if (count < 0 || position + count > data.length) {
                throw new Unreadable("truncated protobuf message");
            }
            position += count;
        }
    }
}
