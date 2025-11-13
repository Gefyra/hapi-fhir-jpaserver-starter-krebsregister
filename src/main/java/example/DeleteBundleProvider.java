package example;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.provider.JpaSystemProvider;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseParameters;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleEntryRequestComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Provider that implements the custom {@code $deleteBundle} operation.
 *
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DeleteBundleProvider implements IResourceProvider {

	/**
	 * FHIR context for parsing and resource utilities.
	 */
	private final FhirContext ctx;
	/**
	 * JPA system provider for transaction processing.
	 */
	private final JpaSystemProvider jpaSystemProvider;
	/**
	 * Provenance Dao
	 */
	private final IFhirResourceDao<Provenance> provenanceDao;


	/**
	 *
	 *
	 * @param requestDetails request context provided by HAPI FHIR
	 * @param theProvenanceId incoming provenance id to delete
	 * @return transaction response bundle
	 * @throws InvalidRequestException when validation fails or the transaction execution encounters an error
	 */
	@Operation(name = "$deleteBundle")
	public Bundle $deleteBundle(RequestDetails requestDetails,
										 @IdParam IdType theProvenanceId) {

		Provenance provenance = provenanceDao.read(theProvenanceId);
		if (provenance == null) {
			throw new ResourceNotFoundException("Provenance not found");
		}


		// Only create and execute transaction if no errors
		Bundle transactionResponse = new Bundle();
		try {
			Bundle tx = createTransactionBundle(provenance.getTarget(), theProvenanceId);
			log.info("Created delete transaction bundle for %s resources".formatted(tx.getEntry().stream().count()));
			log.debug("The transaction bundle: " + tx);
			transactionResponse = (Bundle) jpaSystemProvider.transaction(requestDetails, tx);

			//Expunge deleted resources
			jpaSystemProvider.expunge(new IntegerType(10000), new BooleanType(true),
				new BooleanType(true), new BooleanType(false), requestDetails);
		} catch (Exception e) {
			throw new UnprocessableEntityException("Error during transaction processing: " + e.getMessage());
		}
		return transactionResponse;
	}

	/**
	 * Transforms all 'Provenance.target' references into a transaction that deletes all the referenced resources.
	 *
	 * @param provenanceTargets bundle whose entries should be rewritten for transaction submission
	 * @param theProvenanceId
	 * @return transaction bundle with delete requests
	 */
	private Bundle createTransactionBundle(List<Reference> provenanceTargets, IdType theProvenanceId) {
		Bundle txBundle = new Bundle();
		txBundle.setType(BundleType.TRANSACTION);

		for (Reference reference : provenanceTargets) {
			BundleEntryComponent newEntry = new BundleEntryComponent()
				.setRequest(new BundleEntryRequestComponent()
					.setMethod(HTTPVerb.DELETE)
					.setUrl(reference.getReference())
				);
			txBundle.addEntry(newEntry);
		}

		//Add provenance itself
		BundleEntryComponent newEntry = new BundleEntryComponent()
			.setRequest(new BundleEntryRequestComponent()
				.setMethod(HTTPVerb.DELETE)
				.setUrl(theProvenanceId.asStringValue())
			);
		txBundle.addEntry(newEntry);

		return txBundle;
	}

	@Override
	public Class<? extends IBaseResource> getResourceType() {
		return Provenance.class;
	}
}
