package io.github.melin.lakehouse.rest.event;

import io.github.melin.lakehouse.rest.service.DataPolicyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 表生命周期与数据策略的联动（{@code docs/row-filter-column-mask-design.md} §5.2 取舍 4）。
 *
 * <p><b>策略按三段名绑定，所以表的生命周期必须钩住</b>。三条规则的来源与后果：
 * <ul>
 *   <li><b>DROP / 注销 → 级联删策略</b>：不删的话，同名重建的表会继承旧策略——
 *       可能是意外保护（旧谓词还引用得到列时把新表读小了），更常见的是意外
 *       fail-closed（旧策略引用的列已经不存在，整张表读死）。级联删让
 *       「CREATE 不继承」成为不需要代码的推论。</li>
 *   <li><b>RENAME → 策略跟随</b>：改名不改语义，保护应该跟着表走。</li>
 *   <li><b>CREATE → 不做任何事</b>：继承与否完全由上面两条决定。</li>
 * </ul>
 *
 * <p><b>为什么挂在事件上而不是改协议面</b>：DROP / RENAME 的实现在 Paimon 与 Iceberg
 * 两个协议模块里，策略实体在 core——让协议模块反向感知策略，等于把「表生命周期」
 * 这个通用概念焊死在策略上。{@code CatalogEventListener} 是唯一一个两边都够得着的
 * 位置（{@code LineageEdgeCleanupListener} 的先例）。
 *
 * <p>失败只记 ERROR（发布器兜底），删表本身不受影响：策略残留的最坏结果由引擎侧
 * fail-closed 挡住（旧策略引用不存在的列 → 拒读并给出明确错误），不会静默放行。
 */
@Slf4j
@Component
public class DataPolicyLifecycleListener implements CatalogEventListener {

    /** 监听器名：配置里的键是 {@code lakehouse.rest.event-listener.policy.enabled}。 */
    public static final String NAME = "policy";

    private final DataPolicyService policyService;

    /**
     * {@code @Lazy} 破环：{@code DataPolicyService} 要发布事件（{@code POLICY_CHANGED}），
     * 发布器收集全部监听器，监听器又要回服务——构造期三个 bean 互相等待。
     * 监听器是被动消费方，第一次事件到来时才解析服务是安全的。
     */
    public DataPolicyLifecycleListener(@Lazy DataPolicyService policyService) {
        this.policyService = policyService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void onEvent(CatalogEvent event) {
        if (!CatalogEvent.TARGET_TABLE.equals(event.targetType())) {
            return;
        }
        try {
            switch (event.type()) {
                case TABLE_DROPPED, TABLE_UNREGISTERED -> dropPolicy(event);
                case TABLE_RENAMED -> movePolicy(event);
                default -> {
                    // 其余表事件与策略无关
                }
            }
        } catch (RuntimeException exception) {
            log.error("failed to apply {} to the data policy of {}.{}: {}",
                    event.type(), event.catalog(), event.target(),
                    exception.getMessage(), exception);
        }
    }

    private void dropPolicy(CatalogEvent event) {
        NameParts parts = parts(event);
        if (parts != null) {
            policyService.onTableDropped(parts.catalog(), parts.database(), parts.table());
        }
    }

    /**
     * RENAME 事件：{@code target} 是<b>新</b>全名，{@code from} 属性是旧名
     * （两个协议面的发布点都带这个属性）。{@code from} 与 {@code target} 同形——
     * 是 {@code db.table} 不带 catalog，所以要补上事件里的 catalog 才能凑齐
     * 策略的三段键。缺 {@code from} 的事件找不到旧策略，只能跳过并记 WARN——
     * 静默跳过会让「改名后策略没跟上」排障无门。
     */
    private void movePolicy(CatalogEvent event) {
        NameParts from = partsWithCatalog(event, event.attributes().get("from"));
        NameParts to = parts(event);
        if (from == null || to == null) {
            log.warn("table.renamed event without a parsable from/target, data policy not moved:"
                    + " catalog={} target={}", event.catalog(), event.target());
            return;
        }
        policyService.onTableRenamed(to.catalog(), from.database(), from.table(),
                to.database(), to.table());
    }

    /** 事件的 target 是 {@code db.table}（展示用定位符），加 catalog 才是策略的键。 */
    private NameParts parts(CatalogEvent event) {
        return partsWithCatalog(event, event.target());
    }

    /**
     * 把一段定位符凑成三段名。发布点的形状是 {@code db.table}（不带 catalog，库段
     * 自身可能多级），catalog 永远从事件的 catalog 字段补——不能从定位符里猜首段：
     * 多级 namespace 的表，定位符首段是 namespace 而不是 catalog。
     */
    private NameParts partsWithCatalog(CatalogEvent event, Object ref) {
        String raw = ref == null ? null : String.valueOf(ref);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String catalog = event.catalog() == null || event.catalog().isBlank()
                ? null : event.catalog();
        return catalog == null ? null : parseRef(catalog + "." + raw);
    }

    private NameParts parseRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String[] segments = ref.split("\\.");
        if (segments.length < 3) {
            return null;
        }
        // 首段 catalog、末段表、中间全是 namespace（多级 namespace 按点号拼库，与存储名一致）
        return new NameParts(segments[0],
                String.join(".", java.util.Arrays.copyOfRange(segments, 1, segments.length - 1)),
                segments[segments.length - 1]);
    }

    private record NameParts(String catalog, String database, String table) {
    }
}
