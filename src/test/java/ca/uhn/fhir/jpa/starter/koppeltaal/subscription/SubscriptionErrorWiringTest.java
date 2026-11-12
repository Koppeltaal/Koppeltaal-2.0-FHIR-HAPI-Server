package ca.uhn.fhir.jpa.starter.koppeltaal.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.jpa.starter.Application;
import ca.uhn.fhir.jpa.subscription.channel.subscription.SubscriptionDeliveryHandlerFactory;
import ca.uhn.fhir.jpa.subscription.match.matcher.subscriber.SubscriptionMatchingSubscriber;
import ca.uhn.fhir.jpa.subscription.model.CanonicalSubscriptionChannelType;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.MessageHandler;
import org.springframework.test.util.ReflectionTestUtils;

/** HAPI looks both beans up itself, so only the running application shows that it really uses ours. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {Application.class}, properties = {
		"spring.datasource.url=jdbc:h2:mem:dbsubscriptionerror",
		"hapi.fhir.fhir_version=r4",
		"hapi.fhir.mdm_enabled=false",
		"hapi.fhir.cr_enabled=false",
		"hapi.fhir.subscription.resthook_enabled=true",
		"hapi.fhir.subscription.websocket_enabled=false",
		"spring.main.allow-bean-definition-overriding=true"
})
class SubscriptionErrorWiringTest {

	@Autowired
	private SubscriptionMatchingSubscriber matchingSubscriber;

	@Autowired
	private SubscriptionDeliveryHandlerFactory deliveryHandlerFactory;

	@Test
	void hapisMatchingLoopUsesTheErrorIsolatingMatcher() {
		assertThat(ReflectionTestUtils.getField(matchingSubscriber, "mySubscriptionMatcher"))
				.isInstanceOf(ErrorIsolatingSubscriptionMatcher.class);
	}

	@Test
	void hapiDeliversThroughANewKoppeltaalRestHookSubscriberEachTime() {
		Optional<MessageHandler> first = deliveryHandlerFactory.createDeliveryHandler(CanonicalSubscriptionChannelType.RESTHOOK);
		Optional<MessageHandler> second = deliveryHandlerFactory.createDeliveryHandler(CanonicalSubscriptionChannelType.RESTHOOK);

		assertThat(first).get().isInstanceOf(KoppeltaalRestHookSubscriber.class);
		assertThat(second).get().isNotSameAs(first.get());
	}
}
