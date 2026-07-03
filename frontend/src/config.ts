/**
 * Application configuration derived from CDK stack outputs.
 * These values will be populated after CDK deployment.
 */
export const config = {
  cognito: {
    userPoolId: import.meta.env.VITE_COGNITO_USER_POOL_ID || '',
    userPoolClientId: import.meta.env.VITE_COGNITO_CLIENT_ID || '',
    domain: import.meta.env.VITE_COGNITO_DOMAIN || '',
    redirectSignIn: import.meta.env.VITE_REDIRECT_SIGN_IN || 'http://localhost:5173/',
    redirectSignOut: import.meta.env.VITE_REDIRECT_SIGN_OUT || 'http://localhost:5173/',
    region: import.meta.env.VITE_AWS_REGION || 'us-east-1',
  },
  api: {
    baseUrl: import.meta.env.VITE_API_BASE_URL || '',
  },
};
