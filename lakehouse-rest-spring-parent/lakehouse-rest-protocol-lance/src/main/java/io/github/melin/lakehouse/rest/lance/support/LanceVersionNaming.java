package io.github.melin.lakehouse.rest.lance.support;

/**
 * {@code _versions/} 下 manifest 文件名的两种命名方案。
 *
 * <p>规范（Lance 表格式「Manifest Naming Scheme」）给了两套，且明确说
 * **混用会出错**，因此实现必须能分辨而不是一律当小数读：
 *
 * <ul>
 *   <li><b>V1</b>：{@code {version}.manifest}——版本号直接写，字典序不等于数值序；</li>
 *   <li><b>V2</b>：{@code {u64::MAX - version}.manifest}，补零到 20 位——
 *       降序排列，因此在对象存储上「找最新版本」只要一次 LIST 的头一个条目。</li>
 * </ul>
 *
 * <p><b>为什么 V2 要用无符号语义算。</b>{@code u64::MAX} 是
 * {@code 18446744073709551615}，超过 Java {@code long} 的最大值。
 * 但 {@code version = u64::MAX - name} 在二进制补码下就是
 * {@code -1 - name}（{@code u64::MAX} 的 signed 表示恰好是 {@code -1}），
 * 因此不需要 {@code BigInteger}，也不需要对 2^64 取模——溢出本身就是答案。
 * 这一段要留注释：后来人看到 {@code -1 - x} 一定会想改成 {@code MAX - x}。
 */
public final class LanceVersionNaming {

    /** V2 的文件名长度：{@code u64::MAX} 是 20 位十进制数。 */
    private static final int V2_DIGITS = 20;

    private LanceVersionNaming() {
    }

    /** manifest 文件的后缀。 */
    public static final String SUFFIX = ".manifest";

    /**
     * 文件名 → 版本号。
     *
     * @return 版本号；不是 manifest 文件（或不是数字）时返回 {@code null}
     */
    public static Long versionOf(String fileName) {
        if (fileName == null || !fileName.endsWith(SUFFIX)) {
            return null;
        }
        String digits = fileName.substring(0, fileName.length() - SUFFIX.length());
        if (digits.isEmpty()) {
            return null;
        }
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
                return null;
            }
        }
        try {
            if (isV2(digits)) {
                return -1L - Long.parseUnsignedLong(digits);
            }
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            // 超过 20 位的数字串：不是我们认识的任何一种命名
            return null;
        }
    }

    /** 是否 V2 命名（补零到 20 位）。 */
    public static boolean isV2(String fileName) {
        if (fileName == null) {
            return false;
        }
        String digits = fileName.endsWith(SUFFIX)
                ? fileName.substring(0, fileName.length() - SUFFIX.length()) : fileName;
        return digits.length() == V2_DIGITS;
    }

    /**
     * 版本号 → 文件名；{@code DescribeTableVersion} 要按它拼出 manifest 路径去探测存在性。
     *
     * <p><b>V2 必须按无符号打印。</b>{@code -1 - version} 的 signed 值是负数，
     * {@code String.format("%020d", ...)} 会输出 {@code -000...002} 这种带符号的串，
     * 而文件名里没有符号位——用 {@link Long#toUnsignedString} 再自己补零。
     */
    public static String manifestName(long version, boolean v2) {
        if (!v2) {
            return version + SUFFIX;
        }
        String digits = Long.toUnsignedString(-1L - version);
        if (digits.length() < V2_DIGITS) {
            digits = "0".repeat(V2_DIGITS - digits.length()) + digits;
        }
        return digits + SUFFIX;
    }
}
