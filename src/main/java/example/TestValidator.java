package example;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.validation.FhirValidator;
import java.io.IOException;
import org.springframework.stereotype.Service;

@Service
public class TestValidator extends FhirValidator {

	public TestValidator(FhirContext ctx) throws IOException {
		super(ctx);

		NPMPackageValidationSupport npmPackageValidationSupport = new NpmPackageValidationSupport(
			ctx);
		npmPackageValidationSupport.loadPackageFromClasspath(
			"classpath:/packages/de.basisprofil.r4-1.5.4-snapshots.tgz");

		ValidationSupportChain validationSupportChain = new ValidationSupportChain(
			npmPackageValidationSupport,
			new DefaultProfileValidationSupport(ctx),
			new CommonCodeSystemsTerminologyService(ctx),
			new InmemoryTerminologyService(ctx));

		FhirInstanceValidator validator = new FhirInstanceValidator(validationSupportChain);
		registerValidatorModule(validator);

		validator.setAnyExtensionsAllowed(false);
		validator.setNoExtensibleWarnings(true);
		validator.setErrorForUnknownProfiles(true);
	}
}
