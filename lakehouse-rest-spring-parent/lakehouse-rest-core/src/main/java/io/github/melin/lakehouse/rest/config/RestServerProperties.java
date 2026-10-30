package io.github.melin.lakehouse.rest.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code lakehouse.rest.*} 配置项。
 *
 * <p>加了 {@link Validated}：数值与时长类配置写错（负数、零）会让服务带着无效参数跑起来，
 * 直到第一次真正用上才暴露。这里让它在启动绑定时就失败，错误信息带上属性名，
 * 比运行时再看日志去猜要省事。
 */
@ConfigurationProperties(prefix = "lakehouse.rest")
@Validated
@Getter
@Setter
public class RestServerProperties {

    /** {@code GET /v1/config} 未指定 warehouse 时返回的默认 prefix。 */
    private String defaultPrefix = "paimon";

    private String defaultWarehouse = "file:///tmp/paimon-warehouse";

    /**
     * prefix 未登记时是否自动登记为 catalog。
     *
     * <p>打开后，引擎首次以任意 warehouse 值调用 {@code /v1/config} 即可接入，
     * 无需预先在服务端创建 catalog。
     */
    private boolean autoCreateCatalog = true;

    /**
     * 自动登记（以及建 catalog 时未指定格式）create 出来的 catalog 用哪种表格式。
     *
     * <p>不设这个开关会有一个很难查的故障：一个 Iceberg 客户端敲了一个尚未登记的
     * prefix，服务端按默认的 {@code PAIMON} 自动建出一个 catalog，于是该客户端
     * **此后每一次调用都是 404**，而错误信息是「Unknown catalog prefix」——
     * 它会以为自己的 prefix 写错了，真正的原因却是格式不对。
     *
     * <p>这是「按部署选择自动登记建哪种格式」的那一半对策；另一半是「格式不匹配时
     * 报明确的错」（见 {@code CatalogService#resolveForFormat}），两者都要有：
     * 后者覆盖「catalog 已存在但格式不对」，前者覆盖「两边都没说清」。
     */
    private String defaultCatalogFormat = "PAIMON";

    /** 表路径模板，可用占位符 {@code {warehouse}}、{@code {database}}、{@code {table}}。 */
    private String pathTemplate = "{warehouse}/{database}.db/{table}";

    private int defaultPageSize = 100;

    private int maxPageSize = 1000;

    private final Auth auth = new Auth();

    private final Credential credential = new Credential();

    private final InitialCatalog initialCatalog = new InitialCatalog();

    private final Authorization authorization = new Authorization();

    private final Storage storage = new Storage();

    private final StorageCredentialCache storageCredentialCache = new StorageCredentialCache();

    private final CredentialManager credentialManager = new CredentialManager();

    private final FileIo fileIo = new FileIo();

    private final TableMetadata tableMetadata = new TableMetadata();

    /** {@code lakehouse.rest.file-browser.*}：控制台的文件浏览器（见 {@link FileBrowser}）。 */
    private final FileBrowser fileBrowser = new FileBrowser();

    /** {@code lakehouse.rest.storage-probe.*}：保存前存储自检（见 {@link StorageProbe}）。 */
    private final StorageProbe storageProbe = new StorageProbe();

    /** {@code lakehouse.rest.optimization.*}：表存储优化（见 {@link Optimization}）。 */
    private final Optimization optimization = new Optimization();

    /** {@code lakehouse.rest.lineage.*}：Spark SQL 血缘（见 {@link Lineage}）。 */
    private final Lineage lineage = new Lineage();

    /** {@code lakehouse.rest.policy.*}：行过滤 / 列脱敏策略（见 {@link DataPolicy}）。 */
    private final DataPolicy policy = new DataPolicy();

    /**
     * 行过滤 / 列脱敏的写入口开关（{@code docs/row-filter-column-mask-design.md} §5.4）。
     *
     * <p><b>默认开启，只锁写入口、读永远不锁</b>——与血缘开关同一模式：把「功能没开」与
     * 「没建策略」分开。读侧（resolve、策略详情、列表）任何情况下都不受它限制，
     * 否则「上下文包 404」和「本来就没策略」就混成一件事了，引擎侧的 fail-closed
     * 无法区分故障与空态。
     *
     * <p>缺省取开，取的是「开箱即用就具备治理能力」：策略的<b>执行</b>在引擎侧由
     * {@code spark.lakehouse.rest.policy.enforced} 控制（那里缺省不注入），因此服务端
     * 放开写入口不会让任何查询突然开始被过滤——它只决定「能不能把规则存进来」。
     * 不开治理的部署显式关掉：{@code --lakehouse.rest.policy.enabled=false}。
     * 注意它与 {@code auth.enabled} 是两个维度：后者缺省仍是关，所以单机默认状态下
     * 「能改脱敏规则」等同于「能访问管理 API」，别把它当成一层权限。
     */
    @Getter
    @Setter
    public static class DataPolicy {

        /**
         * 是否允许创建 / 修改 / 删除策略（PUT / DELETE）。<b>默认 true。</b>
         *
         * <p>关掉之后写入口一律 403 且报错写明本配置项，读侧照常（{@code /resolve}、
         * 策略详情、列表）。已下发的策略在引擎侧继续执行——这个开关管的是
         * 「这个部署还开不开放治理」，不是「已存的策略要不要生效」。
         */
        private boolean enabled = true;
    }

    /** Bearer 鉴权开关。关闭时所有请求以 {@code principal} 身份通过。 */
    @Getter
    @Setter
    public static class Auth {
        private boolean enabled = false;
        private String principal = "anonymous";

        /**
         * 静态令牌：给机器用的长期凭据。
         *
         * <p>Spark / Flink 客户端把其中一个值写进配置，此后不再变更。
         * 浏览器里不该用它——运维换人不改配置就没法区分，而写在客户端配置里的
         * 令牌也很难轮转。浏览器走 {@link Console} 的两种交互式登录。
         */
        private List<String> tokens = new ArrayList<>();

        /**
         * 是否登记了至少一个可用的静态令牌。
         *
         * <p>按「有非空项」而不是「列表非空」判断：清空配置时很常见地留下一个空串，
         * 它不该被当成「已开启」；空白令牌本身也过不了令牌匹配。
         *
         * <p>这是静态令牌有没有「开启」的唯一判据——它没有独立的 {@code enabled} 开关，
         * 「开启」的表现就是配置里有值。登录页要不要显示降级入口、启动日志要不要报告它，
         * 都以这里为准；两处各写一遍判断，迟早漂移成「日志里有、页面上没有」。
         */
        public boolean hasStaticTokens() {
            return tokens.stream().anyMatch(token -> token != null && !token.isBlank());
        }

        /**
         * 令牌 → 主体名的显式映射。
         *
         * <p>授权判定按主体名查授权链路，因此开启授权时应当配置该映射；
         * 未配置的令牌退化为「令牌即主体名」。
         */
        private Map<String, String> tokenPrincipals = new LinkedHashMap<>();

        private final AccessToken accessToken = new AccessToken();

        private final Console console = new Console();

        /**
         * {@code lakehouse.rest.auth.access-token.*}：控制台登录签发的访问令牌。
         *
         * <p><b>令牌是自包含的 JWT，不是服务端会话。</b>签名密钥在配置里，
         * 因此多实例部署下任一实例签发的令牌其它实例都认，服务端重启也不失效。
         * 上一版把会话存在内存里，多实例部署时登录只对命中的那个实例有效，
         * 重启即全部掉线——那是设计缺陷，不是可接受的取舍。
         *
         * <p><b>代价是无法即时撤销。</b>令牌一旦签发，在过期前始终有效；
         * 轮换密钥能让所有令牌立刻失效，但那也会踢掉所有人。要即时撤销单个令牌
         * 得引入服务端黑名单或共享存储，这里选择不做，改为把 TTL 设短。
         * 这个取舍写在 {@code docs/console-auth.md} 里。
         */
        @Getter
        @Setter
        public static class AccessToken {

            /**
             * 令牌有效期。
             *
             * <p>设成「浏览器一次工作会话的长度」而不是「一天」：既然没有撤销机制，
             * TTL 就是唯一的暴露窗口上限。过期后控制台会自动跳回登录页。
             */
            @NotNull
            private Duration ttl = Duration.ofHours(1);

            /**
             * 签发者标识（JWT 的 {@code iss}）。
             *
             * <p>本服务端自己签发的令牌用它做自校验；同时也是给下游的提示——
             * 引擎侧若要把令牌转发给别处，凭这个字段能判断令牌来自谁。
             */
            private String issuer = "paimon-rest";

            /**
             * HMAC-SHA256 签名密钥，Base64 编码，解码后至少 32 字节。
             *
             * <p><b>留空时启动生成随机密钥</b>，此时服务端重启会让所有令牌失效、
             * 多实例之间互不认账。本地单机调试可以接受，生产必须显式配置——
             * 生成方式：
             * {@code openssl rand -base64 48}。启动日志会就此给出告警。
             */
            private String signingKey;

            /**
             * 一条「续期链」的最长寿命，{@code 0} 表示不限。
             *
             * <p><b>它与 {@link #ttl} 回答的不是同一个问题。</b>{@code ttl} 是「一张票能活多久」，
             * 这一项是「靠不断换票能往前跑多久」。没有它，一张泄露的票可以在持有者一直
             * 活着的情况下被链式续到无限（见 {@code docs/engine-token-refresh-design.md} §7）。
             *
             * <p>**默认关闭（{@code 0}）**：打开它会让跑超过 N 小时的作业必须重启，
             * 而在本功能落地之前，长作业连一小时都过不去。需要「作业不能永久持有访问权」
             * 的合规场景再打开。
             *
             * <p>实现方式是令牌里多一个 {@code ses} claim（会话起点）。换票时沿用父票的
             * {@code ses}（没有就取父票的 {@code iat}），于是整条链共享同一个起点；
             * 超过上限就拒绝交换，而**已签发的票仍然可用到期**——否则正在跑的作业会
             * 在没有任何预兆的情况下 401。
             */
            private Duration maxSession = Duration.ZERO;

            /** 是否对续期链设总时长上限（即 {@link #maxSession} 是否生效）。 */
            public boolean maxSessionEnabled() {
                return maxSession != null && !maxSession.isZero() && !maxSession.isNegative();
            }
        }

        /**
         * {@code lakehouse.rest.auth.console.*}：浏览器访问控制台时的登录方式。
         *
         * <p>这里有两层开关，别混在一起看：
         *
         * <ol>
         *   <li>{@code console.required}（默认 {@code true}）：<b>控制台要不要先登录</b>。
         *       它只影响控制台自己——前端会停在登录页，{@code /api/console/v1/**}
         *       也要求令牌。
         *   <li>{@link Auth#isEnabled()}（{@code auth.enabled}）：<b>整个服务端要不要令牌</b>。
         *       它管的是 {@code /v1/**}、{@code /api/catalog/v1/**}、
         *       {@code /api/management/v1/**}——也就是引擎与脚本用的数据面。
         * </ol>
         *
         * <p><b>为什么分成两层。</b>把 {@code auth.enabled} 默认打开会让开箱即用的
         * {@code curl /v1/config}、Spark 示例、两个验收脚本全部变成 401，
         * 而它们恰恰是这个工程最容易上手的入口。反过来，控制台是给人看的界面，
         * 默认要求登录既能挡住随手点开的人，也不改变任何既有调用方的行为。
         *
         * <p><b>必须说清楚的一点：控制台门禁不是安全边界。</b>数据面默认仍然匿名可调，
         * 绕过浏览器直接 curl 即可读到同样的数据。它防的是「有人随手打开了这个地址」，
         * 不是「有人想拿数据」。真正的访问控制要把 {@code auth.enabled} 设为 true，
         * 那时控制台与数据面一起受令牌约束。这句话在登录页与
         * {@code docs/console-auth.md} 里也各写了一遍。
         *
         * <p>登录方式三种并存，各自独立开关，都（且只）产出一个 access token，
         * 之后走同一条 {@code Authorization: Bearer} 通道：
         *
         * <ol>
         *   <li>{@link Password}：服务端配置里的用户名与密码（默认 {@code admin/admin}），
         *       默认开启，排在登录页第一个——它不需要运维先去建主体，开箱即用。
         *   <li>{@link ClientCredentials}：OAuth 2.0 客户端凭据流程，凭据是
         *       <b>主体自己的 clientId / clientSecret</b>，令牌由本服务端签发。
         *       对应 Polaris Console 的默认方式。
         *   <li>{@link Oidc}：OpenID Connect 授权码 + PKCE，令牌由外部身份提供方签发，
         *       本服务端只做验签。对应 Polaris Console 的可选方式。
         * </ol>
         */
        @Getter
        @Setter
        public static class Console {

            /**
             * 控制台是否要求先登录。
             *
             * <p>默认 {@code true}：打开控制台先看到登录页。设成 {@code false} 时
             * 控制台不需要登录即可进入——只有当 {@code auth.enabled} 也为 true
             * （数据面一起受约束）时才有意义，否则只是把界面上的门打开。
             */
            private boolean required = true;

            private final Password password = new Password();

            private final ClientCredentials clientCredentials = new ClientCredentials();

            private final Oidc oidc = new Oidc();

            /** 是否提供任何一种浏览器登录方式。 */
            public boolean anyMethodEnabled() {
                return password.isEnabled() || clientCredentials.isEnabled() || oidc.isEnabled();
            }

            /**
             * 当前是否真的需要登录。
             *
             * <p>两种情形都算：整体鉴权开着（数据面也要令牌），或控制台单独要求登录。
             * 前者开着时即使把关禁关掉，前端照样会在每个请求上吃 401，
             * 因此不能只看 {@code required}。
             */
            public boolean loginRequired(boolean authEnabled) {
                return authEnabled || required;
            }
        }

        /**
         * 用户名 + 密码登录（控制台的默认方式）。
         *
         * <p>账号来自配置而不是数据库：它要回答的是「谁可以打开控制台」，
         * 与授权链路里的主体是两件事——前者是开门的钥匙，后者决定进门后能做什么。
         * 登录成功签发的令牌仍然带上主体名（默认就是用户名），
         * 因此授权判定照常按主体名走。
         *
         * <p><b>默认账号 {@code admin/admin} 必须在生产前改掉。</b>这是一个
         * 「开箱能用」的默认值，不是建议值：任何知道这个地址的人都能用它进来。
         * 服务端在密码仍是默认值时会在启动日志里告警。
         */
        @Getter
        @Setter
        public static class Password {

            private boolean enabled = true;

            /**
             * 用户名 → 密码。
             *
             * <p>密码在配置里是明文（与 {@code auth.tokens} 的静态令牌同理）：
             * 服务端要在内存里比对它，保存摘要只是把明文换个地方暴露，
             * 并不能让它更安全；真正的做法是把它交给配置中心或密钥管理系统。
             */
            private Map<String, String> users = new LinkedHashMap<>(Map.of("admin", "admin"));

            /**
             * 把用户名翻译成主体名时的映射；未配置的用户名即主体名。
             *
             * <p>与 {@code auth.token-principals} 同一个用途：让「开门的账号」
             * 与「授权链路里的主体」可以叫不同名字，例如把 {@code admin}
             * 映射到已有的服务管理员主体，避免为它再建一个主体。
             */
            private Map<String, String> principals = new LinkedHashMap<>();

            private final RateLimit rateLimit = new RateLimit();
        }

        /**
         * OAuth 2.0 客户端凭据流程。
         *
         * <p>控制台把用户填的 clientId / clientSecret 发到本服务端的令牌端点
         * （{@code POST /api/catalog/v1/oauth/tokens}），换回一个 Bearer 令牌。
         * 服务端按 {@code lakehouse_principal} 表校验这对凭据，与引擎调用管理 API 时
         * 用的是同一份数据。
         */
        @Getter
        @Setter
        public static class ClientCredentials {

            private boolean enabled = true;

            private final RateLimit rateLimit = new RateLimit();
        }

        /**
         * 令牌端点与 OIDC 验证共用的失败限速。
         *
         * <p>令牌端点必须匿名可访问（它就是用来换取令牌的），因此它是这套鉴权里
         * 唯一能被匿名反复打的地方。没有限速时，攻击者可以用一个已知的 clientId
         * 反复猜 clientSecret——clientSecret 是 32 字节随机值，猜不中，
         * 但猜测请求本身会把数据库打满。这里的限速针对的是这件事。
         *
         * <p>计数按「客户端标识 + 来源 IP」分桶，只在<b>失败</b>时累加：
         * 正常用户会不断刷新令牌，把成功也计入会让长时间开着的控制台被自己的
         * 正常流量锁住。
         */
        @Getter
        @Setter
        public static class RateLimit {

            private boolean enabled = true;

            /** 窗口内允许的失败次数，超过后该桶在窗口剩余时间内一律拒绝。 */
            @Min(1)
            private int maxFailures = 10;

            private Duration window = Duration.ofMinutes(1);
        }

        /**
         * OpenID Connect 授权码 + PKCE。
         *
         * <p>运行时只有一处需要访问外部：从 {@code issuer-uri} 拉取发现文档与 JWKS。
         * 两者都带缓存且惰性加载，因此外部 IdP 短暂不可用时，已经在用控制台的人
         * 不受影响（令牌是自包含的，验签只需已缓存的公钥）。
         */
        @Getter
        @Setter
        public static class Oidc {

            private boolean enabled = false;

            /**
             * 身份提供方的 issuer URL，例如
             * {@code https://keycloak.example.com/realms/EXTERNAL}。
             *
             * <p>服务端据此拉取 {@code /.well-known/openid-configuration}，
             * 拿到授权端点、令牌端点与 JWKS 地址，再转告控制台。
             * 运维只配这一个 URL，端点变化（IdP 升级、换域名）不需要改配置。
             */
            private String issuerUri;

            /** 在 IdP 注册的客户端标识（公开客户端，走 PKCE，没有密钥）。 */
            private String clientId;

            /**
             * 回调地址，必须与 IdP 侧登记的一致，例如
             * {@code http://localhost:8080/console/auth/callback}。
             *
             * <p>由服务端下发而不是前端自己拼：IdP 比对的是完整字符串，
             * 端口、路径、结尾斜杠差一个字符都会被拒绝，而这个错误在浏览器里
             * 只表现为「回到 IdP 时报 redirect_uri 不合法」。
             */
            private String redirectUri;

            /**
             * 登出后回到哪里，例如 {@code http://localhost:8080/console/login}。
             *
             * <p>同样必须在 IdP 侧登记过（RP-initiated logout 的
             * {@code post_logout_redirect_uri}），而且它与 {@link #redirectUri}
             * **不是同一个地址**：那个带 {@code /auth/callback}，是授权码的落点。
             *
             * <p>留空则登出时不下发这个参数，浏览器停在 IdP 自己的「已退出」页面，
             * 需要用户自己走回来。这不是缺陷：IdP 会逐字比对登记过的地址，
             * 服务端擅自拼一个只会换来它页面上一句语焉不详的拒绝，比停在登出页更难排查。
             */
            private String postLogoutRedirectUri;

            private String scope = "openid profile email";

            /**
             * 从哪个 claim 取主体名。
             *
             * <p>默认 {@code sub}。要让授权链路认得出人，这个 claim 的取值需要
             * 与 {@code lakehouse_principal.name} 或
             * {@code lakehouse.rest.authorization.service-admins} 中的名字对得上，
             * 否则登录成功但没有任何权限——现象是「每个页面都 403」。
             */
            private String principalClaim = "sub";

            /**
             * 期望的 {@code aud}。
             *
             * <p>留空则不校验受众。多 IdP 或多客户端共用一个 issuer 时应当填上，
             * 否则给别的应用签发的令牌也能拿来访问本服务端。
             */
            private String audience;

            /** OIDC 发现文档的缓存时长。 */
            @NotNull
            private Duration metadataCacheTtl = Duration.ofMinutes(10);

            /** JWKS 的缓存时长。 */
            @NotNull
            private Duration jwksCacheTtl = Duration.ofHours(1);

            /**
             * 允许的时钟偏移。
             *
             * <p>IdP 与本服务端的时间不可能完全一致，留一点余量，
             * 避免「刚签发的令牌被判为尚未生效」。
             */
            @NotNull
            private Duration clockSkew = Duration.ofSeconds(60);
        }
    }

    /**
     * RBAC 授权开关。
     *
     * <p>默认关闭：关闭时管理 API 只做读写，catalog API 不做权限校验，
     * 与未开启授权的既有部署行为完全一致。开启后管理 API 会校验调用者权限，
     * 且 catalog API 会按 {@code @RequiresPrivilege} 声明校验。
     *
     * <p>开启时建议同时打开 {@code lakehouse.rest.auth.enabled}，
     * 否则所有请求都落到同一个主体上，授权判定失去区分度。
     */
    @Getter
    @Setter
    public static class Authorization {
        private boolean enabled = false;

        /**
         * 服务管理员主体名。
         *
         * <p>官方管理 API 把主体、catalog 的生命周期明确归给服务端，官方 12 项 access
         * 里没有对应取值，因此这里用名单表达。名单内的主体可以管理 principal 与
         * principal role，并在任意 catalog 上放行全部授权判定（含 {@code GRANT}）。
         */
        private List<String> serviceAdmins = new ArrayList<>(List.of("root"));

        /**
         * 启动时预置的引导主体名。
         *
         * <p>与 {@link #serviceAdmins} 配合，构成授权体系的自举入口：
         * 没有它，全新部署没有任何主体能创建第一个主体。留空则不预置。
         */
        private String bootstrapPrincipal = "root";

        /**
         * 启动时预置的引导角色：该角色会获得预置 catalog 上的
         * {@code CATALOG/ALL} 与 {@code CATALOG_ALL/ALL}，即该 catalog 的管理员。
         * 留空则只建引导主体、不授任何权。
         */
        private String bootstrapPrincipalRole = "service_admin";
    }

    /** 数据访问令牌（凭证下发）配置。 */
    @Getter
    @Setter
    public static class Credential {

        /**
         * 下发凭据的有效期（秒）。接上 STS 之后它同时是**密钥寿命**，
         * 不再只是「建议多久后重取」——两者分为两个值就会出现
         * 「签名 15 分钟、声明 1 小时」的错配。
         */
        private long ttlSeconds = 3600;

        /**
         * 启用的签发 provider 名，默认**空**（等价于只有 {@code relay}）。
         *
         * <p>为什么默认空而不是默认全开：{@code s3-token} 会在读表的路径上引入一次
         * 远程调用（有配额、会超时、会因 IAM 配错而失败）。默认打开等于把
         * 「今天能正常读表」的部署升级成一个可能读不了的部署，而且失败信息来自
         * 另一个系统。要启用请显式写 {@code lakehouse.rest.credential.providers: s3-token}。
         *
         * <p>写了不存在的名字启动即失败，与 {@code credential-manager.type} 同一取舍：
         * 一个拼错的 provider 名若被静默忽略，表现是「明明配了 STS，出来的还是长期密钥」。
         */
        private List<String> providers = new ArrayList<>();

        /**
         * 短期凭据在缓存里保留的比例，默认 {@code 0.15}。
         *
         * <p>含义是「缓存只留票寿命的这一部分」，即 1 小时的票在缓存里留 9 分钟。
         * 与 Gravitino 的 {@code credential-cache-expire-ratio} 同名同值同义。
         * 取满 TTL 的代价是把风险转嫁给引擎：客户端在票快过期时拿到手，
         * 用几秒就 403，而它没有任何理由预期这件事。
         *
         * <p>**只对短期凭据生效**：静态凭据没有「快过期」这回事，按比例缩短
         * 只会让转发档每次读表都重新解析一遍配置，换不到任何安全收益。
         */
        private double cacheExpireRatio = 0.15;

        /**
         * AssumeRole 时是否带 session tags（审计归因），默认 {@code false}。
         *
         * <p>打开后每次签发带三个 tag：{@code lh:principal} / {@code lh:scope} /
         * {@code lh:access}，云侧的 CloudTrail 与 S3 访问日志里就能直接看出
         * 「哪个人、读哪张表、读还是写」（设计稿 §5.6）。{@code RoleSessionName}
         * 只到「谁 + 哪个 scope」，而且它是**字符串**，没法按字段过滤；tag 可以。
         *
         * <p><b>为什么默认关：这是一个会让 AssumeRole 直接失败的外部前提。</b>
         * AWS 要求被 assume 的角色信任策略里放行 {@code sts:TagSession}，
         * 没有这一句时带 tag 的调用回 {@code AccessDenied}——现象是「升级之后所有读表
         * 都 503，报错还是权限不足」，而配置里什么都没改。默认打开等于把一个
         * 纯运维决定塞进升级动作里。要启用：先在信任策略里加 {@code sts:TagSession}，
         * 再写 {@code lakehouse.rest.credential.session-tags: true}。
         *
         * <p>关掉时**不传 tag 集合**（不是传空数组）：两者的云侧行为一样，
         * 但「不传」能保证与改造前的请求逐字节相同，抓包对比因此还能用。
         */
        private boolean sessionTags = false;

        /**
         * 云侧回限流时，一次签发动作最多尝试几次（含第一次），默认 {@code 3}。
         *
         * <p>只对**限流**重试。权限不足、角色不存在、网络不通都不重试：
         * 它们重试一百次结果一样，而每次重试都要占一个请求线程。
         * 判据见 {@code ThrottledException}。
         *
         * <p>为什么是 3 而不是更多：签发跑在读表的同步路径上，重试的代价直接是
         * 引擎的等待时间。3 次、退避 200/400ms，最坏多等 0.6 秒；
         * 再往上加，一次读表要多等好几秒，不如让调用方自己重来。
         */
        private int retryAttempts = 3;

        /**
         * 限流重试的首次退避（毫秒），默认 {@code 200}，之后每次翻倍。
         *
         * <p>翻倍是云侧限流的惯例（指数退避），也是「短窗口内别再加压」的唯一表达。
         * 加抖动是为了避免一批引擎在同一毫秒一起醒来，把退避变成新的尖峰。
         */
        private long retryBackoffMillis = 200;

        /**
         * 连续限流后进入的冷却时长（秒），默认 {@code 10}。
         *
         * <p><b>这一档才是真正的「退避」。</b>重试解决的是「偶发的一两次限流」；
         * 配额真被打满时，重试只会在别人的修复动作之前把线程一起拖住。
         * 进入冷却后，同一个 provider 的签发**直接失败**（{@code 503} + 剩余秒数），
         * 不再向云发起请求——这就是给云侧让路，也让故障从「全站变慢」收敛成
         * 「一个错误率」。
         *
         * <p>冷却时间随连续失败次数翻倍（{@code 10s → 20s → 40s}），
         * 上限 {@code 6 ×} 本值；一次成功即清零。
         */
        private long throttleCooldownSeconds = 10;

        /**
         * 是否要求「每份签发都用 catalog 自己带的那对 AK/SK 去 assume」，默认 {@code false}。
         *
         * <p><b>这是服务端身份分层的开关（设计稿 P2-2）。</b>理想形态里，服务端自己那把
         * 身份只该有 {@code sts:AssumeRole}（外加换成目标角色所需的最小集），
         * 数据面的权限全在角色上；而「用哪把钥匙去 assume」按 catalog 决定。
         * 打开后，一个配了 {@code roleArn} 却没填 AK/SK 的 catalog 会**直接失败**，
         * 而不是悄悄退回服务进程的默认凭据链（实例角色 / IRSA / 环境变量）。
         *
         * <p><b>为什么默认关。</b>退回默认链不是错误，而是很多部署的**正常形态**：
         * 整个服务只有一个工作负载身份（IRSA），所有 catalog 都靠它去 assume 各自的角色，
         * 角色上的权限差异已经够了。把它改成默认拒绝，升级当天这些部署会全量读不了表。
         * 想要「每个 catalog 一把最低权限钥匙」的部署显式打开即可。
         *
         * <p>失败是 {@code 503} 而不是 {@code 400}：catalog 的配置本身合法，
         * 是**本部署的策略**不允许用环境身份签。消息里点名这个配置项与改法。
         */
        private boolean requireCatalogSigner = false;
    }

    /** 启动时预置的 catalog 与 database。 */
    @Getter
    @Setter
    public static class InitialCatalog {
        private String prefix;
        private String warehouse;

        /**
         * 预置 catalog 的表格式（{@code PAIMON} / {@code ICEBERG}），留空则用
         * {@code lakehouse.rest.default-catalog-format}。
         *
         * <p>为什么值得单独一个开关：预置出来的是本地开发、演示环境里那把「默认钥匙」，
         * 而**格式决定了用它敲哪一套客户端**。没有这个开关时，想试 Iceberg 就得先手改
         * 启动配置里的 prefix、或者干脆手写一条管理 API 请求——前者会把 Paimon 的
         * 开发环境顶掉，后者要先把服务端跑起来。
         *
         * <p>只对**新建**生效：已存在的 catalog 不会被改（格式不可变，见
         * {@code CatalogFormat}），此时若配置与库中不一致会留一条 warn。
         */
        private String format;

        private List<DatabaseSeed> databases = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class DatabaseSeed {
        private String name;
        private Map<String, String> options = new LinkedHashMap<>();
    }

    /**
     * {@code lakehouse.rest.storage.*}：**加密密钥**与连接参数。
     *
     * <p><b>这里没有对象存储凭据，一行都没有，这是有意的。</b>AK/SK、Azure 的服务主体、
     * GCS 的服务账号 JSON、OSS 要 assume 的角色，全部跟着 catalog 走
     * （{@code storageConfigInfo} 里各自的字段），不在这份全局配置里。
     *
     * <p>理由是「每个 catalog 的存储配置不一样」：一个服务端同时挂生产桶与测试桶时，
     * 这两组密钥的签发人、轮转周期、失效方式都不同；把它们塞进同一份 yml 后，
     * 只有一条路能区分「这次该用哪把钥匙」——{@code storageName} 这个额外字段，
     * 而它只是把「配置在哪个桶」的答案挪了个位置，没有消除「全局配置里躺着别人的密钥」
     * 这件事本身。Polaris 的 {@code polaris.storage.aws.<name>.access-key} 是同一思路，
     * 本工程不再沿用它。
     *
     * <p>代价要写清楚：**密钥会随 catalog 落库**（{@code storage_config_json}），
     * 以 {@code credential-secret-key} 加密后的形态。这是「配置跟着 catalog 走」的
     * 必然结果，所以加密密钥留在这里——它保护的是库里的密文，不属于任何一个 catalog。
     *
     * <p><b>连接池与超时项的作用范围。</b>{@code read-timeout}、{@code connect-timeout}
     * 之外的连接池参数（{@code max-http-connections}、{@code connection-max-idle-time}、
     * {@code connection-time-to-live}、{@code connection-acquisition-timeout}、
     * {@code expect-continue-enabled}）在 JDK 的 {@code HttpClient} 上找不到对应调节项，
     * 当前实现只做取值校验与启动日志输出。保留这些键是为了配置面与 Polaris 一一对应，
     * 迁移时不必删配置、也不必担心它们悄悄失效——它们本来就没生效。
     */
    @Getter
    @Setter
    public static class Storage {

        /**
         * 静态凭据的落库加密密钥：base64 的 32 字节（AES-256），可用
         * {@code openssl rand -base64 32} 生成。
         *
         * <p>它保护的是 catalog 存储配置里由**使用者自己填写**的对象存储长期密钥
         * （{@code storageConfigInfo.accessKeyId} / {@code secretAccessKey}）——
         * 那种密钥要原样下发给引擎，服务端必须能还原明文，因此只能加密而不能摘要。
         * 密文形态与失效行为见 {@code CredentialCipher}。
         *
         * <p><b>留空时本功能关闭</b>：管理 API 拒绝保存带静态凭据的 catalog
         * （400，错误信息里给出生成命令），其余功能完全不受影响。
         * 取值非法（不是 base64、或解出来不是 32 字节）属于配置错误，启动即失败。
         */
        private String credentialSecretKey;

        /**
         * 上一把加密密钥（base64 的 32 字节），只在**轮换期间**配置，格式同
         * {@link #credentialSecretKey}。
         *
         * <p><b>为什么需要它。</b>没有这一项时，换密钥是一次「全站立刻失能」的操作：
         * 所有 catalog 的静态凭据都是旧钥密封的，换完第一秒起每一次下发都 500
         * （{@code CredentialCipher#reveal} 里那句「sealed with a different key」）。
         * 于是换钥只能靠「停机 + 批量重写」，而多数团队会因此永远不换——
         * 一个不敢用的安全措施等于没有。配了上一把之后，轮换变成三步：
         *
         * <ol>
         *   <li>新钥写 {@code credential-secret-key}，旧钥写本项，重启——</li>
         *       之后新旧密文都能解开；{@code seal} 一律用新钥（写永远是新的）。</li>
         *   <li>把还带旧密文的 catalog 逐个重新保存一次（`PUT` 时 {@code secretAccessKey}
         *       原样再交一次，或走一次运维脚本），密文即被新钥重写。</li>
         *   <li>确认没有旧密文在用之后，删掉本项再重启。</li>
         * </ol>
         *
         * <p><b>它只是「读的兜底」，不是第二把当前钥</b>：加密永远用
         * {@code credential-secret-key}。若允许「按哪把钥加密都行」，轮换就永远结束不了
         * ——永远有一半新一半旧的密文，而它们看着完全一样。
         *
         * <p>取值非法（不是 base64、或解出来不是 32 字节）同样启动即失败：
         * 让它带着「旧密文解不开」的状态跑起来，现象是「换钥之后一半 catalog 报 500」，
         * 而这本来正是这一项要消除的现象。
         */
        private String credentialSecretKeyPrevious;

        /** STS 客户端缓存上限，对应 Polaris 的 {@code polaris.storage.clients-cache-max-size}。 */
        @Min(1)
        private Integer clientsCacheMaxSize;

        /** 连接池上限，对应 Polaris 的 {@code polaris.storage.max-http-connections}。 */
        @Min(1)
        private Integer maxHttpConnections;

        /** 单次请求的读取超时。 */
        private Duration readTimeout;

        /** TCP 建连超时。 */
        private Duration connectTimeout;

        /** 从连接池取连接的等待上限。 */
        private Duration connectionAcquisitionTimeout;

        /** 池中连接的闲置上限。 */
        private Duration connectionMaxIdleTime;

        /** 池中连接的总存活时长。 */
        private Duration connectionTimeToLive;

        /** 是否发送 {@code Expect: 100-continue}。 */
        private Boolean expectContinueEnabled;

    }

    /**
     * {@code lakehouse.rest.storage-credential-cache.*}：下发的存储凭据的复用上限。
     *
     * <p>凭据在过期前会被重复下发，缓存避免每次读表都重新解析一遍配置。
     * 缓存条目按签发时的过期时间自然失效，因此这个上限只用于兜住
     * 「大量不同表各自持有一份未过期凭据」时的内存占用。
     */
    @Getter
    @Setter
    public static class StorageCredentialCache {

        /** 缓存条目上限，对应 Polaris 的 {@code polaris.storage-credential-cache.max-entries}。 */
        @Min(1)
        private long maxEntries = 10000;
    }

    /**
     * {@code lakehouse.rest.credential-manager.*}：凭据签发策略的选择。
     *
     * <p>对应 Polaris 的 {@code polaris.credential-manager.type}，取值是实现标识。
     * 取值非法时启动失败，而不是悄悄退回默认实现——
     * 一个被写错的 {@code noop} 若被当成默认值处理，会让「凭据已停发」的部署
     * 继续把凭据发出去。
     */
    @Getter
    @Setter
    public static class CredentialManager {

        private String type = "default";
    }

    /**
     * {@code lakehouse.rest.file-io.*}：本部署接入了哪些存储实现的 FileIO。
     *
     * <p>对应 Polaris 的 {@code polaris.file-io.type}。取值为
     * {@code default} / {@code s3} / {@code azure} / {@code gcs} / {@code local}，
     * 决定创建或修改 catalog 时允许出现哪些 {@code storageType}：
     * 服务端没有对应实现却接受这份配置，错误会推迟到引擎第一次读写时才暴露，
     * 那时已经很难定位到是 catalog 的存储类型选错了。
     */
    @Getter
    @Setter
    public static class FileIo {

        private String type = "default";
    }

    /**
     * {@code lakehouse.rest.table-metadata.*}：是否把表元数据（schema 与快照）物化到 Paimon 仓库里。
     *
     * <p><b>为什么需要这一层，而不是像 HiveCatalog 那样理所当然地写。</b>Paimon 的
     * REST Catalog 客户端是瘦客户端：{@code RESTCatalog.createTable} 只把 schema
     * POST 给服务端，表目录里的 {@code schema/schema-<n>} 从来不由客户端写。
     * 「拥有表的目录实现」里这一步落在 {@code SchemaManager.createTable}
     * （{@code HiveCatalog.createTableImpl} 就是这么调的），换成 REST 形态之后
     * 这份责任移到了服务端。缺了它，引擎建完表能读 schema，却写不进去——
     * {@code FileStoreCommitImpl} 提交快照前要读 {@code schema/schema-<n>} 取 schemaId，
     * 读不到就抛 {@code Cannot get latest schema for table <表名>}。
     *
     * <p><b>快照是同一件事的另一半。</b>{@code RESTCatalog.supportsVersionManagement()} 恒为
     * {@code true}，于是 {@code CatalogEnvironment.snapshotCommit} 选的是
     * {@code CatalogSnapshotCommit}——把 {@code Snapshot} 对象 POST 给服务端，客户端那份写
     * {@code snapshot/snapshot-<n>} 的代码（{@code RenamingSnapshotCommit}）根本不会被实例化。
     * 服务端不写，仓库里就永远没有快照文件；而这种缺失比 schema 那次更难发现：REST 的读路径
     * 也走服务端（{@code SnapshotLoaderImpl} 查的就是本服务端的数据库），因此用 catalog 读写
     * 一切正常，只有绕开服务端直接看仓库时才会露出来。
     */
    @Getter
    @Setter
    public static class TableMetadata {

        /**
         * 是否在建表 / 改表 / 回滚 schema 时写 {@code schema/schema-<n>}，
         * 以及在提交快照 / 回滚快照时写 {@code snapshot/snapshot-<n>}。
         *
         * <p>默认开启：不写这些文件，建出来的表就是「只可读、不可写」的半成品，
         * 而调用方从接口的成功响应上看不出任何异常——这是本项目里最容易
         * 被误判成「引擎坏了」的一种状态。关掉它只对一种部署有意义：
         * 服务端刻意不持有仓库写权限，表的物理目录由另一条链路（例如先在
         * 文件系统目录里建好表再 {@code register} 进来）负责。
         */
        private boolean enabled = true;

        /**
         * 物化失败时是否让请求失败。
         *
         * <p>默认 {@code false}（尽力而为）：服务端写仓库需要三样东西同时到位——
         * 对应的 FileIO 实现（{@code file://} 内置，{@code s3://} 等由部署方放进
         * Paimon 插件目录）、能访问该仓库的凭据、以及到该端点的网络。任缺一项，
         * 严格失败会让**建表整体不可用**：那是一个比「表暂时写不进去」
         * 严重得多的回归，而且影响面扩到了只用元数据的调用方。
         *
         * <p>置 {@code true} 时写入失败以 500 返回，事务回滚，catalog 里不会留下
         * 半成品表。适合「宁可建不出表，也不要有表写着写着写不进去」的部署；
         * 也是把这类配置问题从「一条 WARN 日志」变成「构建/验收直接红」的开关。
         */
        private boolean failOnError = false;

        /**
         * {@code DROP TABLE} 时是否连表目录一起删除。
         *
         * <p>默认 {@code false}：删数据不可逆，而本项配置的默认值就是
         * 「谁都没看过这份文档时会发生什么」的那一个，因此不给它一个会删数据的默认。
         *
         * <p>关着的代价要说清楚：Paimon 的 schema 文件留在原处，于是
         * 「删表 → 同名重建」会撞上 {@code Schema in filesystem exists, creation
         * is not allowed.}（这句话来自 {@code SchemaManager.createTable}）。
         * 处理方式二选一：把这一项打开，或手工清掉旧表目录。
         *
         * <p>开着时只删**非外部表**的目录：{@code register} 进来的表（
         * {@code external=true}）位置不归本服务端所有，删它等于删别人的数据。
         * 这一点与 Paimon 自己的 {@code HiveCatalog.dropTable} 一致。
         */
        private boolean purgeOnDrop = false;
    }

    /**
     * {@code lakehouse.rest.file-browser.*}：控制台「Catalog 详情 → 文件列表」能看多深。
     *
     * <p><b>为什么这件事需要一组配置。</b>这个页面列出的是 catalog 仓库里的物理目录，
     * 而不是契约里那层「库 / 表」抽象——它比其余只读端点更靠近数据：
     * 能看到某个表目录里有哪些文件、什么时候写的、有没有临时文件残留。
     * 排查「表建出来了却写不进去」这类问题时正是要看这些，所以默认开着；
     * 但它属于运维面而不是数据面的能力（本工程其余端点读的都是元数据），
     * 因此留一个开关和一条上限，让不想要它的部署能整体关掉，也不至于
     * 被一个几十万文件的目录拖住。
     */
    @Getter
    @Setter
    public static class FileBrowser {

        /**
         * 是否开放 {@code GET /api/console/v1/catalogs/{catalogName}/files}。
         *
         * <p>默认开启。关掉时该端点回 501，并把这一项配置名写在错误里——
         * 否则现象是「页面上那个页签永远是错误」，看不出是部署刻意关的。
         *
         * <p>它只能挡「控制台看目录」这一件事：仓库本身仍由引擎与服务端读写，
         * 关掉它不改变任何数据面行为。
         */
        private boolean enabled = true;

        /**
         * 单次列出的条目上限。
         *
         * <p>一个仓库根下可能有成千上万个库目录，分层看还好，但对象存储的
         * {@code listStatus} 是一次前缀列举，全量拉回来再截断的代价落在服务端。
         * 超过上限时**返回前 N 条并标记 {@code truncated}**，而不是报错：
         * 列表页的价值在「能下钻」，为了完整性把它整个拒掉反而让人看不到任何东西。
         * 排序是目录优先、名称升序，因此被截掉的总是尾部——用户至少能看到所有目录。
         */
        private int maxEntries = 1000;
    }

    /**
     * {@code lakehouse.rest.storage-probe.*}：catalog 表单「保存前自检」的边界。
     *
     * <p><b>它为什么需要一组自己的配置。</b>{@code POST /api/console/v1/catalog-storage-probe}
     * 是整个控制台里唯一「按用户给的地址真的发一次请求」的端点：正文里带仓库路径与
     * 待保存的凭据，服务端拿它去 {@code HEAD} 一个对象、或者写一个 0 字节对象再删掉。
     * 这个能力没法和其余只读端点用同一套假设——它们读的都是本服务端已经存下来的东西，
     * 而这里处理的是「还没保存、也不会保存」的输入。所以边界要显式写出来、可被部署调整。
     *
     * <p>与 SSRF 的关系：{@code file://} 与无 scheme 的路径在服务层被**硬拒**，不是开关；
     * 这条不做成配置项，是因为它没有任何正当用途——一个「本机某个路径是否存在」的
     * 预言机，能拿来试探服务账号可读的文件。剩下的调整空间只有限频。
     */
    @Getter
    @Setter
    public static class StorageProbe {

        /**
         * 每个主体每分钟最多探测几次（默认 6）。
         *
         * <p><b>失败也计数。</b>不限频的话这个端点就是一个免费的凭据试错器
         * （拿一批 AK/SK 挨个试，看谁不 403）+ 内网端口扫描器（看哪些地址回
         * {@code UNREACHABLE} 之外的东西）。6 这个数来自实际用法：一张表单最多试
         * 两三次就定了，留三倍余量给「边填边试」。
         *
         * <p>窗口是滑动的 60 秒，按主体名分桶——按 IP 分桶在多实例和反向代理下
         * 分不清谁是谁，而这里挡的是「一个已登录的人反复试」，主体名正是那个粒度。
         */
        private int perMinute = 6;
    }

    /**
     * {@code lakehouse.rest.optimization.*}：优化调度器的**节拍**。
     *
     * <p><b>这里只剩三个键，其余全部搬到了「每个 catalog 的执行集群」里。</b>
     * 上一版有十六个键：命名空间、镜像、拉取策略、apiserver、service account、
     * 令牌文件、CA 文件、回调地址、catalog 令牌、单批表数、并发数、超时、提交方式……
     * 它们共同的假设是「整个服务端只有一个执行集群、而且那个集群就是服务端自己所在的集群」。
     * 一旦不同 catalog 要提交到不同集群，这些键**没有一个是全局的**：
     * 镜像跟着集群与表格式走，回调地址在集群内与集群外不同，配额是给某台集群留的余量。
     * 把它们留在 yml 里只会得到一个「配置写着 A、实际跑在 B」的分裂状态。
     *
     * <p>搬走的是：
     * <ul>
     *   <li>{@code namespace} / {@code image} / {@code image-pull-policy} /
     *       {@code service-account} / {@code api-server} → 执行集群的「运行位置」与「连接」；</li>
     *   <li>{@code token-file} / {@code ca-file} → 整个概念消失：自建集群用 kubeconfig
     *       （CA 是内容而不是路径），EKS / AKS 用令牌，需要的是 AK·SK 或 client secret，
     *       都不是「服务端本机上的某个文件」；</li>
     *   <li>{@code management-url} → 执行集群的 {@code callbackBaseUrl}（反向可达的地址，
     *       逐集群不同）；</li>
     *   <li>{@code catalog-token} → 执行集群的 {@code catalogToken}（同样逐集群，
     *       跨云时它必须有值）；</li>
     *   <li>{@code max-tables-per-job} / {@code max-concurrent-jobs} / {@code job-timeout}
     *       → 执行集群的批次策略（配额是给某台集群留的）；</li>
     *   <li>{@code submitter} → 提交方式只剩一种（fabric8 打到目标集群），
     *       {@code log} 那种「静默吞掉所有批次」的实现已删除。</li>
     * </ul>
     * 留下来的三个键是**纯节拍**，与部署形态无关：它们描述的是「调度器多久看一次世界」，
     * 而不是「任务跑到哪里」。
     *
     * <p><b>调度节拍的两个键由 {@code @Scheduled} 直接读</b>（{@code scan-interval}、
     * {@code dispatch-interval}）：注解的占位符在 bean 绑定之前解析，注入不了这个对象，
     * 因此这两个键在注解表达式与这里各出现一次，改的时候两处都要动（都写了同样的默认值，
     * 漂移表现为「配置写了但节拍没变」）。
     */
    @Getter
    @Setter
    public static class Optimization {

        /**
         * 是否启用调度节拍（扫描到期策略、提交批次、跟踪运行状态）。
         *
         * <p>关掉只停调度，不影响手动「立即优化」与状态查询——用于「优化任务由外部系统
         * 触发出批」的部署，以及需要确定性行为的测试。
         */
        private boolean schedulerEnabled = true;

        /**
         * 扫描节拍：多久检查一次「有策略到期了、有批次等着提交/跟踪」。
         *
         * <p>分钟级即可：真正的执行节奏由每个表的调度间隔与三项动作各自的执行间隔决定，
         * 这个值只影响「到期后多久被看见」。调快它不会让优化跑得更勤，只会让空转查询变多。
         */
        @NotNull
        private Duration scanInterval = Duration.ofMinutes(1);

        /**
         * 提交与跟踪节拍。
         *
         * <p>比扫描节拍快：提交要趁热（批次落库后立刻发出去），跟踪决定用户看到「运行中」
         * 到什么精度。默认 10 秒对 k8s API 与数据库都是轻负载。
         */
        @NotNull
        private Duration dispatchInterval = Duration.ofSeconds(10);
    }

    /**
     * {@code lakehouse.rest.lineage.*}：Spark SQL 血缘（设计见 {@code docs/lineage-design.md}）。
     *
     * <p><b>默认开。</b>「没有客户端埋点就没有数据」这条不变——服务端开着也不会自己长出图来，
     * 真正决定有没有数据的是 Spark 侧的 {@code spark.lakehouse.rest.lineage.enabled}。
     * 因此服务端缺省取开，含义是「谁按规范上报都收」，而不是「已经开始采集」。
     *
     * <p>代价要提前知道：边表<b>不按时间清</b>（§7.5），一旦开始写就只剩「删表时级联删边」
     * 一条回收路径，因此准备接采集器的部署要先把量级跑准。不接采集器的部署显式关掉
     * （{@code --lakehouse.rest.lineage.enabled=false}），上报会拿到 403。
     * 读端点任何情况下都不受这个开关限制——关闭只影响写入，已有的边仍然能查
     * （关掉采集的部署不该连带看不见历史图）。
     */
    @Getter
    @Setter
    public static class Lineage {

        /**
         * 是否接受 {@code POST /api/management/v1/lineage/runs}。<b>默认 true。</b>
         *
         * <p>它只决定「上报告要不要收」，不决定「有没有图」：没有 Spark 侧埋点就没有数据。
         * 关掉之后上报拿 403，读端点照常——图是只读的，关掉读只会让「开关忘了打开」
         * 表现为「页签一直空着」，而空态无法区分这两种情况。
         */
        private boolean enabled = true;

        /**
         * {@code lineage_run} 的保留天数。
         *
         * <p>只清 run，不清边：边是「现在谁依赖谁」的状态，它是图本身。
         * 保留期到了之后「历史上某次执行用了什么列映射」查不到，但
         * 「最近一次是谁写的」仍在边表的 {@code last_seen} 上（§7.1 已声明这个代价）。
         */
        private int runRetentionDays = 90;

        /** 单次上报最多几条 run。超了 400，不静默截断——客户端该自己切批。 */
        private int maxRunsPerBatch = 50;

        /**
         * 单个目标表的列级边上限。超了服务端降级为「只有表级」并给 run 标
         * {@code truncated}（§6.2）：宽表 1000 列 × 5 张源表能到 5000 条边，
         * 一次请求写入它的代价远大于图上的收益。
         */
        private int maxColumnsPerTarget = 2000;

        /** 图遍历深度上限（{@code depth} 参数超过它时 400，不静默截断）。 */
        private int maxDepth = 5;

        /** 单次图查询返回节点数上限（硬上限 2000，见 {@code LineageService}）。 */
        private int maxNodes = 500;

        /**
         * 过期 run 的清理节拍（cron）。
         *
         * <p>与优化调度同一个理由，这个键在 {@code @Scheduled} 的占位符里也出现一次：
         * 注解的占位符在 bean 绑定之前解析，注入不了本对象。改的时候两处都要动
         * （两处默认值相同，漂移的表现是「配置写了但节拍没变」）。
         */
        private String cleanupCron = "0 30 3 * * *";
    }
}
