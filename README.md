## Cedar policy based search filtering

This application is a demo HR Portal that demonstrates how to implement fine-grained, policy-based access control for enterprise search results using Amazon Verified Permissions (AVP) and Amazon OpenSearch Service. When organizations allow employees to search internal data (employee records, compensation, job postings, team information), they need to ensure each user only sees data they're authorized to access based on their role, region, and organizational context. This solution implements a three-layer authorization pattern: Layer 1 determines which categories of data a user can search at all, Layer 2 applies organizational filters (like region restrictions) directly into the search query, and Layer 3 individually validates each result against detailed Cedar policies before returning it to the user. A fourth layer handles field-level masking, replacing sensitive values like phone numbers or salaries with "****" for users who can see a record but not all its fields. The authorization logic is expressed as declarative Cedar policies in Amazon Verified Permissions, making access rules auditable and maintainable without code changes. The application uses Amazon Cognito for authentication with four predefined users representing distinct roles: Employee, US HR Manager, Global HR Admin, and Recruiter. Each role demonstrates different access patterns — the US HR Manager sees only US-region employees and compensation, while the Recruiter sees job postings and employee names but with phone numbers masked. The backend is a Java 21 Lambda function fronted by API Gateway, with ElastiCache Redis caching authorization decisions to reduce latency and AVP API calls. The frontend is a React single-page application that provides a unified search experience where results are transparently filtered based on the signed-in user's permissions. All infrastructure is provisioned via a single AWS CDK deployment, making the demo reproducible and easy to tear down. This pattern solves a common enterprise challenge: enabling broad search functionality while enforcing least-privilege access at the data level, without building custom authorization logic into every application.

## Architecture

```
React SPA → API Gateway (Cognito auth) → Lambda (Java 21) → OpenSearch
                                              ↕               ↕
                                      Verified Permissions   ElastiCache Redis
```

**Authorization Layers:**
1. **Layer 1 (Coarse):** Determines which entity types (indices) a user can search
2. **Layer 2 (Pre-Filter):** Applies organizational context as OpenSearch query filters
3. **Layer 3 (Fine-Grained):** Individually authorizes each candidate result
4. **Field Masking:** Replaces sensitive field values with `****` based on role

## Prerequisites

- Java 21 (JDK)
- Maven 3.8+
- Node.js 20+ and npm
- AWS CDK CLI (`npm install -g aws-cdk`)
- AWS CLI configured with credentials (`aws configure`)
- An AWS account with permissions for: VPC, Lambda, OpenSearch, Cognito, API Gateway, ElastiCache, Verified Permissions

## Project Structure

```
├── cdk/          # AWS CDK infrastructure (Java)
├── lambda/       # Search service Lambda (Java 21)
├── frontend/     # React + TypeScript SPA (Vite)
├── cdk.json      # CDK app configuration
└── pom.xml       # Parent Maven POM
```

## Build

### 1. Build the Java modules (CDK + Lambda)

```bash
mvn clean package -DskipTests
```

This produces:
- `lambda/target/lambda-1.0.0-SNAPSHOT.jar` — the Lambda deployment artifact (fat JAR)
- Compiled CDK classes

### 2. Install frontend dependencies

```bash
cd frontend
npm install
cd ..
```

### 3. Install data seeding Lambda dependencies

```bash
cd cdk/src/main/resources/data-seeding-lambda
npm install
cd ../../../../..
```

## Deploy

### 1. Bootstrap CDK (first time only)

```bash
cdk bootstrap
```

### 2. Synthesize the CloudFormation template

```bash
cdk synth
```

### 3. Deploy the stack

```bash
cdk deploy
```

This provisions all resources in a single deployment:
- VPC with public/private subnets
- Cognito User Pool with 4 predefined users
- OpenSearch domain with sample data (auto-seeded)
- AVP policy store with Cedar policies
- ElastiCache Redis cluster
- Lambda function (Search Service)
- API Gateway with Cognito authorizer

### 4. Note the CDK outputs

After deployment, note these outputs:
- `ApiEndpoint` — the API Gateway URL
- `CognitoUserPoolId` — User Pool ID
- `CognitoClientId` — App Client ID
- `CognitoDomain` — Hosted UI domain

### 5. Configure the frontend

Create `frontend/.env` using the CDK outputs:

```env
VITE_API_BASE_URL=<ApiEndpoint>
VITE_COGNITO_USER_POOL_ID=<CognitoUserPoolId>
VITE_COGNITO_CLIENT_ID=<CognitoClientId>
VITE_COGNITO_DOMAIN=<CognitoDomain>
VITE_REDIRECT_SIGN_IN=http://localhost:5173/
VITE_REDIRECT_SIGN_OUT=http://localhost:5173/
VITE_AWS_REGION=us-east-1
```

### 6. Set user passwords

The predefined users are created with `FORCE_CHANGE_PASSWORD` status. Set permanent passwords via the AWS CLI:

```bash
aws cognito-idp admin-set-user-password \
  --user-pool-id <CognitoUserPoolId> \
  --username Alice \
  --password "TempPass1!" \
  --permanent

aws cognito-idp admin-set-user-password \
  --user-pool-id <CognitoUserPoolId> \
  --username Bob \
  --password "TempPass1!" \
  --permanent

aws cognito-idp admin-set-user-password \
  --user-pool-id <CognitoUserPoolId> \
  --username Carol \
  --password "TempPass1!" \
  --permanent

aws cognito-idp admin-set-user-password \
  --user-pool-id <CognitoUserPoolId> \
  --username Dave \
  --password "TempPass1!" \
  --permanent
```

## Run the Frontend

```bash
cd frontend
npm run dev
```

Open http://localhost:5173 and sign in with one of the demo users.

## Demo Users

| User  | Role            | Access                                              |
|-------|-----------------|-----------------------------------------------------|
| Alice | Employee        | Employees + Teams (all regions, no field masking)   |
| Bob   | US HR Manager   | Employees + Compensation (US region only)           |
| Carol | Global HR Admin | All entity types, all regions, no masking           |
| Dave  | Recruiter       | Job Postings + Employees (phone number masked)      |

## Run Tests

### Lambda unit tests (Java)

```bash
mvn test -pl lambda
```

This runs all unit tests including:
- Service layer tests (CoarseAuthorization, PreFilter, FineGrained, FieldMasking)
- OpenSearch client tests
- Authorization context tests

### Lambda property-based tests (jqwik)

```bash
mvn test -pl lambda -Dtest="*Properties"
```

### Frontend lint

```bash
cd frontend
npm run lint
```

### Frontend build verification

```bash
cd frontend
npm run build
```

## Verify CDK Synthesis

```bash
cdk synth
```

Confirms the CloudFormation template generates without errors.

## Teardown

```bash
cdk destroy
```

This removes all provisioned resources. The OpenSearch domain has `RemovalPolicy.DESTROY` so it will be deleted cleanly.

## Key Design Decisions

- **Fail-closed authorization:** All layers return empty results on error rather than potentially leaking data
- **Redis caching:** Authorization decisions are cached for 5 minutes to reduce AVP API calls
- **Batch processing:** Layer 3 batches up to 30 authorization requests per AVP call
- **Field masking optimization:** AVP is called once per entity type (not per result) since field decisions depend only on role
- **Cedar permit-then-forbid:** Default permit on all fields with explicit forbid for specific role/field combinations

## Troubleshooting

**CDK synth fails:** Ensure `lambda/target/lambda-1.0.0-SNAPSHOT.jar` exists by running `mvn package -pl lambda -DskipTests` first.

**OpenSearch timeout on first query:** The domain can take 10-15 minutes to become fully active after deployment. Wait and retry.

**Cognito login redirect fails:** Verify the callback URL in the Cognito app client matches your frontend URL (default: `http://localhost:5173/`).

**Redis connection error in Lambda:** Ensure the Lambda security group has outbound access and the Redis security group allows inbound on port 6379 from the Lambda SG.

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This library is licensed under the MIT-0 License. See the LICENSE file.

