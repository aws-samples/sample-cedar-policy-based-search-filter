package com.example.avp.opensearch.cdk;

import java.util.List;

import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.ec2.ISecurityGroup;
import software.amazon.awscdk.services.ec2.IVpc;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.opensearchservice.AdvancedSecurityOptions;
import software.amazon.awscdk.services.opensearchservice.CapacityConfig;
import software.amazon.awscdk.services.opensearchservice.Domain;
import software.amazon.awscdk.services.opensearchservice.EbsOptions;
import software.amazon.awscdk.services.opensearchservice.EncryptionAtRestOptions;
import software.amazon.awscdk.services.opensearchservice.EngineVersion;
import software.amazon.awscdk.services.opensearchservice.LoggingOptions;
import software.amazon.awscdk.services.opensearchservice.TLSSecurityPolicy;
import software.amazon.awscdk.services.opensearchservice.ZoneAwarenessConfig;
import software.constructs.Construct;

/**
 * CDK construct that provisions an Amazon OpenSearch Service domain for the HR Portal.
 * <p>
 * The domain is deployed within the VPC's private subnets and configured with
 * fine-grained access control to support index-level permissions required by the
 * three-layer authorization pattern.
 * <p>
 * The domain hosts four indices:
 * <ul>
 *   <li>index-employees</li>
 *   <li>index-teams</li>
 *   <li>index-job-postings</li>
 *   <li>index-compensation</li>
 * </ul>
 */
public class SearchConstruct extends Construct {

    private final Domain domain;

    /**
     * Creates the OpenSearch domain within the provided VPC.
     *
     * @param scope           the parent construct
     * @param id              the construct identifier
     * @param vpc             the VPC to deploy the domain into
     * @param subnetSelection the subnet selection for domain placement (private subnets)
     * @param securityGroup   the security group allowing Lambda access on port 443
     * @param masterUserRoleArn the ARN of the IAM role for fine-grained access master user
     */
    public SearchConstruct(final Construct scope, final String id,
                           final IVpc vpc,
                           final SubnetSelection subnetSelection,
                           final ISecurityGroup securityGroup,
                           final String masterUserRoleArn) {
        super(scope, id);

        // Create CloudWatch Log Groups for OpenSearch slow logs (AwsSolutions-OS9)
        LogGroup searchSlowLogGroup = LogGroup.Builder.create(this, "SearchSlowLogGroup")
                .logGroupName("/aws/opensearch/hr-portal/search-slow-logs")
                .retention(RetentionDays.ONE_WEEK)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        LogGroup indexSlowLogGroup = LogGroup.Builder.create(this, "IndexSlowLogGroup")
                .logGroupName("/aws/opensearch/hr-portal/index-slow-logs")
                .retention(RetentionDays.ONE_WEEK)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        this.domain = Domain.Builder.create(this, "HrPortalOpenSearch")
                .version(EngineVersion.OPENSEARCH_2_11)
                .domainName("hr-portal-search")
                // Place in VPC with private subnets across 2 AZs for zone awareness
                .vpc(vpc)
                .vpcSubnets(List.of(SubnetSelection.builder()
                        .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                        .build()))
                .securityGroups(List.of(securityGroup))
                // AwsSolutions-OS4: Use dedicated master nodes
                // AwsSolutions-OS7: Enable zone awareness across 2 AZs
                .capacity(CapacityConfig.builder()
                        .dataNodeInstanceType("t3.small.search")
                        .dataNodes(2)
                        .masterNodeInstanceType("t3.small.search")
                        .masterNodes(3)
                        .multiAzWithStandbyEnabled(false)
                        .build())
                // EBS storage for the data nodes
                .ebs(EbsOptions.builder()
                        .volumeSize(10)
                        .enabled(true)
                        .build())
                // Fine-grained access control with IAM role as master user
                .fineGrainedAccessControl(AdvancedSecurityOptions.builder()
                        .masterUserArn(masterUserRoleArn)
                        .build())
                // Node-to-node encryption required for fine-grained access control
                .nodeToNodeEncryption(true)
                // Encryption at rest required for fine-grained access control
                .encryptionAtRest(EncryptionAtRestOptions.builder()
                        .enabled(true)
                        .build())
                // Enforce HTTPS (AwsSolutions-OS5: no unsigned/anonymous access via fine-grained access control)
                .enforceHttps(true)
                // Use TLS 1.2 security policy
                .tlsSecurityPolicy(TLSSecurityPolicy.TLS_1_2_PFS)
                // AwsSolutions-OS7: Enable zone awareness
                .zoneAwareness(ZoneAwarenessConfig.builder()
                        .enabled(true)
                        .availabilityZoneCount(2)
                        .build())
                // AwsSolutions-OS9: Publish slow logs to CloudWatch
                .logging(LoggingOptions.builder()
                        .slowSearchLogEnabled(true)
                        .slowSearchLogGroup(searchSlowLogGroup)
                        .slowIndexLogEnabled(true)
                        .slowIndexLogGroup(indexSlowLogGroup)
                        .build())
                // Remove on stack deletion for demo purposes
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // AwsSolutions-OS3/OS5: Use IAM-based access policy restricted to the master role
        // Fine-grained access control handles authorization; restrict to specific principal
        this.domain.addAccessPolicies(
                software.amazon.awscdk.services.iam.PolicyStatement.Builder.create()
                        .effect(software.amazon.awscdk.services.iam.Effect.ALLOW)
                        .principals(List.of(
                                new software.amazon.awscdk.services.iam.ArnPrincipal(masterUserRoleArn)))
                        .actions(List.of("es:ESHttp*"))
                        .resources(List.of(this.domain.getDomainArn() + "/*"))
                        .build());
    }

    /**
     * Returns the OpenSearch domain.
     *
     * @return the provisioned OpenSearch domain
     */
    public Domain getDomain() {
        return domain;
    }

    /**
     * Returns the OpenSearch domain endpoint (HTTPS URL).
     *
     * @return the domain endpoint URL prefixed with https://
     */
    public String getDomainEndpoint() {
        return "https://" + domain.getDomainEndpoint();
    }
}
