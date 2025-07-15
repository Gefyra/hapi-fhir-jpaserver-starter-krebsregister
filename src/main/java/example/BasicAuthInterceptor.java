package example;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationInterceptor;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRule;
import ca.uhn.fhir.rest.server.interceptor.auth.RuleBuilder;
import java.util.List;

public class BasicAuthInterceptor extends AuthorizationInterceptor {

	@Override
	public List<IAuthRule> buildRuleList(RequestDetails theRequestDetails) {

		// Process this header
		String authHeader = theRequestDetails.getHeader("Authorization");

		if (authHeader == null || authHeader.equals("Basic dXNlcjpw")) {
			// Apply rules
			RuleBuilder builder = new RuleBuilder();
			return builder.allowAll().build();
		} else {
			return new RuleBuilder().denyAll().build();
		}
	}
}
