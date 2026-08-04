package com.example.avp.opensearch.cdk;

import java.util.List;
import java.util.Map;

import software.amazon.awscdk.CustomResource;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.customresources.Provider;
import software.amazon.awscdk.services.ec2.ISecurityGroup;
import software.amazon.awscdk.services.ec2.IVpc;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

/**
 * CDK construct that provisions a custom resource Lambda to seed sample data
 * into the OpenSearch domain after deployment.
 * <p>
 * Creates four indices (index-employees, index-teams, index-job-postings,
 * index-compensation) with appropriate mappings and populates them with
 * sample data covering multiple regions and roles.
 * <p>
 * This construct must be created AFTER the SearchConstruct so the OpenSearch
 * domain is available.
 */
public class DataSeedingConstruct extends Construct {

    /**
     * Creates the data seeding custom resource.
     *
     * @param scope              the parent construct
     * @param id                 the construct identifier
     * @param vpc                the VPC where OpenSearch is deployed
     * @param subnetSelection    the subnet selection (private subnets with egress)
     * @param securityGroup      the Lambda security group allowing access to OpenSearch
     * @param openSearchEndpoint the OpenSearch domain HTTPS endpoint
     * @param searchLambdaRoleArn the ARN of the search Lambda's IAM role
     * @param executionRole      the IAM role for the seeding Lambda
     */
    public DataSeedingConstruct(final Construct scope, final String id,
                                final IVpc vpc,
                                final SubnetSelection subnetSelection,
                                final ISecurityGroup securityGroup,
                                final String openSearchEndpoint,
                                final String searchLambdaRoleArn,
                                final software.amazon.awscdk.services.iam.IRole executionRole) {
        super(scope, id);

        // AwsSolutions-L1: Use latest Node.js runtime
        Function seedingFunction = Function.Builder.create(this, "DataSeedingFunction")
                .runtime(Runtime.NODEJS_22_X)
                .handler("index.handler")
                .code(Code.fromAsset("cdk/src/main/resources/data-seeding-lambda"))
                .memorySize(512)
                .timeout(Duration.minutes(5))
                // VPC configuration to reach OpenSearch
                .vpc(vpc)
                .vpcSubnets(subnetSelection)
                .securityGroups(List.of(securityGroup))
                // Use the shared master role
                .role(executionRole)
                // Environment variables
                .environment(Map.of(
                        "OPENSEARCH_ENDPOINT", openSearchEndpoint,
                        "SEARCH_LAMBDA_ROLE_ARN", searchLambdaRoleArn
                ))
                .build();

        // Grant OpenSearch HTTP access for index creation and data indexing
        seedingFunction.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "es:ESHttpGet",
                        "es:ESHttpPost",
                        "es:ESHttpPut",
                        "es:ESHttpHead",
                        "es:ESHttpDelete"))
                .resources(List.of("arn:aws:es:" + Stack.of(this).getRegion() + ":"
                        + Stack.of(this).getAccount() + ":domain/hr-portal-search/*"))
                .build());

        // Create CDK custom resource provider
        // AwsSolutions-L1: Provider framework Lambda also uses latest runtime
        Provider provider = Provider.Builder.create(this, "DataSeedingProvider")
                .onEventHandler(seedingFunction)
                .logRetention(RetentionDays.ONE_DAY)
                .build();

        // Create the custom resource that triggers the seeding Lambda
        CustomResource.Builder.create(this, "DataSeedingResource")
                .serviceToken(provider.getServiceToken())
                .properties(Map.of(
                        // Adding a timestamp ensures re-seeding on each deployment
                        "Timestamp", String.valueOf(System.currentTimeMillis())
                ))
                .build();
    }
}
