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

/**
 * Service class for FHIR resource validation using HAPI FHIR.
 *
 * This validator is configured with a custom validation support chain, including:
 * - NPM package validation support (loading a specific package from the classpath)
 * - Default profile validation support
 * - Common code systems terminology service
 * - In-memory terminology server validation support
 *
 * The validator is set to allow any extensions, suppress extensible warnings, and not error on unknown profiles.
 */
@Service
public class TestValidator extends FhirValidator {

	/**
	 * Constructs a TestValidator with a custom validation support chain and configuration.
	 *
	 * @param ctx The FHIR context to use for validation.
	 * @throws IOException If loading the NPM package from the classpath fails.
	 */
	public TestValidator(FhirContext ctx) throws IOException {
		super(ctx);

		// Load custom NPM package for validation support
		NpmPackageValidationSupport npmPackageValidationSupport = new NpmPackageValidationSupport(ctx);
		npmPackageValidationSupport.loadPackageFromClasspath(
			"classpath:/packages/de.basisprofil.r4-1.5.0-snapshots.tgz");
		npmPackageValidationSupport.loadPackageFromClasspath(
			"classpath:/packages/de.gematik.sterbefall-1.0.0-beta.2-snapshots.tgz");

		// Build the validation support chain
		ValidationSupportChain validationSupportChain = new ValidationSupportChain(
			npmPackageValidationSupport,
			new DefaultProfileValidationSupport(ctx),
			new CommonCodeSystemsTerminologyService(ctx),
			new InMemoryTerminologyServerValidationSupport(ctx));

		// Register the FHIR instance validator with the configured support chain
		FhirInstanceValidator validator = new FhirInstanceValidator(validationSupportChain);
		registerValidatorModule(validator);

		// Configure validator options
		validator.setAnyExtensionsAllowed(true);
		validator.setNoExtensibleWarnings(true);
		validator.setErrorForUnknownProfiles(false);
	}
}