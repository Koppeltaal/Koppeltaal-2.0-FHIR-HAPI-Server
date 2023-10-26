package ca.uhn.fhir.jpa.starter.koppeltaal.provider;

import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.annotation.*;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.*;
import org.hl7.fhir.r4.model.ActivityDefinition;
import org.hl7.fhir.r4.model.Task;
import org.springframework.stereotype.Component;

@Component
public class CustomTaskProvider {

  private final IFhirResourceDao<Task> taskDao;
  private final IFhirResourceDao<ActivityDefinition> activityDefinitionDao;

  public CustomTaskProvider(IFhirResourceDao<Task> taskDao, IFhirResourceDao<ActivityDefinition> activityDefinitionDao) {
    this.taskDao = taskDao;
    this.activityDefinitionDao = activityDefinitionDao;
  }

  @Search(queryName = "get-tasks-by-activityDefinitionPublisherId", type = Task.class)
  public IBundleProvider searchTasksByActivityDefinitionPublisherId(
    @RequiredParam(name = "publisherId") String publisherId,
    @OptionalParam(name = "_lastUpdated") DateParam lastUpdated,
    @ResourceParam RequestDetails requestDetails
  ) {
    return getTasksByActivityDefinitionPublisherId(publisherId, lastUpdated, requestDetails);
  }

  @Operation(name = "get-tasks-by-activityDefinitionPublisherId", idempotent = true, type = Task.class)
  public IBundleProvider getTasksByActivityDefinitionPublisherId(
    @OperationParam(name = "publisherId") String publisherId,
    @OperationParam(name = "_lastUpdated") DateParam lastUpdated,
    @ResourceParam RequestDetails requestDetails
  ) {

    IBundleProvider activityDefinitionBundle = activityDefinitionDao.search(new SearchParameterMap("publisherId", new TokenParam(publisherId)), requestDetails);

    ReferenceOrListParam orList = new ReferenceOrListParam();

    activityDefinitionBundle.getAllResources().stream()
      .map(activityDefinition -> ((ActivityDefinition) activityDefinition).getUrl())
      .forEach(adUrl -> orList.add(new ReferenceParam(adUrl)));

    if(orList.size() == 0) return null;  // Return null or an empty bundle if no matches

    // Use the OR query in the search parameter map
    SearchParameterMap paramMap = new SearchParameterMap();
    paramMap.add("instantiates-canonical", orList);

    if (lastUpdated != null) {
      paramMap.setLastUpdated(new DateRangeParam(lastUpdated, null));
    }

    // Search for matching tasks
    return taskDao.search(paramMap, requestDetails);
  }

}
