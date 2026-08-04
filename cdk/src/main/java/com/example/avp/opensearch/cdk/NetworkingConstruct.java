package com.example.avp.opensearch.cdk;

import java.util.List;

import software.amazon.awscdk.services.ec2.FlowLog;
import software.amazon.awscdk.services.ec2.FlowLogDestination;
import software.amazon.awscdk.services.ec2.FlowLogTrafficType;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetConfiguration;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.constructs.Construct;

/**
 * CDK construct that provisions VPC networking infrastructure for the HR Portal.
 * <p>
 * Creates a VPC with public and private subnets, VPC Flow Logs, and security groups for
 * Lambda, OpenSearch, and Redis communication.
 */
public class NetworkingConstruct extends Construct {

    private final Vpc vpc;
    private final SecurityGroup lambdaSecurityGroup;
    private final SecurityGroup openSearchSecurityGroup;
    private final SecurityGroup redisSecurityGroup;

    public NetworkingConstruct(final Construct scope, final String id) {
        super(scope, id);

        // Create VPC with public and private subnets
        this.vpc = Vpc.Builder.create(this, "HrPortalVpc")
                .maxAzs(2)
                .subnetConfiguration(List.of(
                        SubnetConfiguration.builder()
                                .name("Public")
                                .subnetType(SubnetType.PUBLIC)
                                .cidrMask(24)
                                .build(),
                        SubnetConfiguration.builder()
                                .name("Private")
                                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                                .cidrMask(24)
                                .build()
                ))
                .build();

        // Add VPC Flow Logs to CloudWatch (AwsSolutions-VPC7)
        FlowLog.Builder.create(this, "VpcFlowLog")
                .resourceType(software.amazon.awscdk.services.ec2.FlowLogResourceType.fromVpc(this.vpc))
                .destination(FlowLogDestination.toCloudWatchLogs())
                .trafficType(FlowLogTrafficType.ALL)
                .build();

        // Security group for Lambda functions
        this.lambdaSecurityGroup = SecurityGroup.Builder.create(this, "LambdaSecurityGroup")
                .vpc(vpc)
                .description("Security group for Lambda functions")
                .allowAllOutbound(true)
                .build();

        // Security group for OpenSearch domain - allows inbound from Lambda on port 443
        this.openSearchSecurityGroup = SecurityGroup.Builder.create(this, "OpenSearchSecurityGroup")
                .vpc(vpc)
                .description("Security group for OpenSearch domain")
                .allowAllOutbound(false)
                .build();

        this.openSearchSecurityGroup.addIngressRule(
                Peer.securityGroupId(lambdaSecurityGroup.getSecurityGroupId()),
                Port.tcp(443),
                "Allow HTTPS from Lambda"
        );

        // Security group for Redis cluster - allows inbound from Lambda on port 6379
        this.redisSecurityGroup = SecurityGroup.Builder.create(this, "RedisSecurityGroup")
                .vpc(vpc)
                .description("Security group for Redis cluster")
                .allowAllOutbound(false)
                .build();

        this.redisSecurityGroup.addIngressRule(
                Peer.securityGroupId(lambdaSecurityGroup.getSecurityGroupId()),
                Port.tcp(6380),
                "Allow Redis from Lambda on non-default port"
        );
    }

    public Vpc getVpc() {
        return vpc;
    }

    public SecurityGroup getLambdaSecurityGroup() {
        return lambdaSecurityGroup;
    }

    public SecurityGroup getOpenSearchSecurityGroup() {
        return openSearchSecurityGroup;
    }

    public SecurityGroup getRedisSecurityGroup() {
        return redisSecurityGroup;
    }
}
