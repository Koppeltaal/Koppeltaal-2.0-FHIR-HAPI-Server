package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.param.UriParam;
import ca.uhn.fhir.rest.server.interceptor.InterceptorAdapter;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Component
public class TaskSubscriptionPublisherIdInterceptor extends InterceptorAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(TaskSubscriptionPublisherIdInterceptor.class);
  private final IFhirResourceDao<ActivityDefinition> activityDefinitionDao;
  private final IFhirResourceDao<Subscription> subscriptionDao;

  private final RestTemplate restTemplate = new RestTemplate();


  public TaskSubscriptionPublisherIdInterceptor(IFhirResourceDao<ActivityDefinition> activityDefinitionDao, IFhirResourceDao<Subscription> subscriptionDao) {
    this.activityDefinitionDao = activityDefinitionDao;
    this.subscriptionDao = subscriptionDao;
  }

  @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
  public void resourceCreated(RequestDetails theRequestDetails, IBaseResource theResource) {
    if (theResource instanceof Task) {
      handleTaskChange((Task) theResource, theRequestDetails);
    }
  }

  @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
  public void resourceUpdated(RequestDetails theRequestDetails, IBaseResource theOldResource, IBaseResource theNewResource) {
    if (theNewResource instanceof Task) {
      handleTaskChange((Task) theNewResource, theRequestDetails);
    }
  }

  private void handleTaskChange(Task theTask, RequestDetails theRequestDetails) {
    try {
      String taskCanonical = theTask.getInstantiatesCanonical();

      ActivityDefinition activityDefinition = getActivityDefinitionByTask(taskCanonical, theRequestDetails);
      if (activityDefinition == null) return; // No ActivityDefinition found for the given canonical URL - strange but abort

      // 3. Extract the publisherId from the ActivityDefinition
      List<Extension> extensionsByUrl = activityDefinition.getExtensionsByUrl("http://koppeltaal.nl/fhir/StructureDefinition/KT2PublisherId");
      if(extensionsByUrl.isEmpty()) return; //no publisherId set, irrelevant for this interceptor

      String publisherId = extensionsByUrl.get(0).getValue().toString();

      // 4. Use the publisherId to search for matching active Subscriptions
      SearchParameterMap paramMap = new SearchParameterMap();
      paramMap.add(Subscription.SP_CRITERIA, new StringParam("Task?instantiates-canonical.publisherId=" + publisherId));
      paramMap.add(Subscription.SP_STATUS, new TokenParam(Subscription.SubscriptionStatus.ACTIVE.toCode()));
      IBundleProvider subscriptions = subscriptionDao.search(paramMap, theRequestDetails);

      subscriptions.getAllResources().forEach(subscription ->
        //TODO: Re-use FHIR logic, retrying channels etc
        submitNotification((Subscription) subscription, publisherId)
      );
    } catch (Exception e) {
      LOG.error("Failed to execute logic in TaskSubscriptionPublisherIdInterceptor, swallowing exception as resource " +
        "creation/updating should not be prevented by a notification exception", e);
    }
  }

  @Nullable
  private ActivityDefinition getActivityDefinitionByTask(String taskCanonical, RequestDetails theRequestDetails) {
    SearchParameterMap paramMap = new SearchParameterMap();
    paramMap.add("url", new UriParam(taskCanonical));

    IBundleProvider activityDefinitionBundle = activityDefinitionDao.search(paramMap, theRequestDetails);

    // Retrieve the first matching ActivityDefinition, if any
    return activityDefinitionBundle.isEmpty() ? null : (ActivityDefinition) activityDefinitionBundle.getResources(0, 1).get(0);
  }

  private void submitNotification(Subscription subscription, String publisherId) {
    // Extract headers from the Subscription
    HttpHeaders headers = new HttpHeaders();
    for (StringType header : subscription.getChannel().getHeader()) {
      String[] headerParts = header.getValue().split(":");
      if (headerParts.length == 2) {
        headers.add(headerParts[0].trim(), headerParts[1].trim());
      }
    }

    HttpEntity<String> request = new HttpEntity<>(null, headers);
    ResponseEntity<Void> response = restTemplate.exchange(subscription.getChannel().getEndpoint(), HttpMethod.POST, request, Void.class);

    // Check the response code
    HttpStatus statusCode = response.getStatusCode();
    if (statusCode.is2xxSuccessful()) {
      LOG.info("Successfully sent a notification to [{}] as they subscribed to Task changes for all ActivityDefinitions with publisherId [{}]. Got response code [{}] from the webhook endpoint.", subscription.getChannel().getEndpoint(), publisherId, statusCode.value());
      return;
    }

    LOG.warn("Failed to send a notification to [{}]. They subscribed to Task changes for all ActivityDefinitions with publisherId [{}]. Got response code [{}] and cause [{}] from the webhook endpoint.", subscription.getChannel().getEndpoint(), publisherId, statusCode.value(), statusCode.getReasonPhrase());
  }
}
