package io.github.melin.lakehouse.rest.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：把鉴权与授权拦截器挂到 catalog API、管理 API 与控制台扩展端点上。
 *
 * <p>三套 API 共用同一层认证，与 Polaris 的做法一致：管理服务与 catalog 服务
 * 由同一进程承载，认证只做一次。
 *
 * <p><b>认证范围是「默认保护，显式豁免」。</b>路径通配符收得比实际需要的宽
 * （{@code /api/catalog/v1/**} 目前只有一个端点），因为漏保护一个新端点是
 * 静默的——它上线后能匿名访问，而没有任何测试会发现。反过来，
 * 多保护一个端点会立刻表现为 401，一眼可见。豁免项因此逐个列出，
 * 每条都有必须存在的理由：
 *
 * <ul>
 *   <li>{@code /api/console/v1/auth}：登录页要先知道「支持哪些登录方式」，
 *       它被挡在鉴权后面会形成死循环。
 *   <li>{@code /api/console/v1/login}：用它换令牌。
 *   <li>{@code /api/catalog/v1/oauth/tokens}：同上，OAuth 版本。
 *   <li>{@code /v1/oauth/tokens}：同一个 OAuth 令牌端点的**另一个路径**（Iceberg 规格里的
 *       那一个）。Iceberg 客户端在拿到 {@code /v1/config} 之前就要用它换令牌，
 *       因此它在 {@code /v1/**} 下却不带 prefix，也必须可达。两个路径由同一个方法处理，
 *       理由见 {@code OAuthTokenController}。
 * </ul>
 *
 * <p>控制台自身的静态资源（{@code /console/**}）不在认证范围内。这不是豁免，
 * 而是浏览器无法在一个文档请求上带 {@code Authorization} 头——保护它的唯一办法是
 * Cookie 会话，而引入 Cookie 会把 CSRF 一并引入，对一组不含任何数据的
 * JS 与 CSS 来说不值得。页面里的数据请求仍然逐个走鉴权。
 *
 * <p><b>接口文档页（{@code /doc.html}、{@code /v3/api-docs/**}、{@code /swagger-ui/**}、
 * {@code /webjars/**}）同样不需要登录。</b>它们压根不在上面那四个受管前缀里，
 * 因此拦截器不会被调用；下面的 {@code excludePathPatterns} 里仍把这几条写出来，
 * 是为了**万一将来把受管前缀放宽**（例如改成 {@code /**}）时，文档页不会顺手变成
 * 「要先登录才能看接口清单」——那种改动看起来只是一行通配符，影响却是所有人
 * 打不开文档。文档页公开与端点公开是两件事：它只把已有端点列出来，
 * 「调试」发出去的请求照样要过 Bearer 鉴权与表级授权，见 {@code ApiDocEndpointTests}。
 *
 * <p><b>三条豁免路径的使用范围由 {@link BearerAuthInterceptor} 决定</b>：
 * 它在「整体鉴权开着」或「控制台要求登录」时都要求令牌，
 * 后者只作用于 {@code /api/console/v1/**}——理由写在那个类上。
 *
 * <p>注册顺序即执行顺序：{@link BearerAuthInterceptor} 先解析主体，
 * {@link AuthorizationInterceptor} 再按 {@link CatalogAccessRules} 判定权限。
 * 授权拦截器只作用于 {@code /v1/**}，管理 API 的判定发生在控制器内
 * （因为要等请求体解析出实体名）。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final BearerAuthInterceptor bearerAuthInterceptor;
    private final AuthorizationInterceptor authorizationInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(bearerAuthInterceptor)
                .addPathPatterns(
                        "/v1/**",
                        // Lance 面挂在自己的基址上，不在 /v1/** 之下——漏了这一行，
                        // 整个面就是匿名可调，而且没有任何既有测试会发现
                        // （见 docs/lance-rest-catalog-design.md §4.5 风险 8）。
                        "/lance/v1/**",
                        "/api/catalog/v1/**",
                        "/api/management/v1/**",
                        "/api/console/v1/**")
                .excludePathPatterns(
                        "/api/console/v1/auth",
                        "/api/console/v1/login",
                        "/api/catalog/v1/oauth/tokens",
                        "/v1/oauth/tokens",
                        // 接口文档（knife4j-next / springdoc）。今天它们不在受管前缀里，
                        // 这几行是给「以后把 addPathPatterns 放宽」那一天的保险——
                        // 阅览页面不需要登录是明确要求，不该被一次通配符改动带走。
                        "/doc.html",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/webjars/**",
                        "/knife4j/**");
        registry.addInterceptor(authorizationInterceptor)
                .addPathPatterns("/v1/**",
                        // Lance 面：与上面那条认证路径成对出现。只改拦截器内部的分支
                        // 而漏这一行，效果是「/lance/** 完全不判权」——静默的那一类
                        "/lance/v1/**");
    }
}
