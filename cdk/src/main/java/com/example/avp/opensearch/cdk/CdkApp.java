package com.example.avp.opensearch.cdk;

import java.util.List;

import io.github.cdklabs.cdknag.AwsSolutionsChecks;
import io.github.cdklabs.cdknag.NagPackProps;
import io.github.cdklabs.cdknag.NagPackSuppression;
import io.github.cdklabs.cdknag.NagSuppressions;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Aspects;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

/**
 * CDK application entry point for the AVP OpenSearch HR Portal.
 * <p>
 * This application deploys a complete HR Portal demo stack demonstrating
 * three-layer authorization with Amazon Verified Permissions and OpenSearch.
 */
public class CdkApp {

    public static void main(final String[] args) {
        App app = new App();

        HrPortalStack stack = new HrPortalStack(app, "HrPortalStack", StackProps.builder()
                .env(Environment.builder()
                        .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                        .region(System.getenv("CDK_DEFAULT_REGION"))
                        .build())
                .description("AVP OpenSearch HR Portal - Three-layer authorization demo")
                .build());

        // Apply CDK Nag AWS Solutions checks
        Aspects.of(app).add(new AwsSolutionsChecks(NagPackProps.builder()
                .verbose(true)
                .build()));

        // Suppress rules for CDK-managed resources that cannot be directly controlled
        NagSuppressions.addStackSuppressions(stack, List.of(
                // The CDK Custom Resource Provider framework Lambda runtime is managed by CDK
                NagPackSuppression.builder()
                        .id("AwsSolutions-L1")
                        .reason("CDK Custom Resource Provider framework Lambda runtime is managed by the CDK framework and cannot be directly controlled")
                        .build(),
                // The API Gateway CloudWatch role and CDK-auto-attached VPC/Basic execution policies
                NagPackSuppression.builder()
                        .id("AwsSolutions-IAM4")
                        .reason("AWS managed policies: AmazonAPIGatewayPushToCloudWatchLogs is required by API Gateway CloudWatch role; AWSLambdaBasicExecutionRole and AWSLambdaVPCAccessExecutionRole are auto-attached by CDK for VPC-deployed Lambdas and Custom Resource Providers")
                        .appliesTo(List.of(
                                "Policy::arn:<AWS::Partition>:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole",
                                "Policy::arn:<AWS::Partition>:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole",
                                "Policy::arn:<AWS::Partition>:iam::aws:policy/service-role/AmazonAPIGatewayPushToCloudWatchLogs"))
                        .build(),
                // CDK Custom Resource Provider framework uses wildcard for invoke
                NagPackSuppression.builder()
                        .id("AwsSolutions-IAM5")
                        .reason("CDK Custom Resource Provider framework requires wildcard permissions for Lambda invoke and log retention management")
                        .build(),
                // OpenSearch domain is deployed within a VPC; IP-based access policies are not applicable
                NagPackSuppression.builder()
                        .id("AwsSolutions-OS3")
                        .reason("OpenSearch domain is deployed within a VPC with security groups restricting access to Lambda only; IP allowlisting is not applicable for VPC-deployed domains")
                        .build()
        ), true);

        app.synth();
    }
}
