package example;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.provider.JpaSystemProvider;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.util.FhirTerser;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.ValidationResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleEntryRequestComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Parameters;
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
	 * Custom FHIR operation that validates and optionally persists an incoming bundle.
	 * <p>
	 * The bundle is validated and the resulting {@link org.hl7.fhir.r4.model.OperationOutcome} is
	 * added to the response {@link Parameters}. If the validation contains no {@code ERROR} or
	 * {@code FATAL} messages, the bundle is transformed into a transaction bundle and submitted via
	 * {@link JpaSystemProvider#transaction(RequestDetails, org.hl7.fhir.instance.model.api.IBaseBundle)}.
	 * The resulting transaction response is then appended to the returned parameters resource.
	 *
	 * @param requestDetails request context provided by HAPI FHIR
	 * @param bundle         incoming bundle to validate (and potentially persist)
	 * @return parameters with the validation outcome and, on success, the transaction response
	 */
	@Operation(name = "$receiveBundle", idempotent = false)
	public Parameters receiveBundle(RequestDetails requestDetails,
		@OperationParam(name = "resource") Bundle bundle) {

		// Validate the bundle and print the result
		ValidationResult validationResult = sterbefallValidator.validateWithResult(bundle);
		log.info("Validation successful?: " + validationResult.isSuccessful());
		log.debug("Operation outcome: " + ctx.newJsonParser().setPrettyPrint(true)
			.encodeResourceToString(validationResult.toOperationOutcome()));

		Parameters response = new Parameters();
		
		// Add validation result
		response.addParameter()
			.setName("validationResult")
			.setResource((Resource) validationResult.toOperationOutcome());

		// Only create and execute transaction if no errors
		if (validationResult.isSuccessful()) {
			Bundle tx = createTransactionBundle(bundle);
			Bundle transactionResponse = (Bundle) jpaSystemProvider.transaction(requestDetails, tx);
			
			response.addParameter()
				.setName("transactionResponse")
				.setResource(transactionResponse);
		}
		return response;
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
			Resource res = (Resource) entry.getResource();
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
			Resource res = (Resource) entry.getResource();
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
					Resource targetRes = (Resource) targetEntry.getResource();
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
			Resource res = (Resource) oldEntry.getResource();
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
