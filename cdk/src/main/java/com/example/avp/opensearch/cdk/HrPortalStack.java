package com.example.avp.opensearch.cdk;

import java.util.List;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.constructs.Construct;

/**
 * Main CDK stack for the AVP OpenSearch HR Portal.
 * <p>
 * This stack orchestrates all infrastructure constructs in the correct dependency order:
 * <ol>
 *   <li>NetworkingConstruct → VPC, security groups, VPC Flow Logs</li>
 *   <li>AuthConstruct → Cognito User Pool, app client, domain</li>
 *   <li>SearchConstruct → OpenSearch domain (needs VPC, private subnets, OpenSearch SG)</li>
 *   <li>AuthorizationConstruct → AVP policy store</li>
 *   <li>CacheConstruct → Redis cluster (needs VPC, private subnets, Redis SG)</li>
 *   <li>ComputeConstruct → Lambda function (needs VPC, subnets, Lambda SG, policy store ID, endpoints)</li>
 *   <li>ApiConstruct → API Gateway (needs UserPool, Lambda function)</li>
 * </ol>
 * <p>
 * Exposes CDK outputs for API endpoint URL, Cognito pool ID, client ID, domain,
 * OpenSearch endpoint, and policy store ID.
 */
public class HrPortalStack extends Stack {

    public HrPortalStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // Shared subnet selection for private subnets with egress
        SubnetSelection privateSubnets = SubnetSelection.builder()
                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                .build();

        // 1. Networking: VPC, subnets, security groups, VPC Flow Logs
        NetworkingConstruct networking = new NetworkingConstruct(this, "Networking");

        // 2. Authentication: Cognito User Pool, users, app client, domain
        AuthConstruct auth = new AuthConstruct(this, "Auth");

        // Create a shared IAM role for OpenSearch master user access
        // AwsSolutions-IAM4: Use inline policies instead of AWS managed policies
        Role openSearchMasterRole = Role.Builder.create(this, "OpenSearchMasterRole")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .build();

        // Grant scoped CloudWatch Logs permissions (replaces AWSLambdaBasicExecutionRole)
        openSearchMasterRole.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "logs:CreateLogGroup",
                        "logs:CreateLogStream",
                        "logs:PutLogEvents"))
                .resources(List.of("arn:aws:logs:" + this.getRegion() + ":"
                        + this.getAccount() + ":log-group:/aws/lambda/*"))
                .build());

        // Grant scoped VPC ENI permissions (replaces AWSLambdaVPCAccessExecutionRole)
        openSearchMasterRole.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "ec2:CreateNetworkInterface",
                        "ec2:DeleteNetworkInterface",
                        "ec2:AssignPrivateIpAddresses",
                        "ec2:UnassignPrivateIpAddresses"))
                .resources(List.of("arn:aws:ec2:" + this.getRegion() + ":"
                        + this.getAccount() + ":*"))
                .build());

        // DescribeNetworkInterfaces does not support resource-level permissions
        openSearchMasterRole.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("ec2:DescribeNetworkInterfaces"))
                .resources(List.of("*"))
                .build());

        // 3. Search: OpenSearch domain in VPC private subnets
        SearchConstruct search = new SearchConstruct(this, "Search",
                networking.getVpc(),
                privateSubnets,
                networking.getOpenSearchSecurityGroup(),
                openSearchMasterRole.getRoleArn());

        // Grant OpenSearch HTTP access scoped to the domain (replaces es:ESHttp* on *)
        openSearchMasterRole.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "es:ESHttpGet",
                        "es:ESHttpPost",
                        "es:ESHttpPut",
                        "es:ESHttpHead",
                        "es:ESHttpDelete"))
                .resources(List.of(search.getDomain().getDomainArn() + "/*"))
                .build());

        // 4. Authorization: AVP policy store with Cedar schema and policies
        AuthorizationConstruct authorization = new AuthorizationConstruct(this, "Authorization");

        // 5. Cache: ElastiCache Redis in VPC private subnets
        CacheConstruct cache = new CacheConstruct(this, "Cache",
                networking.getVpc(),
                privateSubnets,
                networking.getRedisSecurityGroup());

        // 6. Compute: Lambda function with VPC networking and environment configuration
        ComputeConstruct compute = new ComputeConstruct(this, "Compute",
                networking.getVpc(),
                privateSubnets,
                networking.getLambdaSecurityGroup(),
                authorization.getPolicyStoreId(),
                search.getDomainEndpoint(),
                cache.getRedisEndpoint(),
                cache.getRedisPort(),
                search.getDomain().getDomainArn(),
                cache.getAuthTokenSecret());

        // 3b. Data Seeding: Seed sample data and configure security role mappings
        DataSeedingConstruct dataSeeding = new DataSeedingConstruct(this, "DataSeeding",
                networking.getVpc(),
                privateSubnets,
                networking.getLambdaSecurityGroup(),
                search.getDomainEndpoint(),
                compute.getFunction().getRole().getRoleArn(),
                openSearchMasterRole);

        // 7. API: API Gateway with Cognito authorizer and Lambda integration
        ApiConstruct api = new ApiConstruct(this, "Api",
                auth.getUserPool(),
                compute.getFunction());

        // --- CDK Outputs ---

        CfnOutput.Builder.create(this, "ApiEndpoint")
                .description("API Gateway endpoint URL")
                .value(api.getApiEndpoint())
                .build();

        CfnOutput.Builder.create(this, "CognitoUserPoolId")
                .description("Cognito User Pool ID")
                .value(auth.getUserPool().getUserPoolId())
                .build();

        CfnOutput.Builder.create(this, "CognitoClientId")
                .description("Cognito App Client ID")
                .value(auth.getUserPoolClient().getUserPoolClientId())
                .build();

        CfnOutput.Builder.create(this, "CognitoDomain")
                .description("Cognito Hosted UI domain URL")
                .value(auth.getUserPoolDomain().baseUrl())
                .build();

        CfnOutput.Builder.create(this, "OpenSearchEndpoint")
                .description("OpenSearch domain endpoint")
                .value(search.getDomainEndpoint())
                .build();

        CfnOutput.Builder.create(this, "PolicyStoreId")
                .description("AVP Policy Store ID")
                .value(authorization.getPolicyStoreId())
                .build();
    }
}
