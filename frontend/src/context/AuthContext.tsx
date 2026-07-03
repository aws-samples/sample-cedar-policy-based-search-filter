import {
  createContext,
  useContext,
  useState,
  useEffect,
  useCallback,
  type ReactNode,
} from 'react';
import { Amplify } from 'aws-amplify';
import {
  signInWithRedirect,
  signOut,
  getCurrentUser,
  fetchAuthSession,
} from '@aws-amplify/auth';
import { config } from '../config';

// Configure Amplify with Cognito settings
Amplify.configure({
  Auth: {
    Cognito: {
      userPoolId: config.cognito.userPoolId,
      userPoolClientId: config.cognito.userPoolClientId,
      loginWith: {
        oauth: {
          domain: config.cognito.domain,
          scopes: ['openid', 'email', 'profile'],
          redirectSignIn: [config.cognito.redirectSignIn],
          redirectSignOut: [config.cognito.redirectSignOut],
          responseType: 'code',
        },
      },
    },
  },
});

export interface AuthUser {
  username: string;
  role: string;
}

interface AuthTokens {
  accessToken: string;
  idToken: string;
}

interface AuthContextValue {
  user: AuthUser | null;
  tokens: AuthTokens | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: () => Promise<void>;
  logout: () => Promise<void>;
  getAccessToken: () => Promise<string | null>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

interface AuthProviderProps {
  children: ReactNode;
}

export function AuthProvider({ children }: AuthProviderProps) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [tokens, setTokens] = useState<AuthTokens | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  /**
   * Fetches the current session tokens from Amplify and extracts
   * the user's role from the ID token claims.
   */
  const refreshSession = useCallback(async (): Promise<AuthTokens | null> => {
    try {
      const session = await fetchAuthSession({ forceRefresh: false });
      const accessToken = session.tokens?.accessToken?.toString();
      const idToken = session.tokens?.idToken?.toString();

      if (!accessToken || !idToken) {
        return null;
      }

      const newTokens: AuthTokens = { accessToken, idToken };
      setTokens(newTokens);

      // Extract role from ID token claims (custom:role attribute)
      const idTokenPayload = session.tokens?.idToken?.payload;
      const role = (idTokenPayload?.['custom:role'] as string) || 'Unknown';
      const username =
        (idTokenPayload?.['cognito:username'] as string) ||
        (idTokenPayload?.['preferred_username'] as string) ||
        'User';

      setUser({ username, role });
      return newTokens;
    } catch {
      setTokens(null);
      setUser(null);
      return null;
    }
  }, []);

  /**
   * On mount, check if the user is already authenticated
   * and load their session.
   */
  useEffect(() => {
    const checkAuth = async () => {
      try {
        await getCurrentUser();
        await refreshSession();
      } catch {
        // User is not authenticated
        setUser(null);
        setTokens(null);
      } finally {
        setIsLoading(false);
      }
    };

    checkAuth();
  }, [refreshSession]);

  /**
   * Initiates login via Cognito Hosted UI redirect.
   * Requirement 2.1: Authenticate through Cognito Hosted UI
   */
  const login = useCallback(async () => {
    await signInWithRedirect();
  }, []);

  /**
   * Clears tokens and redirects to login.
   * Requirement 2.3: Logout clears tokens and redirects to login
   */
  const logout = useCallback(async () => {
    try {
      setUser(null);
      setTokens(null);
      // Use local sign out to avoid Cognito hosted UI redirect issues
      await signOut({ global: false });
    } catch {
      // Even if sign out fails remotely, clear local state
      setUser(null);
      setTokens(null);
    }
  }, []);

  /**
   * Returns a valid access token, refreshing the session if needed.
   * Requirement 2.2: Use access token in Authorization header
   * Requirement 2.6 (handled): If token is expired, redirect to login
   */
  const getAccessToken = useCallback(async (): Promise<string | null> => {
    try {
      const session = await fetchAuthSession({ forceRefresh: false });
      const accessToken = session.tokens?.accessToken?.toString();

      if (!accessToken) {
        // Token expired or invalid - redirect to login (Requirement 2.6)
        setUser(null);
        setTokens(null);
        return null;
      }

      // Update stored tokens
      setTokens((prev) => (prev ? { ...prev, accessToken } : null));
      return accessToken;
    } catch {
      // Session invalid - clear state so UI redirects to login
      setUser(null);
      setTokens(null);
      return null;
    }
  }, []);

  const value: AuthContextValue = {
    user,
    tokens,
    isAuthenticated: !!user && !!tokens,
    isLoading,
    login,
    logout,
    getAccessToken,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

/**
 * Hook to access authentication state and actions.
 */
export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
