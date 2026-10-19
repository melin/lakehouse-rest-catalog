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

    /** {@code lakehouse.rest.optimization.*}：表存储优化（见 {@link Optimization}）。 */
    private final Optimization optimization = new Optimization();

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
         * <p>管理规格的权限 enum 里没有「管理主体与服务级角色」这一层权限，
         * 说明 Polaris 把这类操作交给服务管理员而非权限判定，因此这里用名单表达。
         * 名单内的主体可以管理 principal、principal role，并可在任意 catalog 上操作角色与授权。
         */
        private List<String> serviceAdmins = new ArrayList<>(List.of("root"));

        /**
         * 启动时预置的引导主体名。
         *
         * <p>与 {@link #serviceAdmins} 配合，构成授权体系的自举入口：
         * 没有它，全新部署没有任何主体能创建第一个主体。留空则不预置。
         */
        private String bootstrapPrincipal = "root";

        /** 启动时预置的引导角色链：service_admin → 各 catalog 的 catalog_admin。 */
        private String bootstrapPrincipalRole = "service_admin";
    }

    /** 数据访问令牌（凭证下发）配置。 */
    @Getter
    @Setter
    public static class Credential {
        private long ttlSeconds = 3600;
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
     * {@code lakehouse.rest.storage.*}：服务端访问对象存储所用的凭据与连接参数。
     *
     * <p>键名与 Polaris 1.8.0 配置参考的「Storage & Credentials」一节逐项对应，
     * 只把前缀从 {@code polaris} 换成 {@code lakehouse.rest}，运维从 Polaris 迁过来时
     * 配置可以按名字平移。唯一的结构差异是具名存储：Polaris 写成
     * {@code polaris.storage.aws.<storage>.access-key}，与同层的
     * {@code polaris.storage.aws.access-key} 共用键空间；Spring 的绑定器无法在
     * 一个 {@code aws} 前缀下同时接受具名字段和动态子节点，因此这里下沉一层，
     * 写成 {@code lakehouse.rest.storage.aws.storages.<storage>.access-key}。
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

        private final Aws aws = new Aws();

        private final Gcp gcp = new Gcp();

        /**
         * 华为云 OBS 凭据。
         *
         * <p><b>为什么与 {@code aws} 分开而不是共用。</b>华为 OBS 与阿里云 OSS 都兼容
         * S3 协议，看起来可以当成一类存储吃同一份凭据。但它们的 AK/SK 是独立签发、
         * 独立轮转、独立授权的：挤进同一个键空间后，「给 OBS 换一把钥匙」与
         * 「给生产 S3 换一把钥匙」就没法区分，只能靠命名约定维持，而命名约定不会在
         * 轮转出错时提醒任何人。
         *
         * <p>这一组不是 Polaris 配置面的对应项——Polaris 里没有 OBS 存储类型，
         * 它用 S3 兼容层接入。本工程单列，理由见 {@code ManagementEnums.StorageType}。
         */
        private final CloudCredentials obs = new CloudCredentials();

        /** 阿里云 OSS 凭据，结构与 {@link #obs} 相同。 */
        private final CloudCredentials oss = new CloudCredentials();

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

        /**
         * AWS 凭据。
         *
         * <p>{@code access-key}/{@code secret-key} 是默认凭据，用于未指定具名存储的 S3 catalog；
         * 两者都不配时，凭据来源退化为环境（实例角色、环境变量、配置文件），
         * 与 Polaris 的「默认凭据链」一致。
         */
        @Getter
        @Setter
        public static class Aws {

            private String accessKey;

            private String secretKey;

            /**
             * 具名存储的凭据，键为 {@code StorageConfigInfo.storageName}。
             *
             * <p>Polaris 用它支持「一个服务托管多组 S3 凭据」：catalog 只引用一个名字，
             * 密钥留在服务端配置里，不出现在管理 API 的报文中。这也是**密钥完全不落库**
             * 的唯一途径——另一种做法（{@code storageConfigInfo} 里的静态凭据）
             * 密钥仍然写进 {@code storage_config_json}，只是以
             * {@code lakehouse.rest.storage.credential-secret-key} 加密后的形态落库。
             */
            private Map<String, Keys> storages = new LinkedHashMap<>();
        }

        /** 一组访问密钥。 */
        @Getter
        @Setter
        public static class Keys {

            private String accessKey;

            private String secretKey;

            /**
             * 临时凭据的安全令牌。
             *
             * <p>与 AK/SK 三者必须同时提供——只给令牌不给 AK/SK 不会失败，
             * 只会让引擎拿一份不完整的凭据去签名。华为与阿里都要求临时凭据
             * 「AK + SK + 令牌」成套使用。
             *
             * <p>S3 的具名存储不使用这个字段：{@code AwsStorageConfigInfo} 用
             * {@code stsUnavailable} 表达「不下发密钥」，服务端不再代持临时凭据。
             */
            private String sessionToken;
        }

        /**
         * 一组对象存储凭据：默认凭据 + 具名存储。OBS 与 OSS 共用这个结构。
         *
         * <p>与 {@link Aws} 的差别只有 {@code sessionToken} 一项：华为与阿里的
         * 临时凭据是「AK/SK + 安全令牌」三件套，长期凭据则只有 AK/SK。
         * 没有把它合并进 {@link Aws}，是因为那会给 S3 凭空多出一个不生效的
         * {@code aws.session-token} 配置键——服务端当前的 S3 分支不读它。
         */
        @Getter
        @Setter
        public static class CloudCredentials {

            private String accessKey;

            private String secretKey;

            /** 安全令牌；留空表示使用长期凭据。三项都空则退化为环境凭据链。 */
            private String sessionToken;

            /**
             * 具名存储的凭据，键为 {@code StorageConfigInfo.storageName}。
             *
             * <p>与 {@code aws.storages} 同构，理由相同：让 catalog 只引用一个名字，
             * 密钥留在服务端配置里，不出现在管理 API 的报文中。
             */
            private Map<String, Keys> storages = new LinkedHashMap<>();
        }

        /**
         * GCP 凭据。
         *
         * <p>{@code token} 是访问令牌明文；{@code lifespan} 是它的有效期，
         * 两者都对应 Polaris 的 {@code polaris.storage.gcp.token} 与
         * {@code polaris.storage.gcp.lifespan}（后者默认 {@code null}，即用令牌自带的有效期）。
         */
        @Getter
        @Setter
        public static class Gcp {

            private String token;

            private Duration lifespan;
        }
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
     * {@code lakehouse.rest.optimization.*}：表存储优化。
     *
     * <p>这一组配置决定「优化任务在哪里跑、跑多久算超时、一批塞几张表」，
     * 都属于部署形态而非业务参数——表级参数在策略里，catalog 级默认值在 catalog 属性里
     * （{@code optimization.*}，见 {@code OptimizationOptions}）。
     *
     * <p><b>调度节拍的两个键由 {@code @Scheduled} 直接读</b>（{@code scan-interval}、
     * {@code dispatch-interval}）：注解的占位符在 bean 绑定之前解析，注入不了这个对象，
     * 因此这两个键在注解表达式与这里各出现一次，改的时候两处都要动（都写了同样的默认值，
     * 漂移表现为「配置写了但节拍没变」）。其余键只在运行时通过本对象读取。
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
         * 提交方式：{@code k8s}（默认，创建 k8s Job）或 {@code log}（只记日志，本地调试）。
         */
        private String submitter = "k8s";

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

        /** 单批最多几张表。超过则拆到下一批，避免一个批次跑太久、重试代价过大。 */
        @Min(1)
        private int maxTablesPerJob = 20;

        /** 同时允许几个批次在跑。超出的批次留在 PENDING 排队。 */
        @Min(1)
        private int maxConcurrentJobs = 2;

        /** 批次超时。超过它仍未终结的批次判失败并清理执行侧资源。 */
        @NotNull
        private Duration jobTimeout = Duration.ofHours(2);

        /** 提交到 k8s 的命名空间。 */
        private String namespace = "default";

        /** 优化任务镜像（含优化任务与 Paimon Spark 运行时）。{@code k8s} 提交器必填。 */
        private String image;

        private String imagePullPolicy = "IfNotPresent";

        /** k8s API 地址。集群内默认走 service 域名。 */
        private String apiServer = "https://kubernetes.default.svc";

        /** 运行优化任务的 service account 名；留空则用命名空间的默认账号。 */
        private String serviceAccount = "";

        /** service account 令牌文件（集群内由 kubelet 挂载并轮转）。 */
        private String tokenFile = "/var/run/secrets/kubernetes.io/serviceaccount/token";

        /** 集群 CA 证书文件。读不到时退回 JVM 默认信任库并打 WARN。 */
        private String caFile = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt";

        /**
         * 下发给优化任务的服务端地址，任务用它回调拉明细、上报结果。
         *
         * <p>默认值只对「任务与实例同机」的调试场景成立；容器里必须配成服务端在本集群内的
         * 可解析地址（Service 名，不是 pod 名——驱动可能在别的节点上起）。
         */
        private String managementUrl = "http://127.0.0.1:8080";

        /**
         * 下发给优化任务的 catalog 数据面令牌。
         *
         * <p>批次回调令牌只对优化回调端点有效，不能当 catalog 凭据——任务要访问
         * {@code /v1/{prefix}} 才能拿到 Paimon 表对象，那一步走的是
         * {@code lakehouse.rest.auth.*} 这套认证。两者作用域不同，因此是两个令牌。
         *
         * <p>留空表示目标部署没开认证（本地与测试形态），任务会不带头去连；
         * 开了认证的部署必须配一个属于优化任务专用主体（{@code optimizer}）的令牌，
         * 该主体的权限只需覆盖本批次的表（见 {@code docs/table-optimization.md} §3.5）。
         *
         * <p>与回调令牌一样，它写在容器参数里，能做同样的事：命名空间里能读 pod 定义
         * 的人可以读到它。要收掉这个暴露面需要服务端为每批签一个短期令牌
         * （{@code AccessTokenService} 已具备签发能力），属于后续优化项。
         */
        private String catalogToken = "";
    }
}
