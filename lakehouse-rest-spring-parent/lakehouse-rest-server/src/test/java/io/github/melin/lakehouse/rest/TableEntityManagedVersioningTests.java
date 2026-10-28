package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * {@code lakehouse_table.managed_versioning} 的读写在 {@code NULL} 上的行为。
 *
 * <p>这条规则来自一个实际故障：老库升级时这一列是手工
 * {@code alter table ... add column} 加上去的，没有 {@code not null default}，
 * 存量行全是 {@code NULL}；实体当时映射成基本类型 {@code boolean}，
 * 于是 Hibernate 读任何一张表都会在
 * {@code SetterFieldImpl#set} 抛
 * {@code IllegalArgumentException: Can not set boolean field ... managedVersioning
 * to null value}——整张表的列表、详情、给引擎的元数据读取全部 500，
 * 而 DDL 里明明白白写着 {@code not null}，报错点与原因隔了很远。
 *
 * <p>因此这里盯住三件事：<b>字段是包装类型</b>（不能再退回基本类型）、
 * <b>{@code NULL} 读成 {@code false}</b>（与「默认不托管」一致）、
 * 以及<b>写回去时不带 {@code NULL}</b>（否则下一次更新会撞
 * {@code not null} 约束，变成另一个看起来无关的错误）。
 */
class TableEntityManagedVersioningTests {

    @Test
    void fieldMustBeABoxedBooleanSoNullDoesNotBlowUpHibernate() throws Exception {
        Field field = TableEntity.class.getDeclaredField("managedVersioning");
        assertEquals(Boolean.class, field.getType(),
                "基本类型 boolean 读 NULL 会抛 IllegalArgumentException；必须是 Boolean");
    }

    @Test
    void nullReadsAsFalse() {
        TableEntity table = new TableEntity();
        table.setManagedVersioning(null);
        assertFalse(table.isManagedVersioning(), "NULL 表示「没开托管」，不能当成 true");
    }

    @Test
    void trueSurvivesARoundTrip() {
        TableEntity table = new TableEntity();
        table.setManagedVersioning(true);
        assertTrue(table.isManagedVersioning());
    }

    @Test
    void newTableDefaultsToFalse() {
        assertFalse(new TableEntity().isManagedVersioning());
    }

    @Test
    void nullIsNormalizedBeforeWrite() throws Exception {
        TableEntity table = new TableEntity();
        table.setManagedVersioning(null);

        table.normalizeManagedVersioning();

        Field field = TableEntity.class.getDeclaredField("managedVersioning");
        field.setAccessible(true);
        assertEquals(Boolean.FALSE, field.get(table), "写库前必须把 NULL 换成 FALSE");
    }
}
