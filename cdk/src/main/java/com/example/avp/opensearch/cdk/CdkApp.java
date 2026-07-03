package com.example.avp.opensearch.cdk;

import software.amazon.awscdk.App;
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

        new HrPortalStack(app, "HrPortalStack", StackProps.builder()
                .env(Environment.builder()
                        .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                        .region(System.getenv("CDK_DEFAULT_REGION"))
                        .build())
                .description("AVP OpenSearch HR Portal - Three-layer authorization demo")
                .build());

        app.synth();
    }
}
