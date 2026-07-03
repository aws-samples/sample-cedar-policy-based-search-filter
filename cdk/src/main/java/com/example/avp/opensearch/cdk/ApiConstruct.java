package com.example.avp.opensearch.cdk;

import java.util.List;

import software.amazon.awscdk.services.apigateway.AuthorizationType;
import software.amazon.awscdk.services.apigateway.CognitoUserPoolsAuthorizer;
import software.amazon.awscdk.services.apigateway.CorsOptions;
import software.amazon.awscdk.services.apigateway.LambdaIntegration;
import software.amazon.awscdk.services.apigateway.MethodOptions;
import software.amazon.awscdk.services.apigateway.RestApi;
import software.amazon.awscdk.services.apigateway.StageOptions;
import software.amazon.awscdk.services.cognito.UserPool;
import software.amazon.awscdk.services.lambda.IFunction;
import software.constructs.Construct;

/**
 * CDK construct that provisions an API Gateway REST API for the HR Portal.
 * <p>
 * Creates a POST /search endpoint backed by the Search Lambda function, a Cognito
 * authorizer for token validation, and CORS configuration for the frontend origin.
 * <p>
 * On missing, malformed, or expired access tokens, API Gateway returns HTTP 401
 * without invoking the Lambda function.
 */
public class ApiConstruct extends Construct {

    private final RestApi api;

    public ApiConstruct(final Construct scope, final String id,
                        final UserPool userPool, final IFunction searchFunction) {
        super(scope, id);

        // Create the REST API with CORS enabled at the API level
        this.api = RestApi.Builder.create(this, "HrPortalApi")
                .restApiName("hr-portal-api")
                .description("HR Portal Search API with AVP authorization")
                .defaultCorsPreflightOptions(CorsOptions.builder()
                        .allowOrigins(List.of("http://localhost:5173"))
                        .allowMethods(List.of("OPTIONS", "POST"))
                        .allowHeaders(List.of("Authorization", "Content-Type"))
                        .build())
                .deployOptions(StageOptions.builder()
                        .stageName("prod")
                        .build())
                .build();

        // Create Cognito authorizer that validates access tokens from the User Pool
        CognitoUserPoolsAuthorizer cognitoAuthorizer = CognitoUserPoolsAuthorizer.Builder
                .create(this, "HrPortalCognitoAuthorizer")
                .authorizerName("hr-portal-cognito-authorizer")
                .cognitoUserPools(List.of(userPool))
                .build();

        // Create Lambda integration for the search function
        LambdaIntegration searchIntegration = LambdaIntegration.Builder.create(searchFunction)
                .proxy(true)
                .build();

        // Add POST /search endpoint with Cognito authorization
        api.getRoot().addResource("search")
                .addMethod("POST", searchIntegration, MethodOptions.builder()
                        .authorizationType(AuthorizationType.COGNITO)
                        .authorizer(cognitoAuthorizer)
                        .build());
    }

    /**
     * Returns the REST API construct.
     */
    public RestApi getApi() {
        return api;
    }

    /**
     * Returns the API endpoint URL (e.g., https://{api-id}.execute-api.{region}.amazonaws.com/prod/).
     */
    public String getApiEndpoint() {
        return api.getUrl();
    }
}
