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
