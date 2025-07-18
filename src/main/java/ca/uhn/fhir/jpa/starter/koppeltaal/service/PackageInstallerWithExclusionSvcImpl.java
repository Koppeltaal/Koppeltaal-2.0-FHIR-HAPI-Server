/*-
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2023 Smile CDR, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package ca.uhn.fhir.jpa.starter.koppeltaal.service;

import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition;
import ca.uhn.fhir.context.BaseRuntimeElementDefinition;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.dao.data.INpmPackageVersionDao;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.jpa.model.entity.NpmPackageVersionEntity;
import ca.uhn.fhir.jpa.packages.*;
import ca.uhn.fhir.jpa.packages.loader.PackageResourceParsingSvc;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.searchparam.registry.ISearchParamRegistryController;
import ca.uhn.fhir.jpa.searchparam.util.SearchParameterHelper;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.param.UriParam;
import ca.uhn.fhir.util.FhirTerser;
import ca.uhn.fhir.util.SearchParameterUtil;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.Validate;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.npm.IPackageCacheManager;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static ca.uhn.fhir.jpa.packages.util.PackageUtils.DEFAULT_INSTALL_TYPES;
import static org.apache.commons.lang3.StringUtils.defaultString;
import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * <p>
 * <b>** WARNING: Exact copy of the {@link PackageInstallerSvcImpl}</b>
 * </p>
 * <br/>
 * Exact copy is required as the logic that needs to be overridden is inside private methods.
 * The following is customized:
 * <ol>
 *   <li>Certain resources in the package can be excluded</li>
 *   <li>Resources with draft status will be installed (overrides <a href="https://github.com/hapifhir/hapi-fhir/pull/2099" target="_blank">this</a> PR)</li>
 * </ol>
 * @since 5.1.0
 */
@Component
public class PackageInstallerWithExclusionSvcImpl implements IPackageInstallerSvc {

  /* CUSTOMIZATION START */
  private final static List<String> excludedResources = Lists.newArrayList(
    "StructureDefinition/zib-HearingFunction.HearingAid.Product",
    "StructureDefinition/zib-HearingFunction.HearingAid",
    "StructureDefinition/zib-VisualFunction.VisualAid.Product",
    "StructureDefinition/zib-VisualFunction.VisualAid",
    "StructureDefinition/nl-core-HearingFunction.HearingAid.Product",
    "StructureDefinition/nl-core-HearingFunction.HearingAid",
    "StructureDefinition/nl-core-VisualFunction.VisualAid.Product",
    "StructureDefinition/nl-core-VisualFunction.VisualAid",
	"StructureDefinition/zib-Wound.Drain.Product",
	"StructureDefinition/zib-Wound.Drain",
	"StructureDefinition/nl-core-Wound.Drain.Product",
	"StructureDefinition/nl-core-Wound.Drain"
  );
  /* CUSTOMIZATION END */

	private static final Logger ourLog = LoggerFactory.getLogger(PackageInstallerWithExclusionSvcImpl.class);


	boolean enabled = true;
	@Autowired
	private FhirContext myFhirContext;
	@Autowired
	private DaoRegistry myDaoRegistry;
	@Autowired
	private IValidationSupport validationSupport;
	@Autowired
	private IHapiPackageCacheManager myPackageCacheManager;
	@Autowired
	private PlatformTransactionManager myTxManager;
	@Autowired
	private INpmPackageVersionDao myPackageVersionDao;
	@Autowired
	private ISearchParamRegistryController mySearchParamRegistryController;
	@Autowired
	private PartitionSettings myPartitionSettings;
	@Autowired
	private SearchParameterHelper mySearchParameterHelper;
	@Autowired
	private PackageResourceParsingSvc myPackageResourceParsingSvc;

	/**
	 * Constructor
	 */
	public PackageInstallerWithExclusionSvcImpl() {
		super();
	}

	@PostConstruct
	public void initialize() {
		switch (myFhirContext.getVersion().getVersion()) {
			case R5:
			case R4B:
			case R4:
			case DSTU3:
				break;

			case DSTU2:
			case DSTU2_HL7ORG:
			case DSTU2_1:
			default: {
				ourLog.info("IG installation not supported for version: {}", myFhirContext.getVersion().getVersion());
				enabled = false;
			}
		}
	}

    public PackageDeleteOutcomeJson uninstall(PackageInstallationSpec theInstallationSpec) {
        return myPackageCacheManager.uninstallPackage(theInstallationSpec.getName(), theInstallationSpec.getVersion());
    }

	/**
	 * Loads and installs an IG from a file on disk or the Simplifier repo using
	 * the {@link IPackageCacheManager}.
	 * <p>
	 * Installs the IG by persisting instances of the following types of resources:
	 * <p>
	 * - NamingSystem, CodeSystem, ValueSet, StructureDefinition (with snapshots),
	 * ConceptMap, SearchParameter, Subscription
	 * <p>
	 * Creates the resources if non-existent, updates them otherwise.
	 *
	 * @param theInstallationSpec The details about what should be installed
	 */
	@SuppressWarnings("ConstantConditions")
	@Override
	public PackageInstallOutcomeJson install(PackageInstallationSpec theInstallationSpec) throws ImplementationGuideInstallationException {
		PackageInstallOutcomeJson retVal = new PackageInstallOutcomeJson();
		if (enabled) {
			try {

				boolean exists = new TransactionTemplate(myTxManager).execute(tx -> {
					Optional<NpmPackageVersionEntity> existing = myPackageVersionDao.findByPackageIdAndVersion(theInstallationSpec.getName(), theInstallationSpec.getVersion());
					return existing.isPresent();
				});
				if (exists) {
					ourLog.info("Package {}#{} is already installed", theInstallationSpec.getName(), theInstallationSpec.getVersion());
				}

				NpmPackage npmPackage = myPackageCacheManager.installPackage(theInstallationSpec);
				if (npmPackage == null) {
					throw new IOException(Msg.code(1284) + "Package not found");
				}

				retVal.getMessage().addAll(JpaPackageCache.getProcessingMessages(npmPackage));

				if (theInstallationSpec.isFetchDependencies()) {
					/* Custom addition start, skip if installed */
					if(!exists) {
						fetchAndInstallDependencies(npmPackage, theInstallationSpec, retVal);
					}
					/* Custom addition end, skip if installed */
				}

				if (theInstallationSpec.getInstallMode() == PackageInstallationSpec.InstallModeEnum.STORE_AND_INSTALL) {
					install(npmPackage, theInstallationSpec, retVal);

					// If any SearchParameters were installed, let's load them right away
					mySearchParamRegistryController.refreshCacheIfNecessary();
				}

			} catch (IOException e) {
				throw new ImplementationGuideInstallationException(Msg.code(1285) + "Could not load NPM package " + theInstallationSpec.getName() + "#" + theInstallationSpec.getVersion(), e);
			}
		}

		return retVal;
	}

	/**
	 * Installs a package and its dependencies.
	 * <p>
	 * Fails fast if one of its dependencies could not be installed.
	 *
	 * @throws ImplementationGuideInstallationException if installation fails
	 */
	private void install(NpmPackage npmPackage, PackageInstallationSpec theInstallationSpec, PackageInstallOutcomeJson theOutcome) throws ImplementationGuideInstallationException {
		String name = npmPackage.getNpm().get("name").asJsonString().getValue();
		String version = npmPackage.getNpm().get("version").asJsonString().getValue();

		String fhirVersion = npmPackage.fhirVersion();
		String currentFhirVersion = myFhirContext.getVersion().getVersion().getFhirVersionString();
		assertFhirVersionsAreCompatible(fhirVersion, currentFhirVersion);

		List<String> installTypes;
		if (!theInstallationSpec.getInstallResourceTypes().isEmpty()) {
			installTypes = theInstallationSpec.getInstallResourceTypes();
		} else {
			/** CUSTOM ADDITION START */
			// installTypes = DEFAULT_INSTALL_TYPES;
			List<String> defaultInstallTypes = Lists.newArrayList(DEFAULT_INSTALL_TYPES);
			defaultInstallTypes.add("ImplementationGuide");
			installTypes = Collections.unmodifiableList(defaultInstallTypes);
			/** CUSTOM ADDITION END */

		}

		ourLog.info("Installing package: {}#{}", name, version);
		int[] count = new int[installTypes.size()];

		for (int i = 0; i < installTypes.size(); i++) {
			String type = installTypes.get(i);

			Collection<IBaseResource> resources = myPackageResourceParsingSvc.parseResourcesOfType(type, npmPackage);
			count[i] = resources.size();

			for (IBaseResource next : resources) {

        /* CUSTOMIZATION START - Skip problematic nictiz resources */
        if(excludedResources.contains(next.getIdElement().getValueAsString())) {
          ourLog.info("Skipping excluded resource [{}]", next.getIdElement().getValueAsString());
          continue;
        }
        
        /* CUSTOMIZATION START - Skip ImplementationGuide resources to avoid reference validation issues */
        if("ImplementationGuide".equals(myFhirContext.getResourceType(next))) {
          ourLog.info("Skipping ImplementationGuide resource [{}] to avoid reference validation issues", next.getIdElement().getValueAsString());
          continue;
        }
        /* CUSTOMIZATION END */

				try {
					next = isStructureDefinitionWithoutSnapshot(next) ? generateSnapshot(next) : next;
					create(next, theInstallationSpec, theOutcome);
				} catch (Exception e) {
					ourLog.warn("Failed to upload resource of type {} with ID {} - Error: {}", myFhirContext.getResourceType(next), next.getIdElement().getValue(), e.toString());
					throw new ImplementationGuideInstallationException(Msg.code(1286) + String.format("Error installing IG %s#%s: %s", name, version, e), e);
				}

			}

		}
		ourLog.info(String.format("Finished installation of package %s#%s:", name, version));

		for (int i = 0; i < count.length; i++) {
			ourLog.info(String.format("-- Created or updated %s resources of type %s", count[i], installTypes.get(i)));
		}
	}

	private void fetchAndInstallDependencies(NpmPackage npmPackage, PackageInstallationSpec theInstallationSpec, PackageInstallOutcomeJson theOutcome) throws ImplementationGuideInstallationException {
		if (npmPackage.getNpm().has("dependencies")) {
			JsonObject dependenciesElement = npmPackage.getNpm().get("dependencies").asJsonObject();
			for (String id : dependenciesElement.getNames()) {
				String ver = dependenciesElement.getJsonString(id).asString();
				try {
					theOutcome.getMessage().add("Package " + npmPackage.id() + "#" + npmPackage.version() + " depends on package " + id + "#" + ver);

					boolean skip = false;
					for (String next : theInstallationSpec.getDependencyExcludes()) {
						if (id.matches(next)) {
							theOutcome.getMessage().add("Not installing dependency " + id + " because it matches exclude criteria: " + next);
							skip = true;
							break;
						}
					}
					if (skip) {
						continue;
					}

					// resolve in local cache or on packages.fhir.org
					NpmPackage dependency = myPackageCacheManager.loadPackage(id, ver);
					// recursive call to install dependencies of a package before
					// installing the package
					fetchAndInstallDependencies(dependency, theInstallationSpec, theOutcome);

					if (theInstallationSpec.getInstallMode() == PackageInstallationSpec.InstallModeEnum.STORE_AND_INSTALL) {
						install(dependency, theInstallationSpec, theOutcome);
					}

				} catch (IOException e) {
					throw new ImplementationGuideInstallationException(Msg.code(1287) + String.format(
						"Cannot resolve dependency %s#%s", id, ver), e);
				}
			}
		}
	}

	/**
	 * Asserts if package FHIR version is compatible with current FHIR version
	 * by using semantic versioning rules.
	 */
	protected void assertFhirVersionsAreCompatible(String fhirVersion, String currentFhirVersion)
		throws ImplementationGuideInstallationException {

		FhirVersionEnum fhirVersionEnum = FhirVersionEnum.forVersionString(fhirVersion);
		FhirVersionEnum currentFhirVersionEnum = FhirVersionEnum.forVersionString(currentFhirVersion);
		Validate.notNull(fhirVersionEnum, "Invalid FHIR version string: %s", fhirVersion);
		Validate.notNull(currentFhirVersionEnum, "Invalid FHIR version string: %s", currentFhirVersion);
		boolean compatible = fhirVersionEnum.equals(currentFhirVersionEnum);
		if (!compatible && fhirVersion.startsWith("R4") && currentFhirVersion.startsWith("R4")) {
			compatible = true;
		}
		if (!compatible) {
			throw new ImplementationGuideInstallationException(Msg.code(1288) + String.format(
				"Cannot install implementation guide: FHIR versions mismatch (expected <=%s, package uses %s)",
				currentFhirVersion, fhirVersion));
		}
	}

	/**
	 * ============================= Utility methods ===============================
	 */


	private void create(IBaseResource theResource, PackageInstallationSpec theInstallationSpec, PackageInstallOutcomeJson theOutcome) {
		IFhirResourceDao dao = myDaoRegistry.getResourceDao(theResource.getClass());
		SearchParameterMap map = createSearchParameterMapFor(theResource);
		IBundleProvider searchResult = searchResource(dao, map);
		if (validForUpload(theResource)) {
			if (searchResult.isEmpty()) {

				ourLog.info("Creating new resource matching {}", map.toNormalizedQueryString(myFhirContext));
				theOutcome.incrementResourcesInstalled(myFhirContext.getResourceType(theResource));

				IIdType id = theResource.getIdElement();

				if (id.isEmpty()) {
					createResource(dao, theResource);
					ourLog.info("Created resource with new id");
				} else {
					if (id.isIdPartValidLong()) {
						String newIdPart = "npm-" + id.getIdPart();
						id.setParts(id.getBaseUrl(), id.getResourceType(), newIdPart, id.getVersionIdPart());
					}
					updateResource(dao, theResource);
					ourLog.info("Created resource with existing id");
				}
			} else {
				if (theInstallationSpec.isReloadExisting()) {
					ourLog.info("Updating existing resource matching {}", map.toNormalizedQueryString(myFhirContext));
					theResource.setId(searchResult.getResources(0, 1).get(0).getIdElement().toUnqualifiedVersionless());
					DaoMethodOutcome outcome = updateResource(dao, theResource);
					if (!outcome.isNop()) {
						theOutcome.incrementResourcesInstalled(myFhirContext.getResourceType(theResource));
					}
				} else {
					ourLog.info("Skipping update of existing resource matching {}", map.toNormalizedQueryString(myFhirContext));
				}
			}
		}
		else{
			ourLog.warn("Failed to upload resource of type {} with ID {} - Error: Resource failed validation", theResource.fhirType(), theResource.getIdElement().getValue());
		}
	}

	private IBundleProvider searchResource(IFhirResourceDao theDao, SearchParameterMap theMap) {
		if (myPartitionSettings.isPartitioningEnabled()) {
			SystemRequestDetails requestDetails = newSystemRequestDetails();
			return theDao.search(theMap, requestDetails);
		} else {
			return theDao.search(theMap);
		}
	}

	@Nonnull
	private SystemRequestDetails newSystemRequestDetails() {
		return
			new SystemRequestDetails()
				.setRequestPartitionId(RequestPartitionId.defaultPartition());
	}

	private void createResource(IFhirResourceDao theDao, IBaseResource theResource) {
		if (myPartitionSettings.isPartitioningEnabled()) {
			SystemRequestDetails requestDetails = newSystemRequestDetails();
			theDao.create(theResource, requestDetails);
		} else {
			theDao.create(theResource);
		}
	}

	DaoMethodOutcome updateResource(IFhirResourceDao theDao, IBaseResource theResource) {
		if (myPartitionSettings.isPartitioningEnabled()) {
			SystemRequestDetails requestDetails = newSystemRequestDetails();
			return theDao.update(theResource, requestDetails);
		} else {
			return theDao.update(theResource, new SystemRequestDetails());
		}
	}

	boolean validForUpload(IBaseResource theResource) {
		String resourceType = myFhirContext.getResourceType(theResource);
		if ("SearchParameter".equals(resourceType)) {

			String code = SearchParameterUtil.getCode(myFhirContext, theResource);
			if (defaultString(code).startsWith("_")) {
				ourLog.warn("Failed to validate resource of type {} with url {} - Error: Resource code starts with \"_\"", theResource.fhirType(), SearchParameterUtil.getURL(myFhirContext, theResource));
				return false;
			}

			String expression = SearchParameterUtil.getExpression(myFhirContext, theResource);
			if (isBlank(expression)) {
				ourLog.warn("Failed to validate resource of type {} with url {} - Error: Resource expression is blank", theResource.fhirType(), SearchParameterUtil.getURL(myFhirContext, theResource));
				return false;
			}

			if (SearchParameterUtil.getBaseAsStrings(myFhirContext, theResource).isEmpty()) {
				ourLog.warn("Failed to validate resource of type {} with url {} - Error: Resource base is empty", theResource.fhirType(), SearchParameterUtil.getURL(myFhirContext, theResource));
				return false;
			}

		}

		if (!isValidResourceStatusForPackageUpload(theResource)) {
			ourLog.warn("Failed to validate resource of type {} with ID {} - Error: Resource status not accepted value.",
				theResource.fhirType(), theResource.getIdElement().getValue());
			return false;
		}

		return true;
	}

	/**
	 * For resources like {@link org.hl7.fhir.r4.model.Subscription}, {@link org.hl7.fhir.r4.model.DocumentReference},
	 * and {@link org.hl7.fhir.r4.model.Communication}, the status field doesn't necessarily need to be set to 'active'
	 * for that resource to be eligible for upload via packages. For example, all {@link org.hl7.fhir.r4.model.Subscription}
	 * have a status of {@link org.hl7.fhir.r4.model.Subscription.SubscriptionStatus#REQUESTED} when they are originally
	 * inserted into the database, so we accept that value for {@link org.hl7.fhir.r4.model.Subscription} isntead.
	 * Furthermore, {@link org.hl7.fhir.r4.model.DocumentReference} and {@link org.hl7.fhir.r4.model.Communication} can
	 * exist with a wide variety of values for status that include ones such as
	 * {@link org.hl7.fhir.r4.model.Communication.CommunicationStatus#ENTEREDINERROR},
	 * {@link org.hl7.fhir.r4.model.Communication.CommunicationStatus#UNKNOWN},
	 * {@link org.hl7.fhir.r4.model.DocumentReference.ReferredDocumentStatus#ENTEREDINERROR},
	 * {@link org.hl7.fhir.r4.model.DocumentReference.ReferredDocumentStatus#PRELIMINARY}, and others, which while not considered
	 * 'final' values, should still be uploaded for reference.
	 *
	 * @return {@link Boolean#TRUE} if the status value of this resource is acceptable for package upload.
	 */
	private boolean isValidResourceStatusForPackageUpload(IBaseResource theResource) {
		List<IPrimitiveType> statusTypes = myFhirContext.newFhirPath().evaluate(theResource, "status", IPrimitiveType.class);
		// Resource does not have a status field
		if (statusTypes.isEmpty()) return true;
		// Resource has a null status field
		if (statusTypes.get(0).getValue() == null) return false;
		// Resource has a status, and we need to check based on type
    String statusString = statusTypes.get(0).getValueAsString();
    switch (theResource.fhirType()) {
			case "Subscription":
				return (statusString.equals("requested"));
			case "DocumentReference":
			case "Communication":
				return (!statusString.equals("?"));
			default:
        /* CUSTOMIZATION START */
        /* Also allow draft, the ZIBs and nl-core are in draft or unknown */
				return statusString.equals("active") || statusString.equalsIgnoreCase("draft") || statusString.equalsIgnoreCase("unknown");
        /* CUSTOMIZATION END */
		}
	}

	private boolean isStructureDefinitionWithoutSnapshot(IBaseResource r) {
		boolean retVal = false;
		FhirTerser terser = myFhirContext.newTerser();
		if (r.getClass().getSimpleName().equals("StructureDefinition")) {
			Optional<String> kind = terser.getSinglePrimitiveValue(r, "kind");
			if (kind.isPresent() && !(kind.get().equals("logical"))) {
				retVal = terser.getSingleValueOrNull(r, "snapshot") == null;
			}
		}
		return retVal;
	}

	private IBaseResource generateSnapshot(IBaseResource sd) {
		try {
			return validationSupport.generateSnapshot(new ValidationSupportContext(validationSupport), sd, null, null, null);
		} catch (Exception e) {
			throw new ImplementationGuideInstallationException(Msg.code(1290) + String.format(
				"Failure when generating snapshot of StructureDefinition: %s", sd.getIdElement()), e);
		}
	}

	private SearchParameterMap createSearchParameterMapFor(IBaseResource resource) {
		if (resource.getClass().getSimpleName().equals("NamingSystem")) {
			String uniqueId = extractUniqeIdFromNamingSystem(resource);
			return SearchParameterMap.newSynchronous().add("value", new StringParam(uniqueId).setExact(true));
		} else if (resource.getClass().getSimpleName().equals("Subscription")) {
			String id = extractIdFromSubscription(resource);
			return SearchParameterMap.newSynchronous().add("_id", new TokenParam(id));
		} else if (resource.getClass().getSimpleName().equals("SearchParameter")) {
			return buildSearchParameterMapForSearchParameter(resource);
		} else if (resourceHasUrlElement(resource)) {
			String url = extractUniqueUrlFromMetadataResource(resource);
			return SearchParameterMap.newSynchronous().add("url", new UriParam(url));
		} else {
			TokenParam identifierToken = extractIdentifierFromOtherResourceTypes(resource);
			return SearchParameterMap.newSynchronous().add("identifier", identifierToken);
		}
	}


	/**
	 * Strategy is to build a SearchParameterMap same way the SearchParamValidatingInterceptor does, to make sure that
	 * the loader search detects existing resources and routes process to 'update' path, to avoid treating it as a new
	 * upload which validator later rejects as duplicated.
	 * To achieve this, we try canonicalizing the SearchParameter first (as the validator does) and if that is not possible
	 * we cascade to building the map from 'url' or 'identifier'.
	 */
	private SearchParameterMap buildSearchParameterMapForSearchParameter(IBaseResource theResource) {
		Optional<SearchParameterMap> spmFromCanonicalized = mySearchParameterHelper.buildSearchParameterMapFromCanonical(theResource);
		if (spmFromCanonicalized.isPresent()) {
			return spmFromCanonicalized.get();
		}

		if (resourceHasUrlElement(theResource)) {
			String url = extractUniqueUrlFromMetadataResource(theResource);
			return SearchParameterMap.newSynchronous().add("url", new UriParam(url));
		} else {
			TokenParam identifierToken = extractIdentifierFromOtherResourceTypes(theResource);
			return SearchParameterMap.newSynchronous().add("identifier", identifierToken);
		}
	}


	private String extractUniqeIdFromNamingSystem(IBaseResource resource) {
		FhirTerser terser = myFhirContext.newTerser();
		IBase uniqueIdComponent = (IBase) terser.getSingleValueOrNull(resource, "uniqueId");
		if (uniqueIdComponent == null) {
			throw new ImplementationGuideInstallationException(Msg.code(1291) + "NamingSystem does not have uniqueId component.");
		}
		IPrimitiveType<?> asPrimitiveType = (IPrimitiveType<?>) terser.getSingleValueOrNull(uniqueIdComponent, "value");
		return (String) asPrimitiveType.getValue();
	}

	private String extractIdFromSubscription(IBaseResource resource) {
		FhirTerser terser = myFhirContext.newTerser();
		IPrimitiveType<?> asPrimitiveType = (IPrimitiveType<?>) terser.getSingleValueOrNull(resource, "id");
		return (String) asPrimitiveType.getValue();
	}

	private String extractUniqueUrlFromMetadataResource(IBaseResource resource) {
		FhirTerser terser = myFhirContext.newTerser();
		IPrimitiveType<?> asPrimitiveType = (IPrimitiveType<?>) terser.getSingleValueOrNull(resource, "url");
		return (String) asPrimitiveType.getValue();
	}

	private TokenParam extractIdentifierFromOtherResourceTypes(IBaseResource resource) {
		FhirTerser terser = myFhirContext.newTerser();
		Identifier identifier = (Identifier) terser.getSingleValueOrNull(resource, "identifier");
		if (identifier != null) {
			return new TokenParam(identifier.getSystem(), identifier.getValue());
		} else {
			throw new UnsupportedOperationException(Msg.code(1292) + "Resources in a package must have a url or identifier to be loaded by the package installer.");
		}
	}

	private boolean resourceHasUrlElement(IBaseResource resource) {
		BaseRuntimeElementDefinition<?> def = myFhirContext.getElementDefinition(resource.getClass());
		if (!(def instanceof BaseRuntimeElementCompositeDefinition)) {
			throw new IllegalArgumentException(Msg.code(1293) + "Resource is not a composite type: " + resource.getClass().getName());
		}
		BaseRuntimeElementCompositeDefinition<?> currentDef = (BaseRuntimeElementCompositeDefinition<?>) def;
		BaseRuntimeChildDefinition nextDef = currentDef.getChildByName("url");
		return nextDef != null;
	}

	@VisibleForTesting
	void setFhirContextForUnitTest(FhirContext theCtx) {
		myFhirContext = theCtx;
	}

}