package io.github.melin.lakehouse.rest.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 监听器订阅配置（{@code paimon.rest.event-listener.*}）的语义测试。
 *
 * <p>这些行为一旦错了，表现是「事件少了」而不是「报错」——所以每条都要钉住，
 * 尤其是三条容易被误读的：
 *
 * <ul>
 *   <li><b>什么都不配 = 全订阅</b>（与 Polaris 一致），不是「什么都不订阅」；</li>
 *   <li><b>类别与类型同时配 = 并集</b>；</li>
 *   <li><b>配置写错要在启动期炸</b>——写成 {@code enabled-event-categories=TBALE}
 *       时静默忽略，等于悄悄关掉了那个监听器的一整类事件。</li>
 * </ul>
 */
class EventListenerSettingsTests {

    private static final String PREFIX = EventListenerSettings.PREFIX;

    private final CatalogEventListener db = listener("db");
    private final CatalogEventListener log = listener("log");
    private final List<CatalogEventListener> listeners = List.of(db, log);

    @Test
    void withoutConfigurationEveryListenerGetsEveryEvent() {
        EventListenerSettings settings = settings(new MockEnvironment());

        assertTrue(settings.enabled("db"));
        assertTrue(settings.enabled("log"));
        assertTrue(settings.accepts("db", CatalogEventType.TABLE_CREATED));
        assertTrue(settings.accepts("log", CatalogEventType.NAMESPACE_DROPPED));
    }

    @Test
    void typesAllowsOnlyTheNamedListeners() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(PREFIX + ".types", "log");

        EventListenerSettings settings = settings(environment);

        assertFalse(settings.enabled("db"), "没进 types 的监听器不投递");
        assertTrue(settings.enabled("log"));
    }

    @Test
    void categoriesFilterOneListenerAndLeaveTheOthersAlone() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(PREFIX + ".log.enabled-event-categories", "table, GRANT");

        EventListenerSettings settings = settings(environment);

        assertTrue(settings.accepts("log", CatalogEventType.TABLE_CREATED));
        assertTrue(settings.accepts("log", CatalogEventType.GRANT_REVOKED), "取值大小写不敏感");
        assertFalse(settings.accepts("log", CatalogEventType.NAMESPACE_CREATED));
        // 另一个监听器没配，仍然是全订阅
        assertTrue(settings.accepts("db", CatalogEventType.NAMESPACE_CREATED));
    }

    @Test
    void categoriesAndTypesAreUnioned() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(PREFIX + ".log.enabled-event-categories", "TABLE");
        environment.setProperty(PREFIX + ".log.enabled-event-types", "catalog.dropped");

        EventListenerSettings settings = settings(environment);

        assertTrue(settings.accepts("log", CatalogEventType.TABLE_CREATED), "类别命中");
        assertTrue(settings.accepts("log", CatalogEventType.CATALOG_DROPPED), "类型命中");
        assertFalse(settings.accepts("log", CatalogEventType.NAMESPACE_CREATED),
                "两个都没命中才不投递");
    }

    @Test
    void unknownCategoryFailsAtStartup() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(PREFIX + ".log.enabled-event-categories", "TBALE");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> settings(environment));
        assertTrue(failure.getMessage().contains("TBALE"), failure.getMessage());
    }

    @Test
    void unknownListenerFailsAtStartup() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(PREFIX + ".types", "db,webhook");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> settings(environment));
        assertTrue(failure.getMessage().contains("webhook"), failure.getMessage());
    }

    @Test
    void unknownEventTypeFailsAtStartup() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(PREFIX + ".db.enabled-event-types", "table.create");

        assertThrows(IllegalStateException.class, () -> settings(environment));
    }

    private EventListenerSettings settings(MockEnvironment environment) {
        return new EventListenerSettings(environment, listeners);
    }

    private static CatalogEventListener listener(String name) {
        return new CatalogEventListener() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void onEvent(CatalogEvent event) {
                // 本测试只关心「该不该收到」，不关心收到之后做什么
            }
        };
    }
}
