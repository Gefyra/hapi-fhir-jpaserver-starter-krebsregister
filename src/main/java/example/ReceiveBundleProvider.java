package example;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.provider.JpaSystemProvider;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.util.FhirTerser;
import ca.uhn.fhir.validation.ValidationResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleEntryRequestComponent;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.stereotype.Component;

/**
 * Provider for handling the custom $receiveBundle operation.
 * <p>
 * This class validates an incoming FHIR Bundle, prints the validation result, and transforms the
 * bundle into a transaction bundle with new UUID-based URNs for all resources and their references.
 * The transaction bundle is then processed using the JPA system provider.
 */
@Component
public class ReceiveBundleProvider {

	/**
	 * Registry for accessing resource DAOs.
	 */
	private final DaoRegistry daoRegistry;
	/**
	 * FHIR context for parsing and resource utilities.
	 */
	private final FhirContext ctx;
	/**
	 * Custom validator for FHIR resources.
	 */
	private final TestValidator testValidator;
	/**
	 * JPA system provider for transaction processing.
	 */
	private final JpaSystemProvider jpaSystemProvider;

	/**
	 * Constructs the provider with required dependencies.
	 *
	 * @param daoRegistry       DAO registry for resource access
	 * @param ctx               FHIR context
	 * @param testValidator     Validator for FHIR resources
	 * @param jpaSystemProvider JPA system provider for transactions
	 */
	public ReceiveBundleProvider(DaoRegistry daoRegistry, FhirContext ctx,
		TestValidator testValidator, JpaSystemProvider jpaSystemProvider) {
		this.daoRegistry = daoRegistry;
		this.ctx = ctx;
		this.testValidator = testValidator;
		this.jpaSystemProvider = jpaSystemProvider;
	}

	/**
	 * Custom FHIR operation to receive and process a bundle.
	 * <p>
	 * Validates the bundle, prints the result, transforms it into a transaction bundle, and executes
	 * the transaction.
	 *
	 * @param requestDetails Request context
	 * @param bundle         The input FHIR Bundle resource
	 * @return The result Bundle from the transaction
	 */
	@Operation(name = "$receiveBundle", idempotent = false)
	public Bundle receiveBundle(RequestDetails requestDetails,
		@OperationParam(name = "resource") Bundle bundle) {

		// Validate the bundle and print the result
		ValidationResult validationResult = testValidator.validateWithResult(bundle);
		System.out.println("Validation result: " + validationResult.isSuccessful());
		System.out.println("Operation outcome: " + ctx.newJsonParser().setPrettyPrint(true)
			.encodeResourceToString(validationResult.toOperationOutcome()));

		// Transform the bundle and execute as a transaction
		Bundle tx = createTransactionBundle(bundle);
		return (Bundle) jpaSystemProvider.transaction(requestDetails, tx);
	}

	/**
	 * Transforms a collection bundle into a transaction bundle with new UUID URNs for all resources
	 * and references.
	 * <p>
	 * Steps: 1. Map all original fullUrls and resource type/IDs to new URNs. 2. Map all reference
	 * strings in the original resources to new URNs. 3. Build a new transaction bundle with updated
	 * fullUrls and resource IDs. 4. Replace all references in the transaction bundle with the new
	 * URNs.
	 *
	 * @param collectionBundle The input collection bundle
	 * @return A transaction bundle with updated references and IDs
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
		txBundle.setType(Bundle.BundleType.TRANSACTION);

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
					.setMethod(Bundle.HTTPVerb.POST)
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