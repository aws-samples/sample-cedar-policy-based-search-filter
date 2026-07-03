/**
 * OpenSearch Data Seeding Lambda
 *
 * CDK custom resource handler that creates indices and seeds sample data
 * into the OpenSearch domain after deployment.
 *
 * Uses SigV4 signing for OpenSearch access within VPC.
 */

const { SignatureV4 } = require("@smithy/signature-v4");
const { Sha256 } = require("@aws-crypto/sha256-js");
const { defaultProvider } = require("@aws-sdk/credential-provider-node");
const { NodeHttpHandler } = require("@smithy/node-http-handler");
const { HttpRequest } = require("@smithy/protocol-http");

const REGION = process.env.AWS_REGION || "us-east-1";
const OPENSEARCH_ENDPOINT = process.env.OPENSEARCH_ENDPOINT;

// --- Index Mappings ---

const INDEX_MAPPINGS = {
  "index-employees": {
    mappings: {
      properties: {
        employeeId: { type: "keyword" },
        name: { type: "text" },
        region: { type: "keyword" },
        department: { type: "keyword" },
        managerId: { type: "keyword" },
        phoneNumber: { type: "keyword" },
        title: { type: "text" },
        email: { type: "keyword" },
      },
    },
  },
  "index-teams": {
    mappings: {
      properties: {
        teamId: { type: "keyword" },
        name: { type: "text" },
        region: { type: "keyword" },
        department: { type: "keyword" },
        members: { type: "keyword" },
        lead: { type: "keyword" },
      },
    },
  },
  "index-job-postings": {
    mappings: {
      properties: {
        postingId: { type: "keyword" },
        title: { type: "text" },
        department: { type: "keyword" },
        region: { type: "keyword" },
        description: { type: "text" },
        status: { type: "keyword" },
      },
    },
  },
  "index-compensation": {
    mappings: {
      properties: {
        compensationId: { type: "keyword" },
        employeeId: { type: "keyword" },
        region: { type: "keyword" },
        salary: { type: "float" },
        bonus: { type: "float" },
        currency: { type: "keyword" },
      },
    },
  },
};

// --- Sample Data ---

const EMPLOYEES = [
  {
    employeeId: "emp-001",
    name: "John Smith",
    region: "US",
    department: "Engineering",
    managerId: "mgr-001",
    phoneNumber: "+1-555-0101",
    title: "Software Engineer",
    email: "john.smith@example.com",
  },
  {
    employeeId: "emp-002",
    name: "Jane Doe",
    region: "US",
    department: "HR",
    managerId: "mgr-002",
    phoneNumber: "+1-555-0102",
    title: "HR Specialist",
    email: "jane.doe@example.com",
  },
  {
    employeeId: "emp-003",
    name: "Mike Johnson",
    region: "US",
    department: "Engineering",
    managerId: "mgr-001",
    phoneNumber: "+1-555-0103",
    title: "Senior Developer",
    email: "mike.johnson@example.com",
  },
  {
    employeeId: "emp-004",
    name: "Anna Mueller",
    region: "EU",
    department: "Engineering",
    managerId: "mgr-003",
    phoneNumber: "+49-555-0201",
    title: "DevOps Engineer",
    email: "anna.mueller@example.com",
  },
  {
    employeeId: "emp-005",
    name: "Pierre Dupont",
    region: "EU",
    department: "HR",
    managerId: "mgr-004",
    phoneNumber: "+33-555-0301",
    title: "HR Director",
    email: "pierre.dupont@example.com",
  },
  {
    employeeId: "emp-006",
    name: "Maria Garcia",
    region: "EU",
    department: "Marketing",
    managerId: "mgr-004",
    phoneNumber: "+34-555-0401",
    title: "Marketing Manager",
    email: "maria.garcia@example.com",
  },
];

const TEAMS = [
  {
    teamId: "team-001",
    name: "Platform Team",
    region: "US",
    department: "Engineering",
    members: ["emp-001", "emp-003"],
    lead: "emp-003",
  },
  {
    teamId: "team-002",
    name: "EU Engineering",
    region: "EU",
    department: "Engineering",
    members: ["emp-004"],
    lead: "emp-004",
  },
  {
    teamId: "team-003",
    name: "HR Operations",
    region: "US",
    department: "HR",
    members: ["emp-002"],
    lead: "emp-002",
  },
];

const JOB_POSTINGS = [
  {
    postingId: "job-001",
    title: "Senior Java Developer",
    department: "Engineering",
    region: "US",
    description: "Looking for an experienced Java developer to join our platform team.",
    status: "Open",
  },
  {
    postingId: "job-002",
    title: "Cloud Architect",
    department: "Engineering",
    region: "EU",
    description: "Seeking a cloud architect to design scalable infrastructure solutions.",
    status: "Open",
  },
  {
    postingId: "job-003",
    title: "HR Business Partner",
    department: "HR",
    region: "US",
    description: "HR Business Partner to support our growing engineering organization.",
    status: "Closed",
  },
];

const COMPENSATION = [
  {
    compensationId: "comp-001",
    employeeId: "emp-001",
    region: "US",
    salary: 150000,
    bonus: 15000,
    currency: "USD",
  },
  {
    compensationId: "comp-002",
    employeeId: "emp-002",
    region: "US",
    salary: 120000,
    bonus: 12000,
    currency: "USD",
  },
  {
    compensationId: "comp-003",
    employeeId: "emp-004",
    region: "EU",
    salary: 85000,
    bonus: 8500,
    currency: "EUR",
  },
  {
    compensationId: "comp-004",
    employeeId: "emp-005",
    region: "EU",
    salary: 95000,
    bonus: 9500,
    currency: "EUR",
  },
];

// --- Helper Functions ---

/**
 * Signs and sends an HTTP request to OpenSearch using SigV4.
 */
async function signedRequest(method, path, body) {
  const url = new URL(path, OPENSEARCH_ENDPOINT);

  const request = new HttpRequest({
    method,
    hostname: url.hostname,
    path: url.pathname,
    headers: {
      "Content-Type": "application/json",
      host: url.hostname,
    },
    body: body ? JSON.stringify(body) : undefined,
  });

  const signer = new SignatureV4({
    credentials: defaultProvider(),
    region: REGION,
    service: "es",
    sha256: Sha256,
  });

  const signedRequest = await signer.sign(request);

  // Use native https module for the request
  const https = require("https");
  return new Promise((resolve, reject) => {
    const options = {
      hostname: url.hostname,
      port: 443,
      path: url.pathname,
      method,
      headers: signedRequest.headers,
    };

    const req = https.request(options, (res) => {
      let data = "";
      res.on("data", (chunk) => (data += chunk));
      res.on("end", () => {
        resolve({ statusCode: res.statusCode, body: data });
      });
    });

    req.on("error", reject);
    if (body) {
      req.write(JSON.stringify(body));
    }
    req.end();
  });
}

/**
 * Creates an index with the given mappings if it doesn't exist.
 */
async function createIndex(indexName, mappings) {
  console.log(`Creating index: ${indexName}`);

  // Check if index exists
  const checkResult = await signedRequest("HEAD", `/${indexName}`);
  if (checkResult.statusCode === 200) {
    console.log(`Index ${indexName} already exists, deleting for fresh seed...`);
    await signedRequest("DELETE", `/${indexName}`);
  }

  // Create index with mappings
  const result = await signedRequest("PUT", `/${indexName}`, mappings);
  if (result.statusCode >= 200 && result.statusCode < 300) {
    console.log(`Index ${indexName} created successfully`);
  } else {
    console.error(`Failed to create index ${indexName}: ${result.statusCode} ${result.body}`);
    throw new Error(`Failed to create index ${indexName}: ${result.statusCode}`);
  }
}

/**
 * Bulk-indexes documents into an index.
 */
async function bulkIndex(indexName, documents, idField) {
  console.log(`Bulk indexing ${documents.length} documents into ${indexName}`);

  // Build NDJSON bulk request body
  let bulkBody = "";
  for (const doc of documents) {
    const action = { index: { _index: indexName, _id: doc[idField] } };
    bulkBody += JSON.stringify(action) + "\n";
    bulkBody += JSON.stringify(doc) + "\n";
  }

  const url = new URL(`/_bulk`, OPENSEARCH_ENDPOINT);
  const request = new HttpRequest({
    method: "POST",
    hostname: url.hostname,
    path: url.pathname,
    headers: {
      "Content-Type": "application/x-ndjson",
      host: url.hostname,
    },
    body: bulkBody,
  });

  const signer = new SignatureV4({
    credentials: defaultProvider(),
    region: REGION,
    service: "es",
    sha256: Sha256,
  });

  const signed = await signer.sign(request);

  const https = require("https");
  const result = await new Promise((resolve, reject) => {
    const options = {
      hostname: url.hostname,
      port: 443,
      path: url.pathname,
      method: "POST",
      headers: signed.headers,
    };

    const req = https.request(options, (res) => {
      let data = "";
      res.on("data", (chunk) => (data += chunk));
      res.on("end", () => {
        resolve({ statusCode: res.statusCode, body: data });
      });
    });

    req.on("error", reject);
    req.write(bulkBody);
    req.end();
  });

  if (result.statusCode >= 200 && result.statusCode < 300) {
    const responseBody = JSON.parse(result.body);
    if (responseBody.errors) {
      console.error(`Bulk indexing errors for ${indexName}:`, JSON.stringify(responseBody.items.filter(i => i.index.error)));
      throw new Error(`Bulk indexing had errors for ${indexName}`);
    }
    console.log(`Successfully indexed ${documents.length} documents into ${indexName}`);
  } else {
    console.error(`Bulk index failed for ${indexName}: ${result.statusCode} ${result.body}`);
    throw new Error(`Bulk index failed for ${indexName}: ${result.statusCode}`);
  }
}

const SEARCH_LAMBDA_ROLE_ARN = process.env.SEARCH_LAMBDA_ROLE_ARN;

/**
 * Maps IAM roles to the all_access internal role
 * in OpenSearch's fine-grained access control.
 */
async function mapRolesToAllAccess() {
  // Get the current Lambda's execution role ARN from the environment
  const currentRoleArn = process.env.AWS_LAMBDA_FUNCTION_NAME 
    ? `arn:aws:iam::${process.env.AWS_ACCOUNT_ID || ''}:role/*` 
    : '';

  const rolesToMap = [];
  if (SEARCH_LAMBDA_ROLE_ARN) {
    rolesToMap.push(SEARCH_LAMBDA_ROLE_ARN);
  }

  // Also map the seeding Lambda's own role (extracted from STS)
  try {
    const { STSClient, GetCallerIdentityCommand } = require("@aws-sdk/client-sts");
    const stsClient = new STSClient({ region: REGION });
    const identity = await stsClient.send(new GetCallerIdentityCommand({}));
    const callerArn = identity.Arn;
    // Extract role ARN from assumed-role ARN: arn:aws:sts::ACCOUNT:assumed-role/ROLE_NAME/SESSION
    const match = callerArn.match(/arn:aws:sts::(\d+):assumed-role\/([^/]+)/);
    if (match) {
      const seedingRoleArn = `arn:aws:iam::${match[1]}:role/${match[2]}`;
      rolesToMap.push(seedingRoleArn);
      console.log(`Seeding Lambda role: ${seedingRoleArn}`);
    }
  } catch (e) {
    console.log("Could not determine seeding Lambda role:", e.message);
  }

  if (rolesToMap.length === 0) {
    console.log("No roles to map, skipping");
    return;
  }

  console.log(`Mapping roles to all_access: ${JSON.stringify(rolesToMap)}`);

  // Try PUT to create/replace the role mapping
  const createBody = {
    backend_roles: rolesToMap
  };
  const createResult = await signedRequest("PUT", "/_plugins/_security/api/rolesmapping/all_access", createBody);
  
  if (createResult.statusCode >= 200 && createResult.statusCode < 300) {
    console.log("Successfully created/updated all_access role mapping");
  } else {
    console.error(`Failed to create role mapping: ${createResult.statusCode} ${createResult.body}`);
    // Try PATCH as fallback
    const patchBody = [{ op: "replace", path: "/backend_roles", value: rolesToMap }];
    const patchResult = await signedRequest("PATCH", "/_plugins/_security/api/rolesmapping/all_access", patchBody);
    if (patchResult.statusCode >= 200 && patchResult.statusCode < 300) {
      console.log("Successfully patched all_access role mapping");
    } else {
      console.error(`PATCH also failed: ${patchResult.statusCode} ${patchResult.body}`);
    }
  }
}

/**
 * Seeds all data into OpenSearch.
 */
async function seedData() {
  // First, map all Lambda roles to all_access
  await mapRolesToAllAccess();

  // Create indices with mappings
  for (const [indexName, mappings] of Object.entries(INDEX_MAPPINGS)) {
    await createIndex(indexName, mappings);
  }

  // Bulk-index sample data
  await bulkIndex("index-employees", EMPLOYEES, "employeeId");
  await bulkIndex("index-teams", TEAMS, "teamId");
  await bulkIndex("index-job-postings", JOB_POSTINGS, "postingId");
  await bulkIndex("index-compensation", COMPENSATION, "compensationId");

  console.log("Data seeding completed successfully");
}

// --- CloudFormation Custom Resource Handler ---

/**
 * Sends a response to CloudFormation for the custom resource.
 */
async function sendResponse(event, status, reason) {
  const https = require("https");
  const url = new URL(event.ResponseURL);

  const responseBody = JSON.stringify({
    Status: status,
    Reason: reason || "See CloudWatch logs",
    PhysicalResourceId: event.PhysicalResourceId || event.LogicalResourceId,
    StackId: event.StackId,
    RequestId: event.RequestId,
    LogicalResourceId: event.LogicalResourceId,
  });

  const options = {
    hostname: url.hostname,
    port: 443,
    path: url.pathname + url.search,
    method: "PUT",
    headers: {
      "Content-Type": "",
      "Content-Length": Buffer.byteLength(responseBody),
    },
  };

  return new Promise((resolve, reject) => {
    const req = https.request(options, (res) => {
      resolve();
    });
    req.on("error", reject);
    req.write(responseBody);
    req.end();
  });
}

/**
 * Lambda handler for CloudFormation custom resource.
 */
exports.handler = async (event, context) => {
  console.log("Event:", JSON.stringify(event, null, 2));

  try {
    if (event.RequestType === "Create" || event.RequestType === "Update") {
      await seedData();
      await sendResponse(event, "SUCCESS", "Data seeded successfully");
    } else if (event.RequestType === "Delete") {
      // Nothing to clean up - indices are in the OpenSearch domain which gets deleted
      await sendResponse(event, "SUCCESS", "Nothing to clean up");
    } else {
      await sendResponse(event, "SUCCESS", "Unknown request type, skipping");
    }
  } catch (error) {
    console.error("Error:", error);
    await sendResponse(event, "FAILED", error.message);
  }
};
