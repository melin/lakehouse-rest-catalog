package io.github.melin.lakehouse.rest.lance;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 测试用的 **Lance manifest 编码器**（手写 protobuf wire 字节）。
 *
 * <p>为什么测试里要自己编一遍而不是塞一段固定字节：解析器的价值在于
 * 「Lance 版本演进时还认得新字段」，而固定字节只能证明我们认得自己写过的那一版。
 * 用编码器可以随时加一个**我们不认识的字段**（{@link #unknownField}），
 * 从而把「未知字段必须跳过」这条 protobuf 的基本性质变成一条可回归的断言。
 */
public final class LanceManifestFixture {

    private LanceManifestFixture() {
    }

    /** 一个字段；字段号见 {@code format.proto} 的 {@code Field}。 */
    public record FieldSpec(int type, String name, int id, int parentId, String logicalType, boolean nullable) {
    }

    public static FieldSpec leaf(String name, int id, int parentId, String logicalType) {
        return new FieldSpec(2, name, id, parentId, logicalType, true);
    }

    public static FieldSpec parent(String name, int id, int parentId) {
        return new FieldSpec(0, name, id, parentId, "struct", true);
    }

    public static FieldSpec repeated(String name, int id, int parentId) {
        return new FieldSpec(1, name, id, parentId, "list", true);
    }

    /**
     * 编一份 manifest。
     *
     * @param timestampSeconds 为 {@code null} 时不写 {@code timestamp} 字段
     * @param fragments       fragment 个数；每个带 {@code deletedPerFragment} 行删除标记
     */
    public static byte[] manifest(long version, Long timestampSeconds, List<FieldSpec> fields,
                                  int fragments, long deletedPerFragment) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (FieldSpec field : fields) {
            write(out, message(1, field(field)));
        }
        for (int i = 0; i < fragments; i++) {
            write(out, message(2, fragment(i, deletedPerFragment)));
        }
        write(out, varintField(3, version));
        if (timestampSeconds != null) {
            write(out, message(7, timestamp(timestampSeconds)));
        }
        return out.toByteArray();
    }

    /** 一个我们不认识的字段（字段号 99，varint）——解析器必须跳过而不是报错。 */
    public static byte[] unknownField() {
        return varintField(99, 12345);
    }

    /**
     * **现代（4.x）形状**的 manifest：{@code Field} 里没有 {@code type}，顶层用 {@code parent_id = -1}。
     *
     * <p>这是真客户端写出来的形状（见 {@code protos/file.proto}：Rust 侧的 {@code Field}
     * 结构体里压根没有 type）。老解析器照 {@code type} 的缺省值 0（{@code PARENT}）理解，
     * 会把每个叶子列都当成 struct：schema 不报错，只是变成一张空表。
     * 这个夹具就是给那条判据留的回归位。
     */
    public static byte[] modernManifest(long version, Long timestampSeconds, List<FieldSpec> fields,
                                        int fragments, long deletedPerFragment) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (FieldSpec spec : fields) {
            write(out, message(1, modernField(spec)));
        }
        for (int i = 0; i < fragments; i++) {
            write(out, message(2, fragment(i, deletedPerFragment)));
        }
        write(out, varintField(3, version));
        if (timestampSeconds != null) {
            write(out, message(7, timestamp(timestampSeconds)));
        }
        return out.toByteArray();
    }

    /**
     * 现代形状的一个 {@code Field}：不写 {@code type}、{@code id = 0} 时省略该键
     * （protobuf 的默认值不落字节，真实文件也是这么写的）。
     */
    private static byte[] modernField(FieldSpec spec) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, stringField(2, spec.name()));
        if (spec.id() != 0) {
            write(out, varintField(3, spec.id()));
        }
        write(out, varintField(4, spec.parentId() <= 0 ? -1 : spec.parentId()));
        write(out, stringField(5, spec.logicalType()));
        write(out, varintField(6, spec.nullable() ? 1 : 0));
        return out.toByteArray();
    }

    /**
     * 把裸 manifest 包成 Lance 4.x 的**容器**形状。
     *
     * <pre>
     * [前置段：u32 长度 + prefixBytes 个字节][u32 长度][manifest][footer 16B: 偏移 i64 + major u16 + minor u16 + "LANC"]
     * </pre>
     *
     * <p>{@code prefixBytes} 模拟「manifest 前面还有别的段」——索引段、内联事务段。
     * 这是真客户端的常态（{@code InsertIntoTable} 第一次写入就带内联事务），
     * 也是「按 footer 偏移取」与「按第 0 字节取」唯一能分出对错的场景：
     * 后者会解出一份字段名乱码、却不抛异常的 protobuf。
     */
    public static byte[] container(byte[] manifest, int prefixBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writePrefix(out, prefixBytes);
        return finish(out, manifest, out.size());
    }

    /** 同上，但 footer 里的段偏移由调用方指定——用来造「偏移越界」的坏文件。 */
    public static byte[] containerWithFooterPosition(byte[] manifest, int prefixBytes, long position) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writePrefix(out, prefixBytes);
        return finish(out, manifest, position);
    }

    private static void writePrefix(ByteArrayOutputStream out, int prefixBytes) {
        if (prefixBytes <= 0) {
            return;
        }
        writeLittleEndian(out, prefixBytes, 4);
        write(out, new byte[prefixBytes]);
    }

    private static byte[] finish(ByteArrayOutputStream out, byte[] manifest, long position) {
        writeLittleEndian(out, manifest.length, 4);
        write(out, manifest);
        writeLittleEndian(out, position, 8);
        writeLittleEndian(out, 0, 2);
        writeLittleEndian(out, 2, 2);
        write(out, "LANC".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static void writeLittleEndian(ByteArrayOutputStream out, long value, int bytes) {
        for (int i = 0; i < bytes; i++) {
            out.write((int) ((value >>> (8 * i)) & 0xFF));
        }
    }

    /** 截断的字节：长度前缀声明的比实际多。 */
    public static byte[] truncated() {
        byte[] payload = new byte[]{1, 2, 3};
        byte[] tagged = message(1, payload);
        byte[] cut = new byte[tagged.length - 1];
        System.arraycopy(tagged, 0, cut, 0, cut.length);
        return cut;
    }

    // ------------------------------------------------------------------ wire

    private static byte[] field(FieldSpec spec) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, varintField(1, spec.type()));
        write(out, stringField(2, spec.name()));
        write(out, varintField(3, spec.id()));
        write(out, varintField(4, spec.parentId()));
        write(out, stringField(5, spec.logicalType()));
        write(out, varintField(6, spec.nullable() ? 1 : 0));
        return out.toByteArray();
    }

    private static byte[] fragment(int id, long deletedRows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, varintField(1, id));
        write(out, message(3, deletionFile(deletedRows)));
        write(out, varintField(4, 100));
        return out.toByteArray();
    }

    private static byte[] deletionFile(long deletedRows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, varintField(1, 0));
        write(out, varintField(4, deletedRows));
        return out.toByteArray();
    }

    private static byte[] timestamp(long seconds) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, varintField(1, seconds));
        write(out, varintField(2, 0));
        return out.toByteArray();
    }

    private static byte[] message(int field, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, varint((field << 3) | 2));
        write(out, varint(payload.length));
        write(out, payload);
        return out.toByteArray();
    }

    private static byte[] varintField(int field, long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, varint((field << 3) | 0));
        write(out, varint(value));
        return out.toByteArray();
    }

    private static byte[] stringField(int field, String value) {
        return message(field, value.getBytes(StandardCharsets.UTF_8));
    }

    /** protobuf 的 varint：低 7 位一组，高位续行。 */
    private static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long remaining = value;
        while (true) {
            int bits = (int) (remaining & 0x7F);
            remaining >>>= 7;
            if (remaining == 0) {
                out.write(bits);
                return out.toByteArray();
            }
            out.write(bits | 0x80);
        }
    }

    private static void write(ByteArrayOutputStream out, byte[] bytes) {
        out.writeBytes(bytes);
    }
}
