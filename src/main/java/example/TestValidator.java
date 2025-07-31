package example;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.validation.FhirValidator;
import java.io.IOException;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.springframework.stereotype.Service;

@Service
public class TestValidator extends FhirValidator {

	public TestValidator(FhirContext ctx) throws IOException {
		super(ctx);

		NpmPackageValidationSupport npmPackageValidationSupport = new NpmPackageValidationSupport(
			ctx);
		npmPackageValidationSupport.loadPackageFromClasspath(
			"classpath:/packages/de.basisprofil.r4-1.5.4-snapshots.tgz");

		ValidationSupportChain validationSupportChain = new ValidationSupportChain(
			npmPackageValidationSupport,
			new DefaultProfileValidationSupport(ctx),
			new CommonCodeSystemsTerminologyService(ctx),
			new InMemoryTerminologyServerValidationSupport(ctx));

		FhirInstanceValidator validator = new FhirInstanceValidator(validationSupportChain);
		registerValidatorModule(validator);

		validator.setAnyExtensionsAllowed(false);
		validator.setNoExtensibleWarnings(true);
		validator.setErrorForUnknownProfiles(true);
	}
}
