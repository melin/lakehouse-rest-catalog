package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** {@code test} profile 把数据源换成内存 H2（见 {@code src/test/resources/application-test.yml}）。 */
@SpringBootTest
@ActiveProfiles("test")
class PaimonRestServerApplicationTests {

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

}
