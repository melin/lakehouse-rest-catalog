package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.SensitivitySuppressionEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 永久抑制项的仓储（设计见 {@code docs/sensitive-data-design.md} §7.2）。
 *
 * <p><b>每条查询都带 {@code enabledFlag}。</b>这不是啰嗦：抑制项有「解禁」这条路，
 * 而解禁是置 {@code false} 不删行。任何一条漏了它的查询都会让解禁变成一句空话
 * ——表现是「点了恢复，第二天扫描又把它跳过了」。
 * 与 {@code ColumnTagRepository} 里「停用的标签不参与策略展开」是同一类坑：
 * **状态列存在时，每条读路径都要显式表态**。
 */
public interface SensitivitySuppressionRepository
        extends JpaRepository<SensitivitySuppressionEntity, Long> {

    /** 这一列的这个类型被否决过没有（人审判重、扫描落标前的最后一道判据）。 */
    Optional<SensitivitySuppressionEntity> findByColumnRefHashAndTypeCode(String columnRefHash,
                                                                         String typeCode);

    /** 一个 catalog 里仍生效的抑制项（扫描开跑前一次取全，避免逐列回查）。 */
    List<SensitivitySuppressionEntity> findByCatalogNameAndEnabledFlagTrueOrderByDecidedAtDesc(
            String catalogName);

    /** 一个 catalog 的全部抑制项（含已解禁的；界面要能显示「解除过」这件事）。 */
    List<SensitivitySuppressionEntity> findByCatalogNameOrderByDecidedAtDesc(String catalogName);

    /** 一批列引用里仍生效的抑制项（人审界面按行取用）。 */
    List<SensitivitySuppressionEntity> findByColumnRefHashInAndEnabledFlagTrue(
            Collection<String> columnRefHashes);

    /** 某列的抑制历史（列详情页的「这列被判过什么、被否过什么」）。 */
    List<SensitivitySuppressionEntity> findByColumnRefHashOrderByCreatedAtDesc(String columnRefHash);
}
