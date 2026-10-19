package io.github.melin.lakehouse.rest.event;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 监听器订阅配置：哪些监听器生效、每个监听器订阅哪些事件。
 *
 * <p>配置形状**照搬 Polaris**（1.5 起的多监听器 + 按类型/类别过滤），只把根前缀换成本项目：
 *
 * <pre>
 * paimon.rest.event-listener.types=db,log
 * paimon.rest.event-listener.log.enabled-event-categories=TABLE,NAMESPACE
 * paimon.rest.event-listener.db.enabled-event-types=catalog.dropped
 * </pre>
 *
 * <ul>
 *   <li>{@code types}：启用的监听器名（{@link CatalogEventListener#name()}）。<b>不配 = 全部启用</b>，
 *       与 Polaris 一致；写了不在注册表里的名字则在启动期报错，而不是悄悄什么都不发。</li>
 *   <li>{@code <name>.enabled-event-categories}：按类别订阅，取值见 {@link CatalogEventCategory}。</li>
 *   <li>{@code <name>.enabled-event-types}：按具体类型订阅，取值是 {@link CatalogEventType#wire()}。
 *       两个都配时取<b>并集</b>（Polaris 的语义，见其 1.8 配置参考）；都不配 = 全订阅。</li>
 * </ul>
 *
 * <p><b>为什么不用 {@code @ConfigurationProperties} 绑。</b>Spring 的 Map 绑定要求动态键统一
 * 落在一个固定节点下（{@code ...listeners.<name>.enabled-event-categories}），而 Polaris 的形状是
 * 监听器名直接挂在 {@code event-listener} 前缀下、与 {@code types} 平级——同一个前缀里既有标量
 * 又有动态子键，绑定器表达不了。这里用 {@link Environment} 显式读，键名与 Polaris 完全对齐。
 *
 * <p><b>启动期校验。</b>监听器名、类别名、事件类型名任一写错都直接让应用起不来：配置错误
 * 的后果是「该记的没记」，运行期才发现通常已经丢了审计。校验放在构造器里——它依赖全部
 * 监听器 bean，构造完成即意味着注册表已完整。
 */
@Component
public class EventListenerSettings {

    public static final String PREFIX = "paimon.rest.event-listener";

    private static final Logger log = LoggerFactory.getLogger(EventListenerSettings.class);

    private final Set<String> types;
    private final Map<String, Subscription> subscriptions;

    public EventListenerSettings(Environment environment, List<CatalogEventListener> listeners) {
        Set<String> registered = new LinkedHashSet<>();
        for (CatalogEventListener listener : listeners) {
            registered.add(listener.name());
        }

        this.types = new LinkedHashSet<>(tokens(environment.getProperty(PREFIX + ".types")));
        List<String> unknown = types.stream().filter(name -> !registered.contains(name)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(PREFIX + ".types 里有未注册的监听器 " + unknown
                    + "，已注册的是 " + registered);
        }

        Map<String, Subscription> parsed = new LinkedHashMap<>();
        for (String name : registered) {
            parsed.put(name, new Subscription(
                    categories(environment.getProperty(PREFIX + "." + name + ".enabled-event-categories"), name),
                    wireTypes(environment.getProperty(PREFIX + "." + name + ".enabled-event-types"), name)));
        }
        this.subscriptions = Map.copyOf(parsed);

        subscriptions.forEach((name, subscription) -> log.info(
                "event listener {}: {} (categories={}, types={})",
                name, enabled(name) ? "enabled" : "disabled",
                subscription.categories(), subscription.types()));
    }

    /** 该监听器是否在 {@code types} 名单里；没配 {@code types} 时全部启用。 */
    public boolean enabled(String listener) {
        return types.isEmpty() || types.contains(listener);
    }

    /** 该监听器是否订阅这个事件类型。 */
    public boolean accepts(String listener, CatalogEventType type) {
        Subscription subscription = subscriptions.get(listener);
        return subscription == null || subscription.accepts(type);
    }

    private Set<CatalogEventCategory> categories(String raw, String listener) {
        Set<CatalogEventCategory> result = new LinkedHashSet<>();
        for (String token : tokens(raw)) {
            try {
                result.add(CatalogEventCategory.valueOf(token.toUpperCase()));
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(PREFIX + "." + listener
                        + ".enabled-event-categories 里有未知类别 " + token + "，可选值 "
                        + Arrays.toString(CatalogEventCategory.values()), exception);
            }
        }
        return result;
    }

    private Set<String> wireTypes(String raw, String listener) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : tokens(raw)) {
            CatalogEventType type = CatalogEventType.byWire(token).orElseThrow(() ->
                    new IllegalStateException(PREFIX + "." + listener
                            + ".enabled-event-types 里有未知事件类型 " + token
                            + "，取值见 CatalogEventType（小写点分，如 table.created）"));
            result.add(type.wire());
        }
        return result;
    }

    private static List<String> tokens(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : raw.split(",")) {
            if (!token.isBlank()) {
                tokens.add(token.trim());
            }
        }
        return tokens;
    }

    /** 一个监听器的订阅集合：两个都空 = 全订阅；否则取并集。 */
    private record Subscription(Set<CatalogEventCategory> categories, Set<String> types) {

        boolean accepts(CatalogEventType type) {
            if (categories.isEmpty() && types.isEmpty()) {
                return true;
            }
            return categories.contains(type.category()) || types.contains(type.wire());
        }
    }
}
