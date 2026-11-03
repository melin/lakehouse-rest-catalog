package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.PrincipalEntity;
import io.github.melin.lakehouse.rest.domain.entity.PrincipalSource;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 管理主体仓储。 */
public interface PrincipalRepository extends JpaRepository<PrincipalEntity, String> {

    Optional<PrincipalEntity> findByName(String name);

    /**
     * 按 clientId 查主体。
     *
     * <p>OAuth 2.0 客户端凭据流程的入口：请求里带的是 clientId 而不是主体名，
     * 而授权判定需要主体名，因此这一步是必须的翻译。
     * 返回值可能是空——调用方不能据此短路掉密钥比较，
     * 否则响应时间会泄漏「这个 clientId 存不存在」。
     */
    Optional<PrincipalEntity> findByClientId(String clientId);

    boolean existsByName(String name);

    List<PrincipalEntity> findAllByOrderByNameAsc();

    /**
     * 按来源过滤。
     *
     * <p>控制台「主体清单」要能把「IdP 来的」与「本地建的」分开看：
     * 前者不能在这里改密码（它没有凭据），后者不能靠登录自动产生。
     * 排序键仍是名称，与 {@link #findAllByOrderByNameAsc()} 一致，
     * 这样加不加过滤都得到同一个相对顺序。
     */
    List<PrincipalEntity> findBySourceOrderByNameAsc(PrincipalSource source);
}
