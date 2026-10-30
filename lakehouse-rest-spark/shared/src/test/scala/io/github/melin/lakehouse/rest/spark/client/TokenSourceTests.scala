package io.github.melin.lakehouse.rest.spark.client

import io.github.melin.lakehouse.rest.engine.auth.{EngineTokenSources, TokenSource}
import io.github.melin.lakehouse.rest.spark.support.{RecordedRequest, StubManagementServer}
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.{BeforeEach, Test}

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * 治理通道的令牌续期（P0-4）。
 *
 * <p>被测的是**两个容易写错、写错了也不会立刻报错**的地方：
 *
 * <ul>
 *   <li>每次请求都重新取票（而不是构造时定死一个字符串）——否则续期发生在别处、
 *       发出去的还是旧票；
 *   <li>401 只重放一次，且只在「真的换到了另一张票」时重放——否则一次 401 会变成
 *       一串重复请求，把「服务端持续拒绝」放大成超时风暴。
 * </ul>
 *
 * <p>续期时序（窗口、single-flight、退避）的用例在
 * {@code lakehouse-rest-engine-auth} 的 `RefreshingAuthProviderTests` 里：
 * 那些规则与 Spark 无关，不该在这里再测一遍。
 */
object TokenSourceTests {

  /** 造一张 JWT 形状的票：只需要第二段（载荷），签名不验。 */
  def jwt(subject: String, expiresAtSeconds: Long): String = {
    val encoder = Base64.getUrlEncoder.withoutPadding()
    val header = encoder.encodeToString("""{"alg":"HS256"}""".getBytes(StandardCharsets.UTF_8))
    val payload = encoder.encodeToString(
      s"""{"sub":"$subject","iat":${expiresAtSeconds - 60},"exp":$expiresAtSeconds}"""
        .getBytes(StandardCharsets.UTF_8))
    s"$header.$payload.signature"
  }
}

class TokenSourceTests {

  private var stub: StubManagementServer = _

  @BeforeEach
  def startStub(): Unit = {
    stub = StubManagementServer.start()
  }

  /** 每个用例自己起一个桩（端口不同 → 进程级票缓存不会串味），用完就关。 */
  private def withStub(body: StubManagementServer => Unit): Unit = {
    try body(stub)
    finally stub.stop()
  }

  private def client(tokens: TokenSource): ManagementApiClient =
    ManagementApiClient.withTokenSource(stub.baseUrl, tokens, null, Duration.ofSeconds(5))

  // ------------------------------------------------------------------ 固定票

  @Test
  def fixedTokenSendsExactlyTheSameHeaderAsBefore(): Unit = withStub { _ =>
    stub.responder = (_: RecordedRequest) => (200, """{"catalogs":[]}""")

    client(TokenSource.fixed("abc123")).listCatalogs()

    assertEquals("Bearer abc123", stub.last.authorization,
      "固定票的头部必须与改造前逐字节相同")
  }

  @Test
  def noTokenSendsNoAuthorizationHeaderAtAll(): Unit = withStub { _ =>
    stub.responder = (_: RecordedRequest) => (200, """{"catalogs":[]}""")

    client(TokenSource.fixed(null)).listCatalogs()

    assertNull(stub.last.authorization,
      "未配置令牌时不能发空 Authorization——服务端会把空串当成主体名")
  }

  @Test
  def fixedTokenIsNotReplayedAfterA401(): Unit = withStub { _ =>
    stub.responder = (_: RecordedRequest) => (401, """{"message":"nope"}""")

    val error = assertThrows(classOf[ManagementApiException],
      () => client(TokenSource.fixed("abc")).listCatalogs())

    assertEquals(401, error.status())
    assertEquals(1, stub.requests.size, "固定票换不出新票，重放只是多打一次注定失败的请求")
  }

  // ------------------------------------------------------------------ 续期

  @Test
  def everyRequestAsksTheSourceForTheCurrentToken(): Unit = withStub { _ =>
    stub.responder = (_: RecordedRequest) => (200, """{"catalogs":[]}""")
    val tokens = new ScriptedTokenSource("first", Some("second"))
    val api = client(tokens)

    api.listCatalogs()
    assertEquals("Bearer first", stub.last.authorization)

    tokens.current = "second"
    api.listCatalogs()
    assertEquals("Bearer second", stub.last.authorization,
      "票变了之后必须用新票，否则续期发生在别处、发出去的还是旧票")
  }

  @Test
  def replaysOnceWithTheRefreshedTokenAfterA401(): Unit = withStub { _ =>
    // 第一次 401，换票后第二次 200：服务端在票过期那一刻的典型表现
    stub.responder = (request: RecordedRequest) =>
      if (request.authorization == "Bearer second") (200, """{"catalogs":[]}""")
      else (401, """{"message":"expired"}""")

    val tokens = new ScriptedTokenSource("first", Some("second"))
    val catalogs = client(tokens).listCatalogs()

    assertEquals(0, catalogs.size())
    assertEquals(2, stub.requests.size, "401 之后应该用新票重放一次")
    assertEquals("Bearer first", stub.requests.head.authorization)
    assertEquals("Bearer second", stub.requests.last.authorization)
    assertEquals(1, tokens.invalidations.get())
  }

  @Test
  def aSecond401IsReturnedAsIsWithoutAFurtherReplay(): Unit = withStub { _ =>
    stub.responder = (_: RecordedRequest) => (401, """{"message":"still expired"}""")
    val tokens = new ScriptedTokenSource("first", Some("second"))

    val error = assertThrows(classOf[ManagementApiException],
      () => client(tokens).listCatalogs())

    assertEquals(401, error.status())
    assertEquals(2, stub.requests.size,
      "只重放一次：把 401 变成重试风暴比 401 本身更糟")
    assertEquals(1, tokens.invalidations.get())
  }

  @Test
  def noReplayWhenTheSourceCouldNotProduceANewToken(): Unit = withStub { _ =>
    stub.responder = (_: RecordedRequest) => (401, """{"message":"expired"}""")
    // 换票失败（服务端不可达 / 凭据不对）时 token() 仍返回旧票，invalidate() 返回 false
    val tokens = new ScriptedTokenSource("first", None)

    assertThrows(classOf[ManagementApiException], () => client(tokens).listCatalogs())

    assertEquals(1, stub.requests.size, "没换到新票就不该重放")
    assertEquals(1, tokens.invalidations.get(), "但「作废并尝试换票」本身要试一次")
  }

  // ------------------------------------------------------------------ 端到端：真的去打令牌端点

  @Test
  def refreshesThroughTheTokenEndpointWhenTheWindowIsReached(): Unit = withStub { _ =>
    val nowSeconds = System.currentTimeMillis() / 1000
    val issued = TokenSourceTests.jwt("alice", nowSeconds + 3600)
    // 起始票只剩 5 秒（iat 在 55 秒前，于是 TTL 判为 60 秒），窗口配成 9 秒
    // → 此刻已在窗口内（到期前 9 秒 = 4 秒之前），第一次取票就会去换
    val start = TokenSourceTests.jwt("alice", nowSeconds + 5)

    stub.responder = (request: RecordedRequest) =>
      if (request.path == "/v1/oauth/tokens") {
        (200, s"""{"access_token":"$issued","token_type":"bearer","expires_in":3600}""")
      } else {
        (200, """{"catalogs":[]}""")
      }

    val endpoint = stub.baseUrl.replace("/api/management/v1", "/v1/oauth/tokens")
    val source = EngineTokenSources.create(endpoint, start, null, null, Duration.ofSeconds(9))
    client(source).listCatalogs()

    val tokenCalls = stub.requests.filter(_.path == "/v1/oauth/tokens")
    assertEquals(1, tokenCalls.size, "进窗口时应该去换一次票")
    // 换票请求自身不能带 Authorization（它是靠请求体里的 subject_token 认证的），
    // 带上旧票会让服务端把两张票混在一起看
    assertNull(tokenCalls.head.authorization)
    assertTrue(tokenCalls.head.body.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange"),
      "应该发 RFC 8693 的 token-exchange，实际请求体：" + tokenCalls.head.body)
    assertEquals("Bearer " + issued, stub.last.authorization, "换到的票要真的被下一次请求用上")
  }

  /** 可编排的票来源：手动控制「换票能换到什么」。 */
  private final class ScriptedTokenSource(@volatile var current: String,
                                          refreshTo: Option[String]) extends TokenSource {

    val invalidations = new AtomicInteger(0)

    override def token(): String = current

    override def invalidate(): Boolean = {
      invalidations.incrementAndGet()
      refreshTo match {
        case Some(next) =>
          current = next
          true
        case None => false
      }
    }
  }
}
