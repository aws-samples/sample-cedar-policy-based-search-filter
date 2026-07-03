package com.example.avp.opensearch.cdk;

import software.amazon.awscdk.services.verifiedpermissions.CfnPolicy;
import software.amazon.awscdk.services.verifiedpermissions.CfnPolicyStore;
import software.constructs.Construct;

/**
 * CDK construct that provisions Amazon Verified Permissions (AVP) resources.
 * <p>
 * Creates a policy store with a Cedar schema defining User, EntityType, and Field
 * entities with Search, View, and ViewField actions. Provisions all Cedar policies
 * for the three-layer authorization pattern:
 * <ul>
 *   <li>Layer 1: Coarse-grained entity-type access (one policy per role)</li>
 *   <li>Layer 3: Fine-grained context-based view policies</li>
 *   <li>Field Masking: Permit-then-forbid pattern for field-level visibility</li>
 * </ul>
 */
public class AuthorizationConstruct extends Construct {

    private final CfnPolicyStore policyStore;

    /**
     * The Cedar schema definition for the HRPortal namespace.
     */
    private static final String CEDAR_SCHEMA_JSON = """
            {
                "HRPortal": {
                    "entityTypes": {
                        "User": {
                            "shape": {
                                "type": "Record",
                                "attributes": {
                                    "role": { "type": "String", "required": true },
                                    "region": { "type": "String", "required": true },
                                    "department": { "type": "String", "required": true },
                                    "managerId": { "type": "String", "required": true }
                                }
                            }
                        },
                        "EntityType": {},
                        "Field": {
                            "memberOfTypes": ["EntityType"]
                        }
                    },
                    "actions": {
                        "Search": {
                            "appliesTo": {
                                "principalTypes": ["User"],
                                "resourceTypes": ["EntityType"],
                                "context": {
                                    "type": "Record",
                                    "attributes": {}
                                }
                            }
                        },
                        "View": {
                            "appliesTo": {
                                "principalTypes": ["User"],
                                "resourceTypes": ["EntityType"],
                                "context": {
                                    "type": "Record",
                                    "attributes": {
                                        "resourceRegion": { "type": "String", "required": true },
                                        "resourceDepartment": { "type": "String", "required": true },
                                        "resourceManagerId": { "type": "String", "required": true }
                                    }
                                }
                            }
                        },
                        "ViewField": {
                            "appliesTo": {
                                "principalTypes": ["User"],
                                "resourceTypes": ["Field"],
                                "context": {
                                    "type": "Record",
                                    "attributes": {}
                                }
                            }
                        }
                    }
                }
            }
            """;

    // Layer 1 Cedar Policies (Coarse-Grained Entity-Type Access)
    private static final String LAYER1_EMPLOYEE_POLICY = """
            permit (
                principal,
                action == HRPortal::Action::"Search",
                resource
            ) when {
                principal.role == "Employee" &&
                (resource == HRPortal::EntityType::"employees" || resource == HRPortal::EntityType::"teams")
            };
            """;

    private static final String LAYER1_US_HR_MANAGER_POLICY = """
            permit (
                principal,
                action == HRPortal::Action::"Search",
                resource
            ) when {
                principal.role == "USHRManager" &&
                (resource == HRPortal::EntityType::"employees" || resource == HRPortal::EntityType::"compensation")
            };
            """;

    private static final String LAYER1_GLOBAL_HR_ADMIN_POLICY = """
            permit (
                principal,
                action == HRPortal::Action::"Search",
                resource
            ) when {
                principal.role == "GlobalHRAdmin" &&
                (resource == HRPortal::EntityType::"employees" || resource == HRPortal::EntityType::"teams" ||
                 resource == HRPortal::EntityType::"job-postings" || resource == HRPortal::EntityType::"compensation")
            };
            """;

    private static final String LAYER1_RECRUITER_POLICY = """
            permit (
                principal,
                action == HRPortal::Action::"Search",
                resource
            ) when {
                principal.role == "Recruiter" &&
                (resource == HRPortal::EntityType::"job-postings" || resource == HRPortal::EntityType::"employees")
            };
            """;

    // Layer 3 Cedar Policies (Fine-Grained Context-Based View)
    private static final String LAYER3_US_HR_MANAGER_POLICY = """
            permit (
                principal,
                action == HRPortal::Action::"View",
                resource
            ) when {
                principal.role == "USHRManager" && context.resourceRegion == principal.region
            };
            """;

    private static final String LAYER3_GLOBAL_HR_ADMIN_POLICY = """
            permit (
                principal,
                action == HRPortal::Action::"View",
                resource
            ) when {
                principal.role == "GlobalHRAdmin"
            };
            """;

    private static final String LAYER3_EMPLOYEE_POLICY = """
            permit (
                principal,
                action == HRPortal::Action::"View",
                resource
            ) when {
                principal.role == "Employee"
            };
            """;

    private static final String LAYER3_RECRUITER_POLICY = """
            permit (
                principal,
                action == HRPortal::Action::"View",
                resource
            ) when {
                principal.role == "Recruiter"
            };
            """;

    // Field Masking Cedar Policies (Permit-then-Forbid)
    private static final String FIELD_MASKING_PERMIT_POLICY = """
            permit (
                principal,
                action == HRPortal::Action::"ViewField",
                resource
            );
            """;

    private static final String FIELD_MASKING_FORBID_RECRUITER_PHONE_POLICY = """
            forbid (
                principal,
                action == HRPortal::Action::"ViewField",
                resource == HRPortal::Field::"phoneNumber"
            ) when {
                principal.role == "Recruiter"
            };
            """;

    public AuthorizationConstruct(final Construct scope, final String id) {
        super(scope, id);

        // Create the AVP Policy Store with Cedar schema
        this.policyStore = CfnPolicyStore.Builder.create(this, "HrPortalPolicyStore")
                .validationSettings(CfnPolicyStore.ValidationSettingsProperty.builder()
                        .mode("STRICT")
                        .build())
                .schema(CfnPolicyStore.SchemaDefinitionProperty.builder()
                        .cedarJson(CEDAR_SCHEMA_JSON)
                        .build())
                .description("HR Portal AVP Policy Store for three-layer authorization")
                .build();

        // Create Layer 1 policies (Coarse-Grained Entity-Type Access)
        createStaticPolicy("Layer1EmployeePolicy", "Layer 1: Employee - Search employees, teams",
                LAYER1_EMPLOYEE_POLICY);
        createStaticPolicy("Layer1USHRManagerPolicy", "Layer 1: US HR Manager - Search employees, compensation",
                LAYER1_US_HR_MANAGER_POLICY);
        createStaticPolicy("Layer1GlobalHRAdminPolicy", "Layer 1: Global HR Admin - Search all entity types",
                LAYER1_GLOBAL_HR_ADMIN_POLICY);
        createStaticPolicy("Layer1RecruiterPolicy", "Layer 1: Recruiter - Search job-postings, employees",
                LAYER1_RECRUITER_POLICY);

        // Create Layer 3 policies (Fine-Grained Context-Based View)
        createStaticPolicy("Layer3USHRManagerPolicy", "Layer 3: US HR Manager - View when region matches",
                LAYER3_US_HR_MANAGER_POLICY);
        createStaticPolicy("Layer3GlobalHRAdminPolicy", "Layer 3: Global HR Admin - View all results",
                LAYER3_GLOBAL_HR_ADMIN_POLICY);
        createStaticPolicy("Layer3EmployeePolicy", "Layer 3: Employee - View all results in permitted types",
                LAYER3_EMPLOYEE_POLICY);
        createStaticPolicy("Layer3RecruiterPolicy", "Layer 3: Recruiter - View all results in permitted types",
                LAYER3_RECRUITER_POLICY);

        // Create Field Masking policies (Permit-then-Forbid)
        createStaticPolicy("FieldMaskingPermitPolicy", "Field Masking: Default permit all ViewField",
                FIELD_MASKING_PERMIT_POLICY);
        createStaticPolicy("FieldMaskingForbidRecruiterPhone",
                "Field Masking: Forbid Recruiter viewing phoneNumber",
                FIELD_MASKING_FORBID_RECRUITER_PHONE_POLICY);
    }

    /**
     * Creates a static Cedar policy in the policy store.
     *
     * @param logicalId   The CDK logical ID for the policy resource
     * @param description A human-readable description of the policy
     * @param statement   The Cedar policy statement
     */
    private void createStaticPolicy(String logicalId, String description, String statement) {
        CfnPolicy.Builder.create(this, logicalId)
                .policyStoreId(policyStore.getAttrPolicyStoreId())
                .definition(CfnPolicy.PolicyDefinitionProperty.builder()
                        .staticValue(CfnPolicy.StaticPolicyDefinitionProperty.builder()
                                .statement(statement)
                                .description(description)
                                .build())
                        .build())
                .build();
    }

    /**
     * Returns the policy store ID for use by Lambda functions.
     *
     * @return The AVP policy store ID
     */
    public String getPolicyStoreId() {
        return policyStore.getAttrPolicyStoreId();
    }
}
