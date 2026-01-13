package example;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.provider.JpaSystemProvider;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.util.FhirTerser;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.ValidationResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleEntryRequestComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.Provenance;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.stereotype.Component;

/**
 * Provider that implements the custom {@code $receiveBundle} operation.
 * <p>
 * An incoming bundle is validated, the validation outcome is always returned, and—only when the
 * validation does not contain {@link ResultSeverityEnum#ERROR} or {@link ResultSeverityEnum#FATAL}
 * messages—the bundle is converted into a transaction bundle with freshly generated UUID URNs and
 * executed via the injected {@link JpaSystemProvider}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ReceiveBundleProvider {

	/**
	 * FHIR context for parsing and resource utilities.
	 */
	private final FhirContext ctx;
	/**
	 * Custom validator for FHIR resources.
	 */
	private final SterbefallValidator sterbefallValidator;
	/**
	 * JPA system provider for transaction processing.
	 */
	private final JpaSystemProvider jpaSystemProvider;

	/**
	 * Custom FHIR operation that validates an incoming bundle and persists it when validation succeeds.
	 * <p>
	 * The bundle is validated using the injected validator. When the validation produces an
	 * {@link OperationOutcome} containing {@code ERROR} or {@code FATAL} severities, processing stops by
	 * throwing an {@link UnprocessableEntityException} that exposes the outcome to the caller. Otherwise the
	 * bundle is transformed into a transaction bundle with UUID-based URNs and submitted via
	 * {@link JpaSystemProvider#transaction(RequestDetails, IBaseBundle)}. The transaction response bundle
	 * is returned to the client.
	 *
	 * @param requestDetails request context provided by HAPI FHIR
	 * @param bundle         incoming bundle to validate (and potentially persist)
	 * @return transaction response bundle
	 * @throws InvalidRequestException when validation fails or the transaction execution encounters an error
	 */
	@Operation(name = "$receiveBundle")
	public Bundle receiveBundle(RequestDetails requestDetails,
		@OperationParam(name = "resource") Bundle bundle) {

		ensureProfileClaim(bundle);

		// Validate the bundle and print the result
		ValidationResult validationResult = sterbefallValidator.validateWithResult(bundle);
		log.info("Validation successful?: " + validationResult.isSuccessful());
		log.debug("Operation outcome: " + ctx.newJsonParser().setPrettyPrint(true)
			.encodeResourceToString(validationResult.toOperationOutcome()));

		// if validation failed set status code and return OperationOutcome
		if (!validationResult.isSuccessful()) {
			throw new UnprocessableEntityException("Bundle validation failed",
				validationResult.toOperationOutcome());
		}

		// Validate Provenance resource
		validateProvenanceResource(bundle);

		// Only create and execute transaction if no errors
		Bundle transactionResponse = new Bundle();
		try {
			if (validationResult.isSuccessful()) {
				Bundle tx = createTransactionBundle(bundle);
				transactionResponse = (Bundle) jpaSystemProvider.transaction(requestDetails, tx);
			}
		} catch (Exception e) {
			throw new UnprocessableEntityException("Error during transaction processing: " + e.getMessage());
		}
		return transactionResponse;
	}

	/**
	 * Add the correct meta.profile claim to a given bundle. This is necessary in order to generate a validation report that checks against specific STF profiles.
	 *
	 * @param bundle the bundle to ensure correct profile claim
	 */
	private void ensureProfileClaim(Bundle bundle) {
		//Remove all existing claims
		bundle.getMeta().getProfile().clear();

		//Add relevant profile claim
		final String canonicalUrl = "http://gematik.de/fhir/oegd/stf/StructureDefinition/StfExportBundle";
		bundle.getMeta().addProfile(canonicalUrl);
	}

	/**
	 * Validates that the bundle contains exactly one Provenance resource with CREATE activity
	 * and that all other resources in the bundle are referenced by this Provenance.
	 *
	 * @param bundle the bundle to validate
	 * @throws UnprocessableEntityException if validation fails
	 */
	private void validateProvenanceResource(Bundle bundle) {
		// Find all Provenance resources with CREATE activity
		List<Provenance> createProvenances = bundle.getEntry().stream()
			.filter(entry -> entry.getResource() instanceof Provenance)
			.map(entry -> (Provenance) entry.getResource())
			.filter(this::hasCreateActivity)
			.toList();

		// Check exactly one Provenance with CREATE activity exists
		if (createProvenances.isEmpty()) {
			OperationOutcome outcome = createOperationOutcome(
				"Bundle must contain exactly one Provenance resource with CREATE activity (system: 'http://terminology.hl7.org/CodeSystem/v3-DataOperation', code: 'CREATE')");
			throw new UnprocessableEntityException("Missing Provenance resource with CREATE activity", outcome);
		}

		if (createProvenances.size() > 1) {
			OperationOutcome outcome = createOperationOutcome(
				"Bundle must contain exactly one Provenance resource with CREATE activity, found " + createProvenances.size());
			throw new UnprocessableEntityException("Multiple Provenance resources with CREATE activity found", outcome);
		}

		Provenance createProvenance = createProvenances.get(0);

		// Collect all resource identifiers from bundle (excluding only the CREATE Provenance)
		Map<String, BundleEntryComponent> resourceMap = new HashMap<>();
		for (BundleEntryComponent entry : bundle.getEntry()) {
			Resource res = entry.getResource();
			if (res == null) {
				continue;
			}
			
			// Exclude only the CREATE Provenance resource
			if (res == createProvenance) {
				continue;
			}

			// Store multiple possible reference formats
			String typeId = res.fhirType() + "/" + res.getIdElement().getIdPart();
			resourceMap.put(typeId, entry);
			resourceMap.put("/" + typeId, entry);
			
			if (entry.hasFullUrl()) {
				resourceMap.put(entry.getFullUrl(), entry);
			}
		}

		// Check that Provenance.target references all other resources
		List<Reference> targets = createProvenance.getTarget();
		if (targets.isEmpty()) {
			OperationOutcome outcome = createOperationOutcome(
				"Provenance resource must reference all other resources in bundle via target element");
			throw new UnprocessableEntityException("Provenance has no targets", outcome);
		}

		// Track which resources are referenced
		Map<BundleEntryComponent, Boolean> referencedResources = new HashMap<>();
		resourceMap.values().forEach(entry -> referencedResources.put(entry, false));

		for (Reference target : targets) {
			String ref = target.getReference();
			if (ref != null) {
				BundleEntryComponent entry = resourceMap.get(ref);
				if (entry != null) {
					referencedResources.put(entry, true);
				}
			}
		}

		// Find unreferenced resources and create one issue per missing reference
		List<String> unreferencedResources = new ArrayList<>();
		for (Map.Entry<BundleEntryComponent, Boolean> entry : referencedResources.entrySet()) {
			if (!entry.getValue()) {
				Resource res = entry.getKey().getResource();
				String identifier = res.fhirType() + "/" + res.getIdElement().getIdPart();
				unreferencedResources.add(identifier);
			}
		}

		if (!unreferencedResources.isEmpty()) {
			OperationOutcome outcome = new OperationOutcome();
			for (String unreferenced : unreferencedResources) {
				outcome.addIssue()
					.setSeverity(IssueSeverity.ERROR)
					.setCode(IssueType.INVALID)
					.setDiagnostics("Provenance resource must reference resource: " + unreferenced);
			}
			throw new UnprocessableEntityException("Incomplete Provenance references", outcome);
		}
	}

	/**
	 * Checks if the Provenance resource has the required CREATE activity.
	 *
	 * @param provenance the Provenance resource to check
	 * @return true if CREATE activity is present with correct system
	 */
	private boolean hasCreateActivity(Provenance provenance) {
		if (!provenance.hasActivity()) {
			return false;
		}

		CodeableConcept activity = provenance.getActivity();
		for (Coding coding : activity.getCoding()) {
			if ("http://terminology.hl7.org/CodeSystem/v3-DataOperation".equals(coding.getSystem())
				&& "CREATE".equals(coding.getCode())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Creates an OperationOutcome with an error message.
	 *
	 * @param message the error message
	 * @return OperationOutcome with the error
	 */
	private OperationOutcome createOperationOutcome(String message) {
		OperationOutcome outcome = new OperationOutcome();
		outcome.addIssue()
			.setSeverity(IssueSeverity.ERROR)
			.setCode(IssueType.INVALID)
			.setDiagnostics(message);
		return outcome;
	}

	/**
	 * Transforms the supplied bundle into a transaction bundle using freshly generated UUID URNs.
	 * <p>
	 * The method first records mappings for every entry's {@code fullUrl} and type/id pair, then
	 * augments the mapping with references found inside the original resources. Afterwards it
	 * constructs a transaction bundle that reuses the existing resource instances while updating
	 * their internal IDs and fullUrls. Finally, all references inside the transaction bundle are
	 * rewritten so that they point to the newly generated URNs.
	 *
	 * @param collectionBundle bundle whose entries should be rewritten for transaction submission
	 * @return transaction bundle with aligned fullUrls, IDs, and references
	 */
	private Bundle createTransactionBundle(Bundle collectionBundle) {
		// --- 1. Pass: fullUrl + ResourceType/Id mapping ---
		Map<String, String> oldToNewUrn = new HashMap<>();
		for (BundleEntryComponent entry : collectionBundle.getEntry()) {
			Resource res = entry.getResource();
			if (res == null) {
				continue;
			}

			String uuid = UUID.randomUUID().toString();
			String urn = "urn:uuid:" + uuid;

			// 1a) Map fullUrl
			if (entry.hasFullUrl()) {
				oldToNewUrn.put(entry.getFullUrl(), urn);
			}
			// 1b) Map ResourceType/Id and leading slash
			String typeId = res.fhirType() + "/" + res.getIdElement().getIdPart();
			oldToNewUrn.put(typeId, urn);
			oldToNewUrn.put("/" + typeId, urn);
		}

		// --- 2. Pass: map all reference strings in original resources ---
		FhirTerser terser = ctx.newTerser();
		for (BundleEntryComponent entry : collectionBundle.getEntry()) {
			Resource res = entry.getResource();
			if (res == null) {
				continue;
			}

			List<Reference> refs = terser.getAllPopulatedChildElementsOfType(res, Reference.class);
			for (Reference ref : refs) {
				String oldRef = ref.getReference();
				if (oldRef == null || oldToNewUrn.containsKey(oldRef)) {
					continue;
				}

				// Find the bundle entry the oldRef points to
				for (BundleEntryComponent targetEntry : collectionBundle.getEntry()) {
					Resource targetRes = targetEntry.getResource();
					if (targetRes == null) {
						continue;
					}
					String targetKey = targetRes.fhirType() + "/" + targetRes.getIdElement().getIdPart();
					if (oldRef.equals(targetKey) || oldRef.equals("/" + targetKey)) {
						// Mapping for this key already exists under targetKey
						String mapped = oldToNewUrn.get(targetKey);
						if (mapped != null) {
							oldToNewUrn.put(oldRef, mapped);
						}
						break;
					}
				}
			}
		}

		// --- 3. Pass: build the transaction bundle ---
		Bundle txBundle = new Bundle();
		txBundle.setType(BundleType.TRANSACTION);

		for (BundleEntryComponent oldEntry : collectionBundle.getEntry()) {
			Resource res = oldEntry.getResource();
			if (res == null) {
				continue;
			}

			// Determine newUrn using fullUrl or typeId
			String typeId = res.fhirType() + "/" + res.getIdElement().getIdPart();
			String lookup = oldEntry.hasFullUrl() ? oldEntry.getFullUrl() : typeId;
			String newUrn = oldToNewUrn.getOrDefault(lookup,     // first try fullUrl
				oldToNewUrn.get(typeId));   // fallback to typeId

			// Set internal ID in the resource object
			String newId = newUrn.substring("urn:uuid:".length());
			res.setId(new IdType(res.fhirType(), newId));

			// Create new entry
			BundleEntryComponent newEntry = new BundleEntryComponent()
				.setFullUrl(newUrn)
				.setResource(res)
				.setRequest(new BundleEntryRequestComponent()
					.setMethod(HTTPVerb.POST)
					.setUrl(res.fhirType())
				);
			txBundle.addEntry(newEntry);
		}

		// --- 4. Pass: replace all references in txBundle, including performer-actor ---
		for (BundleEntryComponent entry : txBundle.getEntry()) {
			Resource res = entry.getResource();
			if (res == null) {
				continue;
			}

			// 4a) General reference fields
			FhirTerser t2 = ctx.newTerser();
			List<Reference> refs = t2.getAllPopulatedChildElementsOfType(res, Reference.class);
			for (Reference ref : refs) {
				String oldRef = ref.getReference();
				if (oldRef == null) {
					continue;
				}

				// Mapping lookup
				String mapped = oldToNewUrn.get(oldRef);
				if (mapped == null && oldRef.startsWith("/")) {
					mapped = oldToNewUrn.get(oldRef.substring(1));
				}
				if (mapped != null) {
					ref.setReference(mapped);
				}
			}
		}
		return txBundle;
	}
}
