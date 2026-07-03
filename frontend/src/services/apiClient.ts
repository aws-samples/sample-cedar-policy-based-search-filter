import axios, { type AxiosInstance, type InternalAxiosRequestConfig } from 'axios';
import { fetchAuthSession } from '@aws-amplify/auth';
import { config } from '../config';

/**
 * Axios instance configured to communicate with the Search Service API.
 * Automatically attaches the user's access token as a Bearer header
 * on every request.
 *
 * Requirement 2.2: Store access/ID tokens, use access token in Authorization header
 */
const apiClient: AxiosInstance = axios.create({
  baseURL: config.api.baseUrl,
  headers: {
    'Content-Type': 'application/json',
  },
  timeout: 30000, // 30s timeout matching Lambda max execution time
});

/**
 * Request interceptor that attaches the current access token
 * to the Authorization header before each request.
 */
apiClient.interceptors.request.use(
  async (requestConfig: InternalAxiosRequestConfig) => {
    try {
      const session = await fetchAuthSession({ forceRefresh: false });
      // Cognito User Pools authorizer on API Gateway expects the ID token
      const idToken = session.tokens?.idToken?.toString();

      if (idToken) {
        requestConfig.headers.Authorization = idToken;
      }
    } catch {
      // If we can't get the token, let the request proceed without it.
      // The API Gateway will return 401 and the response interceptor will handle it.
    }

    return requestConfig;
  },
  (error) => Promise.reject(error)
);

/**
 * Response interceptor that handles authentication errors.
 * Requirement 2.6: If access token expires or becomes invalid,
 * redirect to login screen.
 */
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (axios.isAxiosError(error) && error.response?.status === 401) {
      // Token expired or invalid - redirect to login
      // The AuthContext will handle clearing state; we trigger via a custom event
      window.dispatchEvent(new CustomEvent('auth:unauthorized'));
    }
    return Promise.reject(error);
  }
);

export default apiClient;
