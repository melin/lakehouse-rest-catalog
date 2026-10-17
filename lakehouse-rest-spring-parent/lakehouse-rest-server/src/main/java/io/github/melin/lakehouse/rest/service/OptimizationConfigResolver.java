package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableOptimizationEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableOptimizationRepository;
import io.github.melin.lakehouse.rest.support.CatalogFormat;
import io.github.melin.lakehouse.rest.support.OptimizationOptions;
import io.github.melin.lakehouse.rest.support.OptimizationOptions.Action;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把策略配置算成「这一项动作实际会用的参数」。
 *
 * <p>单独成组件是为了打断依赖环：策略服务要用它算生效值给控制台看，批次服务要用它算
 * 参数入批，批次明细服务（提交清单、重试）也要用——如果它长在策略服务里，
 * 策略服务又依赖批次服务（手动触发要建批次），就成了环。
 *
 * <p><b>合并与校验的关系。</b>表级差异是经 API 写入的，写的时候已经校验过一遍；
 * catalog 默认值是运维直接改属性的，没人拦得住，所以合并之后要再校验一次：
 * 校验不过就**整项回落内置默认**并打 WARN，而不是让一批表带着非法参数跑到 k8s 上去失败。
 * 回落是整项而不是逐键——参数之间有依赖（{@code strategy=sort} 要求有排序列），
 * 逐键丢弃会拼出一个本身自相矛盾的组合。
 */
@Component
@RequiredArgsConstructor
public class OptimizationConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(OptimizationConfigResolver.class);

    private final TableOptimizationRepository policyRepository;
    private final CatalogRepository catalogRepository;

    /** 表策略里的三层配置差异，按动作归拢。 */
    public static Map<String, Map<String, String>> tableConfigs(TableOptimizationEntity policy) {
        Map<String, Map<String, String>> configs = new LinkedHashMap<>();
        if (policy == null) {
            return configs;
        }
        configs.put(Action.COMPACT.wire(), policy.getCompactionConfig());
        configs.put(Action.EXPIRE.wire(), policy.getSnapshotExpireConfig());
        configs.put(Action.ORPHAN.wire(), policy.getOrphanRemoveConfig());
        return configs;
    }

    /** catalog 属性（默认值的来源）。catalog 不存在时返回空表，回落内置默认。 */
    @Transactional(readOnly = true)
    public Map<String, String> catalogProperties(String catalogId) {
        return catalogRepository.findById(catalogId)
                .map(CatalogEntity::getProperties)
                .orElseGet(LinkedHashMap::new);
    }

    /** 三项动作的生效参数，键为动作的 wire 名（Paimon 格式；保留给既有调用方）。 */
    public Map<String, Map<String, String>> resolve(Map<String, String> catalogProperties,
                                                    Map<String, Map<String, String>> tableConfigs) {
        return resolve(CatalogFormat.PAIMON, catalogProperties, tableConfigs);
    }

    /**
     * 三项动作的生效参数（按格式）。
     *
     * <p>格式只影响「用哪套键与默认值」——合并与回落的结构两边完全一样。
     */
    public Map<String, Map<String, String>> resolve(CatalogFormat format,
                                                    Map<String, String> catalogProperties,
                                                    Map<String, Map<String, String>> tableConfigs) {
        Map<String, Map<String, String>> resolved = new LinkedHashMap<>();
        for (Action action : Action.values()) {
            Map<String, String> tableConfig = tableConfigs == null ? null : tableConfigs.get(action.wire());
            resolved.put(action.wire(), resolve(format, action, catalogProperties, tableConfig));
        }
        return resolved;
    }

    /** 按表 id 解析生效参数（Paimon 格式；保留给既有调用方）。 */
    @Transactional(readOnly = true)
    public Map<String, Map<String, String>> resolveByTable(String catalogId, String tableId) {
        return resolveByTable(CatalogFormat.PAIMON, catalogId, tableId);
    }

    /** 按表 id 解析生效参数（按格式）；策略不存在（表已删或还没配过）时全部走默认值。 */
    @Transactional(readOnly = true)
    public Map<String, Map<String, String>> resolveByTable(CatalogFormat format, String catalogId, String tableId) {
        TableOptimizationEntity policy = policyRepository.findByTableId(tableId).orElse(null);
        return resolve(format, catalogProperties(catalogId), tableConfigs(policy));
    }

    /** 单项动作的生效参数（Paimon 格式；保留给既有调用方）。 */
    public Map<String, String> resolve(Action action, Map<String, String> catalogProperties,
                                       Map<String, String> tableConfig) {
        return resolve(CatalogFormat.PAIMON, action, catalogProperties, tableConfig);
    }

    /**
     * 单项动作的生效参数（按格式）。
     *
     * <p>合并后仍要过一次格式自己的校验：catalog 默认值是运维直接改属性的，没人拦得住。
     * 校验不过就**整项回落内置默认**并打 WARN——逐键丢弃会拼出自相矛盾的组合
     * （例如 {@code strategy=sort} 配一个被丢掉的排序列）。
     */
    public Map<String, String> resolve(CatalogFormat format, Action action,
                                       Map<String, String> catalogProperties,
                                       Map<String, String> tableConfig) {
        Map<String, String> catalogDefaults =
                OptimizationOptions.catalogDefaults(catalogProperties, format, action);
        Map<String, String> resolved =
                OptimizationOptions.resolve(format, action, catalogProperties, tableConfig);
        try {
            OptimizationOptions.validate(format, action, resolved);
        } catch (RuntimeException exception) {
            log.warn("catalog level {} defaults are invalid ({}), falling back to built-in defaults: {}",
                    action.wire(), exception.getMessage(), OptimizationOptions.defaults(format, action));
            return OptimizationOptions.defaults(format, action);
        }
        if (!catalogDefaults.isEmpty()) {
            log.debug("{} resolved with catalog defaults {}", action.wire(), catalogDefaults);
        }
        return resolved;
    }
}
