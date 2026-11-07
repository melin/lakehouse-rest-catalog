package io.github.melin.lakehouse.rest.optimizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 两个入口（优化任务、扫描任务）共用的命令行解析。
 *
 * <p><b>为什么不各写一份。</b>参数形状完全同构：一个 id（{@code --job-id} / {@code --scan-id}）、
 * {@code --management-url}、{@code --token}、可选的 {@code --catalog-token} 与 {@code --catalog-url}。
 * 复制一份的代价不是「多八十行」，而是两条链路对同一种写法给出不同结果——例如
 * {@code --token=abc} 这种等号形式，一份支持、另一份当成「缺值」。而这两个参数都是
 * 服务端生成的，真机上没人会手敲一遍来提前发现差异。
 *
 * <p><b>不用第三方 CLI 库</b>：调用面就是「几个 {@code --name value}」，引一个库换来的
 * 只有用法文档，而这里每处错误都能说清「谁负责传这个参数」——看到这条报错的人通常正在
 * 读 k8s Job 定义。
 */
final class JobArgs {

    private JobArgs() {
    }

    /**
     * 把 {@code --name value} / {@code --name=value} 收成一张表。
     *
     * @param bareName 位置参数（不带 {@code --} 的第一个）回落到哪个键；手工调试时最常
     *                 忘的就是 {@code --}，放行但不宣导。传 {@code null} 表示不接受位置参数
     */
    static Map<String, String> parse(String[] args, String bareName) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> bare = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                bare.add(arg);
                continue;
            }
            String name = arg.substring(2);
            int equals = name.indexOf('=');
            if (equals > 0) {
                // `--name=` 是「显式给空值」，与「没给」不同：调用方可以据此报出
                // 「传了个空的管理 API 地址」，而不是「参数缺失」
                values.put(name.substring(0, equals), name.substring(equals + 1));
                continue;
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("missing value for " + arg);
            }
            values.put(name, args[++i]);
        }
        if (bareName != null && !values.containsKey(bareName) && !bare.isEmpty()) {
            values.put(bareName, bare.get(0));
        }
        return values;
    }

    /**
     * 取必需参数；缺失时抛出**指名参数与责任人**的异常。
     *
     * @param seeAlso 真正生成这个参数的那个类名。看到这条错误的人通常正在改 k8s Job 定义，
     *                告诉他去哪看，比给他一份用法更有用
     */
    static String require(Map<String, String> values, String name, String seeAlso) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required argument --" + name
                    + "; the server passes it when it creates the job, so when running by hand"
                    + " copy the arguments from the generated job definition (see " + seeAlso + ")");
        }
        return value.trim();
    }

    /** 去掉尾部斜杠：{@code baseUrl + "/path"} 拼出来不能出现 {@code //}。 */
    static String trimSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** 空串与 {@code null} 同义：可选参数传空串的意图是「不用它」，不是「用空值」。 */
    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
