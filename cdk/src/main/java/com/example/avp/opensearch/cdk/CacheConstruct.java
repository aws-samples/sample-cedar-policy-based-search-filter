package com.example.avp.opensearch.cdk;

import java.util.List;

import software.amazon.awscdk.services.ec2.IVpc;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.elasticache.CfnReplicationGroup;
import software.amazon.awscdk.services.elasticache.CfnSubnetGroup;
import software.constructs.Construct;

/**
 * CDK construct that provisions an ElastiCache for Redis cluster within the VPC.
 * <p>
 * Redis is used for caching authorization decisions with a 300-second TTL.
 * Configured with encryption at rest, encryption in transit, Redis AUTH,
 * Multi-AZ, and a non-default port for security compliance.
 * Key patterns:
 * <ul>
 *   <li>"l1:{userId}" for Layer 1 coarse-grained authorization cache</li>
 *   <li>"l3:{userId}:{entityType}:{resourceId}" for Layer 3 fine-grained authorization cache</li>
 * </ul>
 */
public class CacheConstruct extends Construct {

    private final CfnReplicationGroup replicationGroup;
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
                // AwsSolutions-AEC6: Enable Redis AUTH for user authentication
                .authToken("HrPortalRedisAuth2024!SecureToken")
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
}
