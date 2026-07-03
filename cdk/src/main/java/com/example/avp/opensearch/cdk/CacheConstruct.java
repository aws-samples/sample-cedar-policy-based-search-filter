package com.example.avp.opensearch.cdk;

import java.util.List;

import software.amazon.awscdk.services.ec2.IVpc;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.elasticache.CfnCacheCluster;
import software.amazon.awscdk.services.elasticache.CfnSubnetGroup;
import software.constructs.Construct;

/**
 * CDK construct that provisions an ElastiCache for Redis cluster within the VPC.
 * <p>
 * Redis is used for caching authorization decisions with a 300-second TTL.
 * Key patterns:
 * <ul>
 *   <li>"l1:{userId}" for Layer 1 coarse-grained authorization cache</li>
 *   <li>"l3:{userId}:{entityType}:{resourceId}" for Layer 3 fine-grained authorization cache</li>
 * </ul>
 */
public class CacheConstruct extends Construct {

    private final CfnCacheCluster cacheCluster;

    /**
     * Creates a new CacheConstruct.
     *
     * @param scope           the parent construct
     * @param id              the construct ID
     * @param vpc             the VPC to place the Redis cluster in
     * @param subnetSelection the subnet selection for Redis (should be private subnets)
     * @param securityGroup   the security group allowing Lambda access on port 6379
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

        // Create the Redis cache cluster
        this.cacheCluster = CfnCacheCluster.Builder.create(this, "RedisCluster")
                .engine("redis")
                .cacheNodeType("cache.t3.micro")
                .numCacheNodes(1)
                .cacheSubnetGroupName(subnetGroup.getRef())
                .vpcSecurityGroupIds(List.of(securityGroup.getSecurityGroupId()))
                .build();

        // Ensure the cluster is created after the subnet group
        this.cacheCluster.addDependency(subnetGroup);
    }

    /**
     * Returns the Redis primary endpoint address for Lambda configuration.
     *
     * @return the Redis endpoint address (host:port format uses port 6379 by default)
     */
    public String getRedisEndpoint() {
        return cacheCluster.getAttrRedisEndpointAddress();
    }

    /**
     * Returns the Redis primary endpoint port.
     *
     * @return the Redis endpoint port
     */
    public String getRedisPort() {
        return cacheCluster.getAttrRedisEndpointPort();
    }
}
