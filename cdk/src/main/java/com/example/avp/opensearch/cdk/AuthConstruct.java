package com.example.avp.opensearch.cdk;

import java.util.List;
import java.util.Map;

import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.cognito.AdvancedSecurityMode;
import software.amazon.awscdk.services.cognito.AutoVerifiedAttrs;
import software.amazon.awscdk.services.cognito.CfnUserPoolUser;
import software.amazon.awscdk.services.cognito.ClientAttributes;
import software.amazon.awscdk.services.cognito.CognitoDomainOptions;
import software.amazon.awscdk.services.cognito.OAuthFlows;
import software.amazon.awscdk.services.cognito.OAuthScope;
import software.amazon.awscdk.services.cognito.OAuthSettings;
import software.amazon.awscdk.services.cognito.PasswordPolicy;
import software.amazon.awscdk.services.cognito.SignInAliases;
import software.amazon.awscdk.services.cognito.StandardAttribute;
import software.amazon.awscdk.services.cognito.StandardAttributes;
import software.amazon.awscdk.services.cognito.StandardAttributesMask;
import software.amazon.awscdk.services.cognito.StringAttribute;
import software.amazon.awscdk.services.cognito.UserPool;
import software.amazon.awscdk.services.cognito.UserPoolClient;
import software.amazon.awscdk.services.cognito.UserPoolDomain;
import software.amazon.awscdk.services.cognito.UserPoolDomainOptions;
import software.constructs.Construct;

/**
 * CDK construct that provisions Amazon Cognito authentication infrastructure for the HR Portal.
 * <p>
 * Creates a Cognito User Pool with a custom "role" attribute, four predefined users
 * (Alice/Employee, Bob/USHRManager, Carol/GlobalHRAdmin, Dave/Recruiter) with temporary
 * passwords, and an app client configured for OAuth code grant flow with Hosted UI.
 */
public class AuthConstruct extends Construct {

    private final UserPool userPool;
    private final UserPoolClient userPoolClient;
    private final UserPoolDomain userPoolDomain;

    public AuthConstruct(final Construct scope, final String id) {
        super(scope, id);

        // Create Cognito User Pool with custom "role" attribute
        this.userPool = UserPool.Builder.create(this, "HrPortalUserPool")
                .userPoolName("hr-portal-user-pool")
                .selfSignUpEnabled(false)
                .signInAliases(SignInAliases.builder()
                        .username(true)
                        .email(true)
                        .build())
                .autoVerify(AutoVerifiedAttrs.builder()
                        .email(true)
                        .build())
                .standardAttributes(StandardAttributes.builder()
                        .email(StandardAttribute.builder()
                                .required(true)
                                .mutable(true)
                                .build())
                        .build())
                .customAttributes(Map.of(
                        "role", StringAttribute.Builder.create()
                                .mutable(true)
                                .maxLen(64)
                                .build()
                ))
                .passwordPolicy(PasswordPolicy.builder()
                        .minLength(8)
                        .requireLowercase(true)
                        .requireUppercase(true)
                        .requireDigits(true)
                        .requireSymbols(true)
                        .build())
                // AwsSolutions-COG3: Enable Advanced Security Mode
                .advancedSecurityMode(AdvancedSecurityMode.ENFORCED)
                // AwsSolutions-COG2: Require MFA
                .mfa(software.amazon.awscdk.services.cognito.Mfa.REQUIRED)
                .mfaSecondFactor(software.amazon.awscdk.services.cognito.MfaSecondFactor.builder()
                        .sms(true)
                        .otp(true)
                        .build())
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // Create Cognito Hosted UI domain
        this.userPoolDomain = userPool.addDomain("HrPortalDomain", UserPoolDomainOptions.builder()
                .cognitoDomain(CognitoDomainOptions.builder()
                        .domainPrefix("hr-portal-avp-demo")
                        .build())
                .build());

        // Create app client with OAuth code grant flow for Hosted UI
        this.userPoolClient = UserPoolClient.Builder.create(this, "HrPortalAppClient")
                .userPool(userPool)
                .userPoolClientName("hr-portal-app-client")
                .generateSecret(false)
                .oAuth(OAuthSettings.builder()
                        .flows(OAuthFlows.builder()
                                .authorizationCodeGrant(true)
                                .implicitCodeGrant(false)
                                .build())
                        .scopes(List.of(
                                OAuthScope.OPENID,
                                OAuthScope.EMAIL,
                                OAuthScope.PROFILE
                        ))
                        .callbackUrls(List.of("http://localhost:5173/", "http://localhost:5173/callback"))
                        .logoutUrls(List.of("http://localhost:5173/"))
                        .build())
                // Security: explicitly allowlist the attributes end users may write via
                // UpdateUserAttributes. Without this, Cognito lets users self-modify every
                // mutable attribute, including custom:role, which the authorization model
                // trusts. Roles can only be changed by administrators (AdminUpdateUserAttributes).
                .writeAttributes(new ClientAttributes()
                        .withStandardAttributes(StandardAttributesMask.builder()
                                .email(true)
                                .build()))
                .build();

        // Create predefined users with temporary passwords
        createUser("Alice", "alice@example.com", "Employee");
        createUser("Bob", "bob@example.com", "USHRManager");
        createUser("Carol", "carol@example.com", "GlobalHRAdmin");
        createUser("Dave", "dave@example.com", "Recruiter");
    }

    /**
     * Creates a Cognito user with a temporary password and custom role attribute.
     * <p>
     * Users are created in FORCE_CHANGE_PASSWORD status. The messageAction is set to
     * SUPPRESS so no welcome email is sent. For the demo, an admin can set passwords
     * via the AWS CLI or console after deployment.
     */
    private void createUser(String username, String email, String role) {
        CfnUserPoolUser.Builder.create(this, username + "User")
                .userPoolId(userPool.getUserPoolId())
                .username(username)
                .desiredDeliveryMediums(List.of("EMAIL"))
                .forceAliasCreation(false)
                .userAttributes(List.of(
                        CfnUserPoolUser.AttributeTypeProperty.builder()
                                .name("email")
                                .value(email)
                                .build(),
                        CfnUserPoolUser.AttributeTypeProperty.builder()
                                .name("email_verified")
                                .value("true")
                                .build(),
                        CfnUserPoolUser.AttributeTypeProperty.builder()
                                .name("custom:role")
                                .value(role)
                                .build()
                ))
                .messageAction("SUPPRESS")
                .build();
    }

    public UserPool getUserPool() {
        return userPool;
    }

    public UserPoolClient getUserPoolClient() {
        return userPoolClient;
    }

    public UserPoolDomain getUserPoolDomain() {
        return userPoolDomain;
    }
}
