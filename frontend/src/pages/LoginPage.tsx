import { useState } from 'react';
import { useAuth } from '../context/AuthContext';

/**
 * LoginPage component that displays a sign-in button triggering
 * Cognito Hosted UI redirect via Amplify Auth.
 *
 * Requirements:
 * - 2.1: Authenticate through Cognito Hosted UI
 * - 2.5: Display error message if authentication fails
 */
export function LoginPage() {
  const { login } = useAuth();
  const [error, setError] = useState<string | null>(null);
  const [isSigningIn, setIsSigningIn] = useState(false);

  const handleSignIn = async () => {
    setError(null);
    setIsSigningIn(true);
    try {
      await login();
    } catch {
      setError('Authentication was unsuccessful. Please try again.');
      setIsSigningIn(false);
    }
  };

  return (
    <div className="login-page">
      <div className="login-container">
        <h1>HR Portal</h1>
        <p>Sign in to access the HR Portal and search employee records.</p>

        {error && (
          <div className="login-error" role="alert">
            {error}
          </div>
        )}

        <button
          onClick={handleSignIn}
          disabled={isSigningIn}
          className="sign-in-button"
        >
          {isSigningIn ? 'Redirecting...' : 'Sign In'}
        </button>
      </div>
    </div>
  );
}
