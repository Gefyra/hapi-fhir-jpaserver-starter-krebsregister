package example;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.util.FhirTerser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.stereotype.Component;

@Component
public class ReceiveBundleProvider {

	private final DaoRegistry daoRegistry;
	private final FhirContext ctx;
	private final TestValidator testValidator;

	public ReceiveBundleProvider(DaoRegistry daoRegistry, FhirContext ctx, TestValidator testValidator) {
		this.daoRegistry = daoRegistry;
		this.ctx = ctx;
		this.testValidator = testvalidator;
	}

	@Operation(name = "$receiveBundle", idempotent = false)
	public MethodOutcome receiveBundle(@OperationParam(name = "resource") Bundle bundle) {
		//TODO Validate the bundle

		//createTransactionBundle(bundle);
		// TODO: get Transaction Provider and process the transaction bundle

		testValidator.validateWithResult(bundle);

		IFhirResourceDao bundleDao = daoRegistry.getResourceDao("Bundle");
		DaoMethodOutcome daoMethodOutcome = bundleDao.create(bundle);
		IBaseOperationOutcome operationOutcome = daoMethodOutcome.getOperationOutcome();

		MethodOutcome methodOutcome = new MethodOutcome();
		methodOutcome.setOperationOutcome(operationOutcome);

		// split resources in the bundle
		//persist Practitioner
		Optional<Resource> practitioner = bundle.getEntry().stream()
			.filter(entry -> entry.getResource() instanceof Practitioner).map(e -> e.getResource())
			.findFirst();

		IFhirResourceDao<Practitioner> practitionerDao = daoRegistry.getResourceDao(
			Practitioner.class);
		DaoMethodOutcome outcomePractitioner = practitionerDao.create(
			(Practitioner) practitioner.get());

		IIdType practitionerId = outcomePractitioner.getId();

		Optional<Resource> practitionerRole = bundle.getEntry().stream()
			.filter(entry -> entry.getResource() instanceof PractitionerRole)
			.map(e -> e.getResource()).findFirst();
		IFhirResourceDao<PractitionerRole> practitionerRoleDao = daoRegistry.getResourceDao(
			PractitionerRole.class);
		PractitionerRole practitionerRoleInstance = (PractitionerRole) practitionerRole.get();
		practitionerRoleInstance.setPractitioner(
			new Reference(practitionerId));
		practitionerRoleInstance.setOrganization(null);

		DaoMethodOutcome outcomePractitionerRole = practitionerRoleDao.create(
			(PractitionerRole) practitionerRoleInstance);

		return methodOutcome;
	}

	private Bundle createTransactionBundle(Bundle collectionBundle) {
		// 1. Erster Durchgang: Mapping alterKey → neue URN aufbauen
		Map<String, String> oldToNewUrn = new HashMap<>();
		for (Bundle.BundleEntryComponent entry : collectionBundle.getEntry()) {
			IBaseResource res = entry.getResource();
			if (res == null) continue;

			// alter Schlüssel: fullUrl oder resourceType/id
			String oldKey = entry.hasFullUrl()
				? entry.getFullUrl()
				: res.fhirType() + "/" + res.getIdElement().getIdPart();

			// neue UUID + URN
			String uuid = UUID.randomUUID().toString();
			String urn  = "urn:uuid:" + uuid;

			oldToNewUrn.put(oldKey, urn);
		}

		// 2. Zweiter Durchgang: Transaction-Bundle tatsächlich aufbauen
		Bundle txBundle = new Bundle();
		txBundle.setType(Bundle.BundleType.TRANSACTION);

		for (Bundle.BundleEntryComponent oldEntry : collectionBundle.getEntry()) {
			IBaseResource res = oldEntry.getResource();
			if (res == null) continue;

			// alten Schlüssel wiederherstellen
			String oldKey = oldEntry.hasFullUrl()
				? oldEntry.getFullUrl()
				: res.fhirType() + "/" + res.getIdElement().getIdPart();

			// URN und UUID aus Map
			String newUrn = oldToNewUrn.get(oldKey);
			String newId  = newUrn.substring("urn:uuid:".length());

			// frisches Entry
			Bundle.BundleEntryComponent newEntry = new Bundle.BundleEntryComponent()
				.setFullUrl(newUrn)
				.setResource((Resource) res)
				.setRequest(new Bundle.BundleEntryRequestComponent()
					.setMethod(Bundle.HTTPVerb.POST)
					.setUrl(res.fhirType())
				);

			// interne Resource-ID setzen
			res.setId(new IdType(res.fhirType(), newId));

			txBundle.addEntry(newEntry);
		}

		// 3. Referenzen in allen Ressourcen ersetzen
		for (Bundle.BundleEntryComponent entry : txBundle.getEntry()) {
			Resource res = entry.getResource();
			if (res == null) continue;

			FhirTerser terser = ctx.newTerser();

			// finde alle Reference-Felder
			List<Reference> refs = terser.getAllPopulatedChildElementsOfType(res, Reference.class);
			for (Reference ref : refs) {
				String oldRef = ref.getReference();
				if (oldToNewUrn.containsKey(oldRef)) {
					// ersetze auf neue URN
					ref.setReference(oldToNewUrn.get(oldRef));
				}
			}
		}
		return txBundle;
	}
}

