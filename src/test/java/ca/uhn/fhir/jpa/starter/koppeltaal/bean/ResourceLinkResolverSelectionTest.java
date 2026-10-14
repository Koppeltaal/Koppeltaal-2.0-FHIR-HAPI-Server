package ca.uhn.fhir.jpa.starter.koppeltaal.bean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ca.uhn.fhir.jpa.searchparam.extractor.IResourceLinkResolver;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.BeanOverrideConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

class ResourceLinkResolverSelectionTest {

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void theOverrideWinsRegardlessOfHapisPrimaryBeanRegistrationOrder(boolean hapiFirst) {
		var beans = new DefaultListableBeanFactory();
		var hapi = mock(IResourceLinkResolver.class);
		var override = mock(IResourceLinkResolver.class);
		var originalDefinition = new RootBeanDefinition(IResourceLinkResolver.class, () -> hapi);
		originalDefinition.setPrimary(true);
		var overrideDefinition = new RootBeanDefinition(IResourceLinkResolver.class, () -> override);
		overrideDefinition.setPrimary(true);
		if (hapiFirst) beans.registerBeanDefinition("daoResourceLinkResolver", originalDefinition);
		beans.registerBeanDefinition("koppeltaalDaoResourceLinkResolver", overrideDefinition);
		if (!hapiFirst) beans.registerBeanDefinition("daoResourceLinkResolver", originalDefinition);

		BeanOverrideConfig.selectKoppeltaalResourceLinkResolver().postProcessBeanFactory(beans);

		assertThat(beans.getBean(IResourceLinkResolver.class)).isSameAs(override);
	}
}
