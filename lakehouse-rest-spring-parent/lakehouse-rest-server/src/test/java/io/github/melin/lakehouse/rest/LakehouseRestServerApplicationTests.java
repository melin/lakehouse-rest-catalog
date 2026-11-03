package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** {@code test} profile 把数据源换成内存 H2（见 {@code src/test/resources/application-test.yml}）。 */
@SpringBootTest
@ActiveProfiles("test")
class LakehouseRestServerApplicationTests {

	@Autowired
	private RestServerProperties properties;

	@Test
	void contextLoads() {
	}

	/**
	 * 两个开关**绑定后的生效值**都是开。
	 *
	 * <p>与 {@code LineageDisabledTests#codeDefaultIsOn} /
	 * {@code DataPolicyDisabledTests#codeDefaultIsOn} 有意重复，但读的东西不同：那两条
	 * {@code new} 一个空对象，验的是**纯代码默认**；这条读 Spring 绑定后的值，验的是
	 * <b>代码默认 + application.yml 合起来的结果</b>。
	 *
	 * <p>两个层次都要，因为这套开关出过的事就是「代码默认 true、yml 写着 false」——
	 * 两边都写时行为用例照样绿（看的是生效值），只验代码默认又拦不住 yml 写反。
	 * 只有把「生效值」也钉住，改任意一处才会立刻变红。
	 */
	@Test
	void lineageAndPolicySwitchesAreOnAtStartup() {
		assertTrue(properties.getLineage().isEnabled(),
				"缺省启动时血缘上报应当是开的（application.yml 与 RestServerProperties 要一起是 true）");
		assertTrue(properties.getPolicy().isEnabled(),
				"缺省启动时策略写入口应当是开的（application.yml 与 RestServerProperties 要一起是 true）");
	}

	/**
	 * 三家签发器**绑定后的生效值**都在（代码默认 + application.yml 合起来的结果）。
	 *
	 * <p>与 {@code ServerSideCredentialTests#codeDefaultEnablesTheThreeProviders} 互补：
	 * 那条 {@code new} 一个空对象验纯代码默认，这条验「yml 没把它写回去」。
	 * 缺省值改变过一次（空 → 三家），而它失效的方式是静默的：填了 {@code roleArn}
	 * 却一张票都没签过，引擎拿到的仍是库里的永久密钥——控制台标红、作业照常跑。
	 */
	@Test
	void credentialProvidersAreOnAtStartup() {
		assertEquals(List.of(
				VendedStorageCredential.PROVIDER_S3_TOKEN,
				VendedStorageCredential.PROVIDER_AZURE_SAS,
				VendedStorageCredential.PROVIDER_OSS_STS),
			properties.getCredential().getProviders(),
			"缺省启动时三家签发器都该在（application.yml 与 RestServerProperties 要一起是这三家）");
	}

}
