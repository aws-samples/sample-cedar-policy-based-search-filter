package com.example.avp.opensearch.cdk;

import java.util.List;

import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.apigateway.AccessLogFormat;
import software.amazon.awscdk.services.apigateway.AuthorizationType;
import software.amazon.awscdk.services.apigateway.CognitoUserPoolsAuthorizer;
import software.amazon.awscdk.services.apigateway.CorsOptions;
import software.amazon.awscdk.services.apigateway.JsonSchema;
import software.amazon.awscdk.services.apigateway.JsonSchemaType;
import software.amazon.awscdk.services.apigateway.LambdaIntegration;
import software.amazon.awscdk.services.apigateway.LogGroupLogDestination;
import software.amazon.awscdk.services.apigateway.MethodLoggingLevel;
import software.amazon.awscdk.services.apigateway.MethodOptions;
import software.amazon.awscdk.services.apigateway.Model;
import software.amazon.awscdk.services.apigateway.RequestValidator;
import software.amazon.awscdk.services.apigateway.RestApi;
import software.amazon.awscdk.services.apigateway.StageOptions;
import software.amazon.awscdk.services.cognito.UserPool;
import software.amazon.awscdk.services.lambda.IFunction;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.wafv2.CfnWebACL;
import software.amazon.awscdk.services.wafv2.CfnWebACLAssociation;
import software.constructs.Construct;

/**
 * CDK construct that provisions an API Gateway REST API for the HR Portal.
 * <p>
 * Creates a POST /search endpoint backed by the Search Lambda function, a Cognito
 * authorizer for token validation, CORS configuration, access logging, request
 * validation, and CloudWatch logging for all methods.
 * <p>
 * On missing, malformed, or expired access tokens, API Gateway returns HTTP 401
 * without invoking the Lambda function.
 */
public class ApiConstruct extends Construct {

    private final RestApi api;

    public ApiConstruct(final Construct scope, final String id,
                        final UserPool userPool, final IFunction searchFunction) {
        super(scope, id);

        // Create CloudWatch Log Group for API Gateway access logs (AwsSolutions-APIG1)
        LogGroup apiAccessLogGroup = LogGroup.Builder.create(this, "ApiAccessLogGroup")
                .logGroupName("/aws/apigateway/hr-portal-api-access-logs")
                .retention(RetentionDays.ONE_WEEK)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

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
                        // AwsSolutions-APIG1: Enable access logging
                        .accessLogDestination(new LogGroupLogDestination(apiAccessLogGroup))
                        .accessLogFormat(AccessLogFormat.jsonWithStandardFields())
                        // AwsSolutions-APIG6: Enable CloudWatch logging for all methods
                        .loggingLevel(MethodLoggingLevel.INFO)
                        .dataTraceEnabled(false)
                        .metricsEnabled(true)
                        .build())
                .build();

        // AwsSolutions-APIG2: Create request validator
        RequestValidator requestValidator = RequestValidator.Builder.create(this, "RequestValidator")
                .restApi(this.api)
                .requestValidatorName("hr-portal-request-validator")
                .validateRequestBody(true)
                .validateRequestParameters(true)
                .build();

        // Create a request model for the search endpoint
        Model searchRequestModel = Model.Builder.create(this, "SearchRequestModel")
                .restApi(this.api)
                .contentType("application/json")
                .modelName("SearchRequest")
                .schema(JsonSchema.builder()
                        .type(JsonSchemaType.OBJECT)
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

        // Add POST /search endpoint with Cognito authorization and request validation
        api.getRoot().addResource("search")
                .addMethod("POST", searchIntegration, MethodOptions.builder()
                        .authorizationType(AuthorizationType.COGNITO)
                        .authorizer(cognitoAuthorizer)
                        .requestValidator(requestValidator)
                        .requestModels(java.util.Map.of("application/json", searchRequestModel))
                        .build());

        // AwsSolutions-APIG3: Associate WAFv2 Web ACL with the API Gateway stage
        CfnWebACL webAcl = CfnWebACL.Builder.create(this, "ApiWebAcl")
                .defaultAction(CfnWebACL.DefaultActionProperty.builder()
                        .allow(CfnWebACL.AllowActionProperty.builder().build())
                        .build())
                .scope("REGIONAL")
                .visibilityConfig(CfnWebACL.VisibilityConfigProperty.builder()
                        .cloudWatchMetricsEnabled(true)
                        .metricName("hr-portal-api-waf")
                        .sampledRequestsEnabled(true)
                        .build())
                .name("hr-portal-api-waf")
                .rules(List.of(
                        CfnWebACL.RuleProperty.builder()
                                .name("AWSManagedRulesCommonRuleSet")
                                .priority(1)
                                .overrideAction(CfnWebACL.OverrideActionProperty.builder()
                                        .none(java.util.Map.of())
                                        .build())
                                .visibilityConfig(CfnWebACL.VisibilityConfigProperty.builder()
                                        .cloudWatchMetricsEnabled(true)
                                        .metricName("aws-common-rules")
                                        .sampledRequestsEnabled(true)
                                        .build())
                                .statement(CfnWebACL.StatementProperty.builder()
                                        .managedRuleGroupStatement(CfnWebACL.ManagedRuleGroupStatementProperty.builder()
                                                .vendorName("AWS")
                                                .name("AWSManagedRulesCommonRuleSet")
                                                .build())
                                        .build())
                                .build()
                ))
                .build();

        // Associate WAF WebACL with the API Gateway stage
        CfnWebACLAssociation.Builder.create(this, "ApiWebAclAssociation")
                .webAclArn(webAcl.getAttrArn())
                .resourceArn("arn:aws:apigateway:" + software.amazon.awscdk.Stack.of(this).getRegion()
                        + "::/restapis/" + api.getRestApiId() + "/stages/" + api.getDeploymentStage().getStageName())
                .build();
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
