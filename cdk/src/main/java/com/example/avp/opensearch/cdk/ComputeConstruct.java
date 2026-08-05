package com.example.avp.opensearch.cdk;

import java.util.List;
import java.util.Map;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.services.ec2.ISecurityGroup;
import software.amazon.awscdk.services.ec2.IVpc;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.constructs.Construct;

/**
 * CDK construct that provisions the Search Lambda function for the HR Portal.
 * <p>
 * The Lambda function executes the three-layer authorization pipeline:
 * <ul>
 *   <li>Layer 1: Coarse-grained entity-type access via AVP BatchIsAuthorized</li>
 *   <li>Layer 2: Pre-filtering with organizational context</li>
 *   <li>Layer 3: Fine-grained per-result authorization</li>
 * </ul>
 * <p>
 * The function is deployed into VPC private subnets with access to OpenSearch,
 * Redis, and Amazon Verified Permissions.
 */
public class ComputeConstruct extends Construct {

    private final Function function;

    /**
     * Creates the Search Lambda function with VPC networking and IAM permissions.
     *
     * @param scope              the parent construct
     * @param id                 the construct identifier
     * @param vpc                the VPC to deploy the Lambda into
     * @param subnetSelection    the subnet selection for Lambda placement (private subnets)
     * @param securityGroup      the Lambda security group allowing outbound access
     * @param policyStoreId      the AVP policy store ID for authorization decisions
     * @param openSearchEndpoint the OpenSearch domain HTTPS endpoint
     * @param redisEndpoint      the Redis cluster endpoint address
     * @param redisPort          the Redis cluster endpoint port
     * @param openSearchDomainArn the OpenSearch domain ARN for scoped IAM permissions
     * @param redisAuthTokenSecret the Secrets Manager secret holding the Redis AUTH token
     */
    public ComputeConstruct(final Construct scope, final String id,
                            final IVpc vpc,
                            final SubnetSelection subnetSelection,
                            final ISecurityGroup securityGroup,
                            final String policyStoreId,
                            final String openSearchEndpoint,
                            final String redisEndpoint,
                            final String redisPort,
                            final String openSearchDomainArn,
                            final Secret redisAuthTokenSecret) {
        super(scope, id);

        this.function = Function.Builder.create(this, "SearchHandler")
                .runtime(Runtime.JAVA_21)
                .handler("com.example.avp.opensearch.lambda.SearchHandler")
                .code(Code.fromAsset("lambda/target/lambda-1.0.0-SNAPSHOT.jar"))
                .memorySize(1024)
                .timeout(Duration.seconds(30))
                // VPC configuration
                .vpc(vpc)
                .vpcSubnets(subnetSelection)
                .securityGroups(List.of(securityGroup))
                // Environment variables for the Lambda
                .environment(Map.of(
                        "POLICY_STORE_ID", policyStoreId,
                        "OPENSEARCH_ENDPOINT", openSearchEndpoint,
                        "REDIS_ENDPOINT", redisEndpoint,
                        "REDIS_PORT", redisPort,
                        "REDIS_AUTH_SECRET_ARN", redisAuthTokenSecret.getSecretArn()
                ))
                .build();

        // Grant the Lambda permission to read the Redis AUTH token secret
        redisAuthTokenSecret.grantRead(this.function);

        // AwsSolutions-IAM4: Replace managed VPC policy with scoped inline policy
        // Grant ENI management for VPC-attached Lambda (instead of AWSLambdaVPCAccessExecutionRole)
        this.function.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "ec2:CreateNetworkInterface",
                        "ec2:DeleteNetworkInterface",
                        "ec2:AssignPrivateIpAddresses",
                        "ec2:UnassignPrivateIpAddresses"))
                .resources(List.of("arn:aws:ec2:" + Stack.of(this).getRegion() + ":"
                        + Stack.of(this).getAccount() + ":*"))
                .build());

        // DescribeNetworkInterfaces does not support resource-level permissions
        this.function.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("ec2:DescribeNetworkInterfaces"))
                .resources(List.of("*"))
                .build());

        // AwsSolutions-IAM4: Replace managed BasicExecution policy with scoped inline policy
        this.function.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "logs:CreateLogGroup",
                        "logs:CreateLogStream",
                        "logs:PutLogEvents"))
                .resources(List.of("arn:aws:logs:" + Stack.of(this).getRegion() + ":"
                        + Stack.of(this).getAccount() + ":log-group:/aws/lambda/*"))
                .build());

        // Grant AVP permissions on the policy store (scoped resource)
        // AVP resource ARNs are regionless: arn:aws:verifiedpermissions::ACCOUNT:policy-store/ID
        this.function.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "verifiedpermissions:IsAuthorized",
                        "verifiedpermissions:BatchIsAuthorized",
                        "verifiedpermissions:IsAuthorizedWithToken"))
                .resources(List.of("arn:aws:verifiedpermissions::" 
                        + Stack.of(this).getAccount() + ":policy-store/" + policyStoreId))
                .build());

        // AwsSolutions-IAM5: Grant OpenSearch HTTP access scoped to the domain (read/search only)
        this.function.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "es:ESHttpGet",
                        "es:ESHttpPost",
                        "es:ESHttpHead"))
                .resources(List.of(openSearchDomainArn + "/*"))
                .build());
    }

    /**
     * Returns the Lambda function for use by other constructs (e.g., ApiConstruct).
     *
     * @return the Search Lambda function
     */
    public Function getFunction() {
        return function;
    }
}
