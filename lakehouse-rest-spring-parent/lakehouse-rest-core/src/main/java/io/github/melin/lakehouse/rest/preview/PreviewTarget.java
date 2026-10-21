package io.github.melin.lakehouse.rest.preview;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;

/**
 * 打开一张表所需的最小上下文：表实体、它所属的 catalog、以及库名。
 *
 * <p>三个字段都不是"顺手带上"的：表实体给物理路径（预览只按路径打开，不假设
 * 表在 warehouse 下的目录结构——外部表、多级 namespace 都能落在别处）；
 * catalog 给凭据（{@code StorageFileIoFactory} 要它算 FileIO 选项）与前缀（审计用）；
 * 库名给判权（{@code ResourceType.TABLE} 的 namespace 那一段）与响应自描述。
 *
 * @param table    表实体
 * @param catalog  表所属 catalog
 * @param database 库名；元数据里挂着的库实体找不到时是库 id（与文件列表端点的处理一致）
 */
public record PreviewTarget(TableEntity table, CatalogEntity catalog, String database) {
}
