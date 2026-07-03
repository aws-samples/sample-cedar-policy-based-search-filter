package com.example.avp.opensearch.cdk;

import java.util.List;

import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.ec2.ISecurityGroup;
import software.amazon.awscdk.services.ec2.IVpc;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.opensearchservice.AdvancedSecurityOptions;
import software.amazon.awscdk.services.opensearchservice.CapacityConfig;
import software.amazon.awscdk.services.opensearchservice.Domain;
import software.amazon.awscdk.services.opensearchservice.EbsOptions;
import software.amazon.awscdk.services.opensearchservice.EncryptionAtRestOptions;
import software.amazon.awscdk.services.opensearchservice.EngineVersion;
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
     */
    public SearchConstruct(final Construct scope, final String id,
                           final IVpc vpc,
                           final SubnetSelection subnetSelection,
                           final ISecurityGroup securityGroup,
                           final String masterUserRoleArn) {
        super(scope, id);

        // For a single-node domain, we need exactly one subnet
        // Select the first private subnet directly
        String singleSubnetId = vpc.selectSubnets(SubnetSelection.builder()
                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                .build()).getSubnetIds().get(0);

        this.domain = Domain.Builder.create(this, "HrPortalOpenSearch")
                .version(EngineVersion.OPENSEARCH_2_11)
                .domainName("hr-portal-search")
                // Place in VPC with a single private subnet (single-node requires exactly one)
                .vpc(vpc)
                .vpcSubnets(List.of(SubnetSelection.builder()
                        .subnets(List.of(
                                vpc.selectSubnets(SubnetSelection.builder()
                                        .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                                        .build()).getSubnets().get(0)))
                        .build()))
                .securityGroups(List.of(securityGroup))
                // Use a small instance type suitable for demo
                .capacity(CapacityConfig.builder()
                        .dataNodeInstanceType("t3.small.search")
                        .dataNodes(1)
                        .multiAzWithStandbyEnabled(false)
                        .build())
                // EBS storage for the data node
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
                // Enforce HTTPS
                .enforceHttps(true)
                // Use TLS 1.2 security policy (TLS 1.0 policies are disabled)
                .tlsSecurityPolicy(TLSSecurityPolicy.TLS_1_2_PFS)
                // Single-AZ for demo (cost optimization)
                .zoneAwareness(ZoneAwarenessConfig.builder()
                        .enabled(false)
                        .build())
                // Remove on stack deletion for demo purposes
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // Grant open access policy on the domain (fine-grained access control handles authorization)
        this.domain.addAccessPolicies(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .principals(List.of(new software.amazon.awscdk.services.iam.AnyPrincipal()))
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
