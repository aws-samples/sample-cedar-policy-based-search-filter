package com.example.avp.opensearch.cdk;

import java.util.List;

import io.github.cdklabs.cdknag.NagPackSuppression;
import io.github.cdklabs.cdknag.NagSuppressions;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.ec2.IVpc;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.elasticache.CfnReplicationGroup;
import software.amazon.awscdk.services.elasticache.CfnSubnetGroup;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.services.secretsmanager.SecretStringGenerator;
import software.constructs.Construct;

/**
 * CDK construct that provisions an ElastiCache for Redis cluster within the VPC.
 * <p>
 * Redis is used for caching authorization decisions with a 300-second TTL.
 * Configured with encryption at rest, encryption in transit, Redis AUTH
 * (token stored in Secrets Manager), Multi-AZ, and a non-default port for
 * security compliance.
 * Key patterns:
 * <ul>
 *   <li>"l1:{userId}" for Layer 1 coarse-grained authorization cache</li>
 *   <li>"l3:{userId}:{entityType}:{resourceId}" for Layer 3 fine-grained authorization cache</li>
 * </ul>
 */
public class CacheConstruct extends Construct {

    private final CfnReplicationGroup replicationGroup;
    private final Secret authTokenSecret;
    private static final String REDIS_PORT = "6380";

    /**
     * Creates a new CacheConstruct.
     *
     * @param scope           the parent construct
     * @param id              the construct ID
     * @param vpc             the VPC to place the Redis cluster in
     * @param subnetSelection the subnet selection for Redis (should be private subnets)
     * @param securityGroup   the security group allowing Lambda access on port 6380
     */
    public CacheConstruct(final Construct scope, final String id,
                          final IVpc vpc,
                          final SubnetSelection subnetSelection,
                          final SecurityGroup securityGroup) {
        super(scope, id);

        // Create a Secrets Manager secret for the Redis AUTH token
        this.authTokenSecret = Secret.Builder.create(this, "RedisAuthToken")
                .secretName("hr-portal/redis-auth-token")
                .description("Redis AUTH token for HR Portal ElastiCache cluster")
                .generateSecretString(SecretStringGenerator.builder()
                        .excludePunctuation(false)
                        .includeSpace(false)
                        .passwordLength(32)
                        // ElastiCache AUTH tokens only allow printable ASCII excluding: /, @, ", and space
                        .excludeCharacters("/@\" ")
                        .build())
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // Suppress AwsSolutions-SMG4: Auto-rotation for Redis AUTH tokens requires a custom
        // rotation Lambda with VPC access to atomically update both the secret and the cluster.
        // Recommended for production but not implemented in this demo.
        NagSuppressions.addResourceSuppressions(this.authTokenSecret, List.of(
                NagPackSuppression.builder()
                        .id("AwsSolutions-SMG4")
                        .reason("Redis AUTH token auto-rotation requires a custom rotation Lambda with VPC/ElastiCache access; not implemented in this demo")
                        .build()
        ));

        // Create subnet group for Redis using the selected subnets
        List<String> subnetIds = vpc.selectSubnets(subnetSelection).getSubnetIds();

        CfnSubnetGroup subnetGroup = CfnSubnetGroup.Builder.create(this, "RedisSubnetGroup")
                .description("Subnet group for HR Portal Redis cluster")
                .subnetIds(subnetIds)
                .build();

        // Create the Redis replication group with full security compliance
        this.replicationGroup = CfnReplicationGroup.Builder.create(this, "RedisReplicationGroup")
                .replicationGroupDescription("HR Portal Redis cache with encryption")
                .engine("redis")
                .cacheNodeType("cache.t3.micro")
                // AwsSolutions-AEC4: Multi-AZ configuration for high availability
                .numCacheClusters(2)
                .multiAzEnabled(true)
                .automaticFailoverEnabled(true)
                .cacheSubnetGroupName(subnetGroup.getRef())
                .securityGroupIds(List.of(securityGroup.getSecurityGroupId()))
                // Enable encryption at rest
                .atRestEncryptionEnabled(true)
                // Enable encryption in transit
                .transitEncryptionEnabled(true)
                // AwsSolutions-AEC6: Enable Redis AUTH with token from Secrets Manager
                .authToken(authTokenSecret.getSecretValue().unsafeUnwrap())
                // AwsSolutions-AEC5: Use non-default port
                .port(Integer.parseInt(REDIS_PORT))
                .build();

        // Ensure the replication group is created after the subnet group
        this.replicationGroup.addDependency(subnetGroup);
    }

    /**
     * Returns the Redis primary endpoint address for Lambda configuration.
     *
     * @return the Redis endpoint address
     */
    public String getRedisEndpoint() {
        return replicationGroup.getAttrPrimaryEndPointAddress();
    }

    /**
     * Returns the Redis primary endpoint port.
     *
     * @return the Redis endpoint port
     */
    public String getRedisPort() {
        return REDIS_PORT;
    }

    /**
     * Returns the Secrets Manager secret containing the Redis AUTH token.
     * Used to grant read access to the Lambda function and pass the secret ARN.
     *
     * @return the Redis AUTH token secret
     */
    public Secret getAuthTokenSecret() {
        return authTokenSecret;
    }
}
